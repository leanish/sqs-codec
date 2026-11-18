/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.encoding;

import java.nio.charset.StandardCharsets;

import com.google.errorprone.annotations.Immutable;

/**
 * Strict canonical ASCII85 codec without framing, whitespace or shorthand forms.
 *
 * <p><b>Experimental:</b> This codec is still experimental and may change before a stable
 * release.
 */
@Immutable
public final class Ascii85PayloadCodec implements PayloadCodec {

    private static final Ascii85PayloadCodec INSTANCE = new Ascii85PayloadCodec();

    private static final int INPUT_CHUNK_SIZE = 4;
    private static final int OUTPUT_CHUNK_SIZE = 5;
    private static final int RADIX = 85;
    private static final int ASCII_OFFSET = 33;
    private static final int MIN_ENCODED_BYTE = ASCII_OFFSET;
    private static final int MAX_ENCODED_BYTE = ASCII_OFFSET + RADIX - 1;
    private static final int PADDED_DIGIT = 'u' - ASCII_OFFSET;
    private static final long MAX_UINT32 = 0xffff_ffffL;

    private Ascii85PayloadCodec() {
    }

    public static Ascii85PayloadCodec instance() {
        return INSTANCE;
    }

    @Override
    public byte[] encode(byte[] payload) {
        if (payload.length == 0) {
            return new byte[0];
        }

        byte[] encoded = new byte[encodedLength(payload.length)];
        int outputOffset = 0;
        for (int inputOffset = 0; inputOffset < payload.length; inputOffset += INPUT_CHUNK_SIZE) {
            int chunkSize = Math.min(INPUT_CHUNK_SIZE, payload.length - inputOffset);
            long value = 0;
            for (int index = 0; index < INPUT_CHUNK_SIZE; index++) {
                value <<= Byte.SIZE;
                if (index < chunkSize) {
                    value |= payload[inputOffset + index] & 0xffL;
                }
            }

            byte[] encodedChunk = encodeChunk(value);
            int encodedChunkSize = chunkSize == INPUT_CHUNK_SIZE ? OUTPUT_CHUNK_SIZE : chunkSize + 1;
            System.arraycopy(encodedChunk, 0, encoded, outputOffset, encodedChunkSize);
            outputOffset += encodedChunkSize;
        }
        return encoded;
    }

    public String encodeToString(byte[] payload) {
        return new String(encode(payload), StandardCharsets.US_ASCII);
    }

    @Override
    public byte[] decode(byte[] encoded) {
        try {
            return decodeInternal(encoded);
        } catch (IllegalArgumentException e) {
            throw new InvalidPayloadException("Invalid ascii85 payload", e);
        }
    }

    private byte[] decodeInternal(byte[] encoded) {
        if (encoded.length == 0) {
            return new byte[0];
        }

        int trailingChars = encoded.length % OUTPUT_CHUNK_SIZE;
        if (trailingChars == 1) {
            throw new IllegalArgumentException("ASCII85 payload cannot end with a single character");
        }

        byte[] decoded = new byte[decodedLength(encoded.length)];
        int outputOffset = 0;
        int inputOffset = 0;
        while (inputOffset + OUTPUT_CHUNK_SIZE <= encoded.length) {
            long value = decodeChunk(encoded, inputOffset, OUTPUT_CHUNK_SIZE);
            outputOffset = writeDecodedBytes(value, decoded, outputOffset, INPUT_CHUNK_SIZE);
            inputOffset += OUTPUT_CHUNK_SIZE;
        }
        if (trailingChars > 0) {
            long value = decodeChunk(encoded, inputOffset, trailingChars);
            writeDecodedBytes(value, decoded, outputOffset, trailingChars - 1);
        }
        return decoded;
    }

    private static byte[] encodeChunk(long value) {
        byte[] encoded = new byte[OUTPUT_CHUNK_SIZE];
        long remaining = value;
        for (int index = OUTPUT_CHUNK_SIZE - 1; index >= 0; index--) {
            encoded[index] = (byte) (remaining % RADIX + ASCII_OFFSET);
            remaining /= RADIX;
        }
        return encoded;
    }

    private static long decodeChunk(byte[] encoded, int offset, int chunkSize) {
        long value = 0;
        for (int index = 0; index < OUTPUT_CHUNK_SIZE; index++) {
            int digit = index < chunkSize
                    ? decodeDigit(encoded[offset + index] & 0xff)
                    : PADDED_DIGIT;
            value = value * RADIX + digit;
        }
        if (value > MAX_UINT32) {
            throw new IllegalArgumentException("ASCII85 chunk exceeds 32-bit range");
        }
        return value;
    }

    private static int decodeDigit(int encodedByte) {
        if (encodedByte < MIN_ENCODED_BYTE || encodedByte > MAX_ENCODED_BYTE) {
            throw new IllegalArgumentException("ASCII85 payload contains an invalid character");
        }
        return encodedByte - ASCII_OFFSET;
    }

    private static int writeDecodedBytes(long value, byte[] target, int offset, int count) {
        for (int index = 0; index < count; index++) {
            target[offset + index] = (byte) (value >> ((INPUT_CHUNK_SIZE - 1 - index) * Byte.SIZE));
        }
        return offset + count;
    }

    private static int encodedLength(int payloadLength) {
        int fullChunks = payloadLength / INPUT_CHUNK_SIZE;
        int trailingBytes = payloadLength % INPUT_CHUNK_SIZE;
        return fullChunks * OUTPUT_CHUNK_SIZE + (trailingBytes == 0 ? 0 : trailingBytes + 1);
    }

    private static int decodedLength(int encodedLength) {
        int fullChunks = encodedLength / OUTPUT_CHUNK_SIZE;
        int trailingChars = encodedLength % OUTPUT_CHUNK_SIZE;
        // Single-character tails are rejected in decodeInternal before this subtraction runs.
        return fullChunks * INPUT_CHUNK_SIZE + (trailingChars == 0 ? 0 : trailingChars - 1);
    }
}
