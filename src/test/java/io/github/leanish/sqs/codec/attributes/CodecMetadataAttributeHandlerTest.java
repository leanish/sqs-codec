/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.leanish.sqs.codec.CodecConfiguration;
import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

class CodecMetadataAttributeHandlerTest {

    @ParameterizedTest
    @MethodSource("metadataCombinationCases")
    void fromAttributes_combinationMatrix(
            String rawMetadata,
            CompressionAlgorithm compressionAlgorithm,
            ChecksumAlgorithm checksumAlgorithm,
            String expectedChecksumValue,
            String expectedCanonicalMetadata) {
        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.fromAttributes(metadataAttributes(rawMetadata));

        assertThat(metadata.configuration())
                .isEqualTo(new CodecConfiguration(
                        CodecAttributes.VERSION_VALUE,
                        compressionAlgorithm,
                        checksumAlgorithm));
        assertThat(metadata.checksumValue()).isEqualTo(expectedChecksumValue);
        assertThat(formattedMetadata(metadata)).isEqualTo(expectedCanonicalMetadata);
    }

    @ParameterizedTest
    @MethodSource("lenientRawLengthCases")
    void fromAttributes_lenientRawLength(
            String rawMetadata,
            CodecConfiguration expectedConfiguration,
            String expectedChecksumValue,
            String expectedCanonicalMetadata) {
        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.fromAttributes(metadataAttributes(rawMetadata));

        assertThat(metadata.configuration()).isEqualTo(expectedConfiguration);
        assertThat(metadata.checksumValue()).isEqualTo(expectedChecksumValue);
        assertThat(formattedMetadata(metadata)).isEqualTo(expectedCanonicalMetadata);
    }

    @ParameterizedTest
    @MethodSource("metadataPermutationCases")
    void fromAttributes_keyOrderAndWhitespacePermutations(
            String rawMetadata,
            CodecConfiguration expectedConfiguration,
            String expectedChecksumValue,
            String expectedCanonicalMetadata) {
        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.fromAttributes(metadataAttributes(rawMetadata));

        assertThat(metadata.configuration()).isEqualTo(expectedConfiguration);
        assertThat(metadata.checksumValue()).isEqualTo(expectedChecksumValue);
        assertThat(formattedMetadata(metadata)).isEqualTo(expectedCanonicalMetadata);
    }

    @ParameterizedTest
    @MethodSource("unknownKeyToleranceCases")
    void fromAttributes_unknownKeysIgnored(
            String rawMetadata,
            CodecConfiguration expectedConfiguration,
            String expectedChecksumValue,
            String expectedCanonicalMetadata) {
        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.fromAttributes(metadataAttributes(rawMetadata));

        assertThat(metadata.configuration()).isEqualTo(expectedConfiguration);
        assertThat(metadata.checksumValue()).isEqualTo(expectedChecksumValue);
        assertThat(formattedMetadata(metadata)).isEqualTo(expectedCanonicalMetadata);
    }

    @ParameterizedTest
    @MethodSource("invalidMetadataCases")
    void fromAttributes_invalidMetadata(
            String rawMetadata,
            Class<? extends RuntimeException> expectedException,
            String expectedMessage) {
        assertThatThrownBy(() -> CodecMetadataAttributeHandler.fromAttributes(metadataAttributes(rawMetadata)))
                .isInstanceOf(expectedException)
                .hasMessage(expectedMessage);
    }

    @Test
    void forOutbound_disallowsNoOpMetadata() {
        CodecConfiguration configuration = new CodecConfiguration(
                CodecAttributes.VERSION_VALUE,
                CompressionAlgorithm.NONE,
                ChecksumAlgorithm.NONE);

        assertThatThrownBy(() -> CodecMetadataAttributeHandler.forOutbound(
                configuration,
                "payload".getBytes(StandardCharsets.UTF_8),
                true))
                .isInstanceOf(UnsupportedCodecMetadataException.class)
                .hasMessage("Codec metadata must enable compression or checksum");
    }

    @Test
    void forOutbound_omitsRawLengthWhenDisabled() {
        CodecConfiguration configuration = new CodecConfiguration(
                CodecAttributes.VERSION_VALUE,
                CompressionAlgorithm.ZSTD,
                ChecksumAlgorithm.MD5);

        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.forOutbound(
                configuration,
                "payload".getBytes(StandardCharsets.UTF_8),
                false);

        assertThat(formattedMetadata(metadata))
                .isEqualTo(
                        "v=1;c=zstd;h=md5;s="
                                + ChecksumAlgorithm.MD5.implementation().checksum("payload".getBytes(StandardCharsets.UTF_8)));
    }

    private static Stream<Arguments> metadataCombinationCases() {
        return Arrays.stream(CompressionAlgorithm.values())
                .flatMap(compressionAlgorithm -> Arrays.stream(ChecksumAlgorithm.values())
                        .filter(checksumAlgorithm -> compressionAlgorithm != CompressionAlgorithm.NONE
                                || checksumAlgorithm != ChecksumAlgorithm.NONE)
                        .map(checksumAlgorithm -> {
                            String checksumValue = checksumAlgorithm == ChecksumAlgorithm.NONE
                                    ? null
                                    : "checksum-" + checksumAlgorithm.id();
                            String rawMetadata = "v=1;c=" + compressionAlgorithm.id()
                                    + ";h=" + checksumAlgorithm.id()
                                    + (checksumValue == null ? "" : ";s=" + checksumValue)
                                    + ";l=12";
                            String expectedCanonicalMetadata = "v=1;c=" + compressionAlgorithm.id()
                                    + ";h=" + checksumAlgorithm.id()
                                    + (checksumValue == null ? "" : ";s=" + checksumValue)
                                    + (compressionAlgorithm == CompressionAlgorithm.NONE ? "" : ";l=12");
                            return Arguments.of(
                                    rawMetadata,
                                    compressionAlgorithm,
                                    checksumAlgorithm,
                                    checksumValue,
                                    expectedCanonicalMetadata);
                        }));
    }

