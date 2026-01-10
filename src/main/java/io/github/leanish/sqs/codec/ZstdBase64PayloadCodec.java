/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;

final class ZstdBase64PayloadCodec implements PayloadCodec {

    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    @Override
    public PayloadEncoding encoding() {
        return PayloadEncoding.ZSTD_BASE64;
    }

    @Override
    public String encode(String payload) {
        return encode(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String encode(byte[] payload) {
        return BASE64_ENCODER.encodeToString(compress(payload));
    }

    @Override
    public String decodeToString(String encoded) {
        return new String(decodeToBytes(encoded), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] decodeToBytes(String encoded) {
        try {
            return decompress(BASE64_DECODER.decode(encoded));
        } catch (IllegalArgumentException e) {
            throw new PayloadCodecException("Invalid base64 payload", e);
        }
    }

    private static byte[] compress(byte[] payload) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            try (OutputStream compressedStream = new ZstdOutputStreamNoFinalizer(outputStream)) {
                compressedStream.write(payload);
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] decompress(byte[] payload) {
        try (ByteArrayInputStream compressedStream = new ByteArrayInputStream(payload);
                InputStream inputStream = new ZstdInputStreamNoFinalizer(compressedStream);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
