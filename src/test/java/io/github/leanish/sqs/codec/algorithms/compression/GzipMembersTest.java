/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class GzipMembersTest {

    private static final int FHCRC = 2;
    private static final int FEXTRA = 4;
    private static final int FNAME = 8;
    private static final int FCOMMENT = 16;
    private static final int ALL_OPTIONAL_FIELDS = FHCRC | FEXTRA | FNAME | FCOMMENT;
    private static final int LIMIT = 1024 * 1024;
    private static final byte[] TEXT = "concatenated gzip members".getBytes(StandardCharsets.US_ASCII);

    private final GzipCompressor compressor = new GzipCompressor();

    @Test
    void decompress_manyEmptyMembersDecodeToNothing() {
        byte[] payload = repeat(member(new byte[0], 0), 50_000);

        assertThat(compressor.decompress(payload, LIMIT)).isEmpty();
    }

    @Test
    void decompress_manyEmptyMembersThenContent() {
        byte[] payload = concat(repeat(member(new byte[0], 0), 50_000), member(TEXT, 0));

        assertThat(compressor.decompress(payload, LIMIT)).isEqualTo(TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedPayloads")
    void decompress_matchesGzipInputStream(String description, byte[] payload, byte[] expected) throws IOException {
        assertThat(gzipInputStream(payload)).isEqualTo(expected);
        assertThat(compressor.decompress(payload, LIMIT)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedPayloads")
    void decompress_refusesWhatGzipInputStreamRefuses(String description, byte[] payload) {
        assertThatThrownBy(() -> gzipInputStream(payload)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> compressor.decompress(payload, LIMIT))
                .isInstanceOf(CompressionException.class)
                .isNotInstanceOf(DecompressedPayloadTooLargeException.class)
                .hasMessage("Failed to decompress payload with gzip")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void decompress_countsTheLimitAcrossEmptyAndFullMembers() {
        byte[] payload = concat(member(TEXT, 0), repeat(member(new byte[0], 0), 1_000), member(TEXT, FNAME));

        assertThat(compressor.decompress(payload, 2 * TEXT.length)).isEqualTo(concat(TEXT, TEXT));
        assertThatThrownBy(() -> compressor.decompress(payload, 2 * TEXT.length - 1))
                .isInstanceOf(DecompressedPayloadTooLargeException.class);
    }

    private static Stream<Arguments> acceptedPayloads() {
        byte[] mixed = mixed(100_000);
        byte[] text = member(TEXT, 0);
        return Stream.of(
                Arguments.of("one member", text, TEXT),
                Arguments.of("one member with every optional header field", member(TEXT, ALL_OPTIONAL_FIELDS), TEXT),
                Arguments.of("one member with an extra field", member(TEXT, FEXTRA), TEXT),
                Arguments.of("a member larger than the read buffer", member(mixed, 0), mixed),
                Arguments.of("two members", concat(text, member(mixed, ALL_OPTIONAL_FIELDS)), concat(TEXT, mixed)),
                Arguments.of("an empty member", member(new byte[0], 0), new byte[0]),
                Arguments.of("empty members around content",
                        concat(repeat(member(new byte[0], FHCRC), 50), text, repeat(member(new byte[0], 0), 50)),
                        TEXT),
                Arguments.of("trailing bytes shorter than a header", concat(text, bytes(1, 2, 3)), TEXT),
                Arguments.of("trailing garbage", concat(text, new byte[64]), TEXT),
                Arguments.of("a next member with the wrong method", concat(text, withByte(text, 2, 7)), TEXT),
                Arguments.of("a next header cut inside its file name",
                        concat(text, Arrays.copyOf(nameWithoutTerminator(), 30)), TEXT),
                Arguments.of("a next header with a wrong header CRC",
                        concat(text, corruptHeaderCrc(member(TEXT, FHCRC | FNAME))), TEXT));
    }

    private static Stream<Arguments> refusedPayloads() {
        byte[] text = member(TEXT, 0);
        byte[] mixed = member(mixed(100_000), 0);
        return Stream.of(
                Arguments.of("an empty payload", new byte[0]),
                Arguments.of("a truncated first header", Arrays.copyOf(text, 7)),
                Arguments.of("a first header cut inside its file name", nameWithoutTerminator()),
                Arguments.of("an extra field longer than the payload",
                        concat(bytes(0x1f, 0x8b, 8, FEXTRA, 0, 0, 0, 0, 0, 0xff, 0xff, 0xff), new byte[64])),
                Arguments.of("not gzip", new byte[64]),
                Arguments.of("an unsupported method", withByte(text, 2, 7)),
                Arguments.of("a wrong header CRC", corruptHeaderCrc(member(TEXT, FHCRC | FEXTRA))),
                Arguments.of("invalid deflate data", withByte(text, 10, 0xff)),
                Arguments.of("truncated deflate data", Arrays.copyOf(mixed, mixed.length / 2)),
                Arguments.of("a wrong CRC", withByte(text, text.length - 8, text[text.length - 8] ^ 1)),
                Arguments.of("a wrong size", withByte(text, text.length - 1, text[text.length - 1] ^ 1)),
                Arguments.of("a truncated trailer", Arrays.copyOf(text, text.length - 2)),
                Arguments.of("a valid next header with truncated data",
                        concat(text, Arrays.copyOf(mixed, mixed.length / 2))),
                Arguments.of("a wrong CRC in a later member",
                        concat(text, withByte(text, text.length - 8, text[text.length - 8] ^ 1))),
                Arguments.of("a wrong CRC in a trailing empty member",
                        concat(text, withByte(member(new byte[0], 0), 12, 1))));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 15})
    void decompress_refusesAShortTailThatParsesAsAHeader(int tailLength) {
        // Corretto 25.0.1's GZIPInputStream refuses it too; 17.0.19 and 21.0.11 ignore a tail of 18 bytes or fewer
        // once their buffer holds all of it.
        // 10 bytes is a header ending exactly at the end of the payload.
        byte[] payload = concat(member(TEXT, 0), Arrays.copyOf(member(TEXT, 0), tailLength));

        assertThatThrownBy(() -> compressor.decompress(payload, LIMIT))
                .isInstanceOf(CompressionException.class)
                .hasCauseInstanceOf(EOFException.class);
    }

    private static byte[] gzipInputStream(byte[] payload) throws IOException {
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            return input.readAllBytes();
        }
    }

    /** A gzip member built by hand, so every optional header field can be set. */
    private static byte[] member(byte[] content, int flags) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(bytes(0x1f, 0x8b, 8, flags, 0, 0, 0, 0, 0, 0xff));
        if ((flags & FEXTRA) == FEXTRA) {
            bytes.writeBytes(bytes(3, 0, 'x', 'y', 'z'));
        }
        if ((flags & FNAME) == FNAME) {
            bytes.writeBytes(bytes('n', 'a', 'm', 'e', 0));
        }
        if ((flags & FCOMMENT) == FCOMMENT) {
            bytes.writeBytes(bytes('c', 'o', 'm', 'm', 'e', 'n', 't', 0));
        }
        if ((flags & FHCRC) == FHCRC) {
            CRC32 headerCrc = new CRC32();
            headerCrc.update(bytes.toByteArray());
            writeLittleEndian(bytes, headerCrc.getValue(), 2);
        }
        bytes.writeBytes(rawDeflate(content));
        CRC32 contentCrc = new CRC32();
        contentCrc.update(content);
        writeLittleEndian(bytes, contentCrc.getValue(), 4);
        writeLittleEndian(bytes, content.length, 4);
        return bytes.toByteArray();
    }

    private static byte[] nameWithoutTerminator() {
        byte[] name = new byte[40];
        Arrays.fill(name, (byte) 'n');
        return concat(bytes(0x1f, 0x8b, 8, FNAME, 0, 0, 0, 0, 0, 0xff), name);
    }

    private static byte[] corruptHeaderCrc(byte[] member) {
        int headerCrcEnd = member.length - rawDeflate(TEXT).length - 8;
        return withByte(member, headerCrcEnd - 1, member[headerCrcEnd - 1] ^ 1);
    }

    private static byte[] rawDeflate(byte[] content) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(content);
            deflater.finish();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                bytes.write(buffer, 0, deflater.deflate(buffer));
            }
            return bytes.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeLittleEndian(ByteArrayOutputStream bytes, long value, int length) {
        for (int i = 0; i < length; i++) {
            bytes.write((int) (value >>> (8 * i)));
        }
    }

    private static byte[] withByte(byte[] source, int index, int value) {
        byte[] copy = source.clone();
        copy[index] = (byte) value;
        return copy;
    }

    private static byte[] repeat(byte[] part, int times) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(part.length * times);
        for (int i = 0; i < times; i++) {
            bytes.writeBytes(part);
        }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            bytes.writeBytes(part);
        }
        return bytes.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    /** Bytes that compress only partly. */
    private static byte[] mixed(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 31 + i / 7) % 251);
        }
        return bytes;
    }
}
