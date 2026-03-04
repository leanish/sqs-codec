/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

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
            String expectedChecksumValue) {
        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.fromAttributes(metadataAttributes(rawMetadata));

        assertThat(metadata.configuration())
                .isEqualTo(new CodecConfiguration(
                        CodecAttributes.VERSION_VALUE,
                        compressionAlgorithm,
                        checksumAlgorithm));
        assertThat(metadata.checksumValue()).isEqualTo(expectedChecksumValue);
        assertThat(formattedMetadata(metadata)).endsWith(";l=12");
    }

    @ParameterizedTest
    @MethodSource("lenientRawLengthCases")
    void fromAttributes_lenientRawLength(
            String rawMetadata,
            CodecConfiguration expectedConfiguration,
            String expectedChecksumValue) {
        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.fromAttributes(metadataAttributes(rawMetadata));

        assertThat(metadata.configuration()).isEqualTo(expectedConfiguration);
        assertThat(metadata.checksumValue()).isEqualTo(expectedChecksumValue);
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

    private static Stream<Arguments> metadataCombinationCases() {
        return Arrays.stream(CompressionAlgorithm.values())
                .flatMap(compressionAlgorithm -> Arrays.stream(ChecksumAlgorithm.values())
                        .map(checksumAlgorithm -> {
                            String checksumValue = checksumAlgorithm == ChecksumAlgorithm.NONE
                                    ? null
                                    : "checksum-" + checksumAlgorithm.id();
                            String rawMetadata = "v=1;c=" + compressionAlgorithm.id()
                                    + ";h=" + checksumAlgorithm.id()
                                    + (checksumValue == null ? "" : ";s=" + checksumValue)
                                    + ";l=12";
                            return Arguments.of(
                                    rawMetadata,
                                    compressionAlgorithm,
                                    checksumAlgorithm,
                                    checksumValue);
                        }));
    }

    private static Stream<Arguments> lenientRawLengthCases() {
        CodecConfiguration md5Configuration = new CodecConfiguration(
                CodecAttributes.VERSION_VALUE,
                CompressionAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        CodecConfiguration noneConfiguration = new CodecConfiguration(
                CodecAttributes.VERSION_VALUE,
                CompressionAlgorithm.NONE,
                ChecksumAlgorithm.NONE);
        return Stream.of(
                Arguments.of("v=1;c=none;h=none", noneConfiguration, null),
                Arguments.of("v=1;c=none;h=none;l=", noneConfiguration, null),
                Arguments.of("v=1;c=none;h=none;l=-1", noneConfiguration, null),
                Arguments.of("v=1;c=none;h=none;l=abc", noneConfiguration, null),
                Arguments.of("v=1;c=none;h=md5;s=abc", md5Configuration, "abc"),
                Arguments.of("v=1;c=none;h=md5;s=abc;l=", md5Configuration, "abc"),
                Arguments.of("v=1;c=none;h=md5;s=abc;l=-1", md5Configuration, "abc"),
                Arguments.of("v=1;c=none;h=md5;s=abc;l=abc", md5Configuration, "abc"));
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
                        ";;  l=11 ; h=NONE ; c=NONE ; v=1 ;;  ",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.NONE,
                                ChecksumAlgorithm.NONE),
                        null,
                        "v=1;c=none;h=none;l=11"));
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
                        "v=1;c=none;h=none;l=3;future-flag=true",
                        new CodecConfiguration(
                                CodecAttributes.VERSION_VALUE,
                                CompressionAlgorithm.NONE,
                                ChecksumAlgorithm.NONE),
                        null,
                        "v=1;c=none;h=none;l=3"));
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