    private static Stream<Arguments> lenientRawLengthCases() {
        CodecConfiguration md5Configuration = new CodecConfiguration(
                CodecAttributes.VERSION_VALUE,
                CompressionAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        CodecConfiguration gzipConfiguration = new CodecConfiguration(
                CodecAttributes.VERSION_VALUE,
                CompressionAlgorithm.GZIP,
                ChecksumAlgorithm.NONE);
        return Stream.of(
                Arguments.of("v=1;c=none;h=md5;s=abc", md5Configuration, "abc", "v=1;c=none;h=md5;s=abc"),
                Arguments.of("v=1;c=none;h=md5;s=abc;l=", md5Configuration, "abc", "v=1;c=none;h=md5;s=abc"),
                Arguments.of("v=1;c=none;h=md5;s=abc;l=-1", md5Configuration, "abc", "v=1;c=none;h=md5;s=abc"),
                Arguments.of("v=1;c=none;h=md5;s=abc;l=abc", md5Configuration, "abc", "v=1;c=none;h=md5;s=abc"),
                Arguments.of("v=1;c=gzip;h=none", gzipConfiguration, null, "v=1;c=gzip;h=none"),
                Arguments.of("v=1;c=gzip;h=none;l=", gzipConfiguration, null, "v=1;c=gzip;h=none"),
                Arguments.of("v=1;c=gzip;h=none;l=-1", gzipConfiguration, null, "v=1;c=gzip;h=none"),
                Arguments.of("v=1;c=gzip;h=none;l=abc", gzipConfiguration, null, "v=1;c=gzip;h=none"));
    }

    private static Stream<Arguments> metadataPermutationCases() {
        return Stream.of(
                Arguments.of(
                        "h=md5;s=abc;l=12;c=gzip;v=1",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.GZIP,
                                ChecksumAlgorithm.MD5),
                        "abc",
                        "v=1;c=gzip;h=md5;s=abc;l=12"),
                Arguments.of(
                        "  V = 1 ; C = ZSTD ; H = SHA256 ; S = q ; L = 5  ",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.ZSTD,
                                ChecksumAlgorithm.SHA256),
                        "q",
                        "v=1;c=zstd;h=sha256;s=q;l=5"),
                Arguments.of(
                        "l=9;;;h=none;;;c=snappy;v=1;;",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.SNAPPY,
                                ChecksumAlgorithm.NONE),
                        null,
                        "v=1;c=snappy;h=none;l=9"),
                Arguments.of(
                        "v=1;h=md5;s=abc",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.NONE,
                                ChecksumAlgorithm.MD5),
                        "abc",
                        "v=1;c=none;h=md5;s=abc"),
                Arguments.of(
                        "v=1;c=gzip;l=11",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.GZIP,
                                ChecksumAlgorithm.NONE),
                        null,
                        "v=1;c=gzip;h=none;l=11"));
    }

    private static Stream<Arguments> unknownKeyToleranceCases() {
        return Stream.of(
                Arguments.of(
                        "v=1;c=gzip;x=base64;h=md5;s=abc;l=12;y=ignored;extra=value",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.GZIP,
                                ChecksumAlgorithm.MD5),
                        "abc",
                        "v=1;c=gzip;h=md5;s=abc;l=12"),
                Arguments.of(
                        "v=1;c=none;h=md5;s=abc;future-flag=true",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.NONE,
                                ChecksumAlgorithm.MD5),
                        "abc",
                        "v=1;c=none;h=md5;s=abc"),
                Arguments.of(
                        "v=1;c=gzip;h=none;l=3;future-flag=",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.GZIP,
                                ChecksumAlgorithm.NONE),
                        null,
                        "v=1;c=gzip;h=none;l=3"));
    }

    private static Stream<Arguments> invalidMetadataCases() {
        return Stream.of(
                Arguments.of(
                        "c=none;h=md5;s=abc",
                        UnsupportedCodecMetadataException.class,
                        "Missing required codec metadata key: v"),
                Arguments.of(
                        "v=1",
                        UnsupportedCodecMetadataException.class,
                        "Codec metadata must enable compression or checksum"),
                Arguments.of(
                        "v=1;c=none;h=none",
                        UnsupportedCodecMetadataException.class,
                        "Codec metadata must enable compression or checksum"),
                Arguments.of(
                        "v=1;c=none;h=none;s=abc",
                        UnsupportedCodecMetadataException.class,
                        "Codec metadata must enable compression or checksum"),
                Arguments.of(
                        "v=1;l=12",
                        UnsupportedCodecMetadataException.class,
                        "Codec metadata must enable compression or checksum"));
    }

    private static Map<String, MessageAttributeValue> metadataAttributes(String rawMetadata) {
        return Map.of(
                CodecAttributes.META,
                MessageAttributeUtils.stringAttribute(rawMetadata));
    }

    private static String formattedMetadata(CodecMetadataAttributeHandler metadata) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        metadata.applyTo(attributes);
        return attributes.get(CodecAttributes.META).stringValue();
    }
}
