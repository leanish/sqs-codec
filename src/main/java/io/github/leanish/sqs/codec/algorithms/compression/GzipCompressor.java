/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.errorprone.annotations.Immutable;

/**
 * Gzip implementation of the compressor strategy.
 */
@Immutable
public class GzipCompressor implements Compressor {

    private static final String ALGORITHM = "gzip";

    @Override
    public byte[] compress(byte[] payload) {
        // Wrap library-layer runtime failures consistently with checked compression errors.
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            try (OutputStream compressedStream = new GZIPOutputStream(outputStream)) {
                compressedStream.write(payload);
            }
            return outputStream.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw CompressionException.compress(ALGORITHM, e);
        }
    }

    @Override
    public byte[] decompress(byte[] payload) {
        // Wrap library-layer runtime failures consistently with checked decompression errors.
        try (ByteArrayInputStream compressedStream = new ByteArrayInputStream(payload);
                InputStream inputStream = new GZIPInputStream(compressedStream);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw CompressionException.decompress(ALGORITHM, e);
        }
    }
}
