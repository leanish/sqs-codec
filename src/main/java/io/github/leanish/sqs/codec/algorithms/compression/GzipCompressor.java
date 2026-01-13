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
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Gzip implementation of the compressor strategy.
 */
public class GzipCompressor implements Compressor {

    @Override
    public byte[] compress(byte[] payload) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            try (OutputStream compressedStream = new GZIPOutputStream(outputStream)) {
                compressedStream.write(payload);
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] decompress(byte[] payload) {
        try (ByteArrayInputStream compressedStream = new ByteArrayInputStream(payload);
                InputStream inputStream = new GZIPInputStream(compressedStream);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
