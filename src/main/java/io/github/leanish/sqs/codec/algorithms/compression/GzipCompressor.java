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
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.Immutable;

import io.github.leanish.sqs.codec.algorithms.CompressionLevel;

/**
 * Gzip implementation of the compressor strategy.
 */
@Immutable
public class GzipCompressor implements Compressor {

    private static final String ALGORITHM = "gzip";
    private final @Nullable CompressionLevel compressionLevel;

    public GzipCompressor() {
        this.compressionLevel = null;
    }

    public GzipCompressor(CompressionLevel compressionLevel) {
        this.compressionLevel = Objects.requireNonNull(compressionLevel, "compressionLevel");
    }

    @Override
    public byte[] compress(byte[] payload) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            try (OutputStream compressedStream = compressionLevel == null
                    ? new GZIPOutputStream(outputStream)
                    : new ConfigurableGzipOutputStream(outputStream, deflaterLevel(compressionLevel))) {
                compressedStream.write(payload);
            }
            return outputStream.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw CompressionException.compress(ALGORITHM, e);
        }
    }

    @Override
    public byte[] decompress(byte[] payload) {
        try (ByteArrayInputStream compressedStream = new ByteArrayInputStream(payload);
                InputStream inputStream = new GZIPInputStream(compressedStream);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw CompressionException.decompress(ALGORITHM, e);
        }
    }

    /**
     * Maps relative levels onto gzip's deflater scale: 1, 3, default(6), 8, 9.
     */
    static int deflaterLevel(CompressionLevel compressionLevel) {
        return switch (compressionLevel) {
            case MINIMUM -> Deflater.BEST_SPEED;
            case LOW -> 3;
            case MEDIUM -> Deflater.DEFAULT_COMPRESSION;
            case HIGH -> 8;
            case MAXIMUM -> Deflater.BEST_COMPRESSION;
        };
    }

    private static final class ConfigurableGzipOutputStream extends GZIPOutputStream {

        private ConfigurableGzipOutputStream(OutputStream outputStream, int compressionLevel) throws IOException {
            super(outputStream);
            def.setLevel(compressionLevel);
        }
    }
}
