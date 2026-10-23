/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdIOException;
import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.google.errorprone.annotations.Immutable;

import io.github.leanish.sqs.codec.algorithms.CompressionLevel;

/**
 * Zstandard implementation of the compressor strategy.
 */
@Immutable
public class ZstdCompressor implements Compressor {

    private static final String ALGORITHM = "zstd";
    private static final int MIN_WINDOW_LOG = 10;
    private final @Nullable CompressionLevel compressionLevel;

    public ZstdCompressor() {
        this.compressionLevel = null;
    }

    public ZstdCompressor(CompressionLevel compressionLevel) {
        this.compressionLevel = Objects.requireNonNull(compressionLevel, "compressionLevel");
    }

    /**
     * Compresses in one pass, so the frame records its content size and declares a window no larger than the payload,
     * which lets {@link #decompress} refuse any frame whose window exceeds its limit.
     */
    @Override
    public byte[] compress(byte[] payload) {
        try {
            return Zstd.compress(payload, compressionLevel == null ? Zstd.defaultCompressionLevel() : zstdLevel(compressionLevel));
        } catch (RuntimeException e) {
            throw CompressionException.compress(ALGORITHM, e);
        }
    }

    @Override
    public byte[] decompress(byte[] payload, int maxLength) {
        BoundedReads.requireNonNegative(maxLength);
        try (ByteArrayInputStream compressedStream = new ByteArrayInputStream(payload);
                ZstdInputStreamNoFinalizer inputStream = new ZstdInputStreamNoFinalizer(compressedStream)) {
            // The decoder allocates the window a frame declares before producing anything, so it may not exceed the limit.
            inputStream.setLongMax(windowLogFor(maxLength));
            return BoundedReads.readAtMost(inputStream, maxLength, ALGORITHM);
        } catch (DecompressedPayloadTooLargeException e) {
            throw e;
        } catch (ZstdIOException e) {
            if (e.getErrorCode() == Zstd.errFrameParameterWindowTooLarge()) {
                throw DecompressedPayloadTooLargeException.exceeds(ALGORITHM, maxLength);
            }
            throw CompressionException.decompress(ALGORITHM, e);
        } catch (IOException | RuntimeException e) {
            throw CompressionException.decompress(ALGORITHM, e);
        }
    }

    /**
     * The smallest window log (at least zstd's minimum, 10) whose window holds {@code maxLength} bytes.
     */
    static int windowLogFor(int maxLength) {
        return Math.max(MIN_WINDOW_LOG, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(maxLength, 1) - 1));
    }

    /**
     * Maps relative levels onto zstd's scale: 1, 2, default, 16, max.
     */
    static int zstdLevel(CompressionLevel compressionLevel) {
        return switch (compressionLevel) {
            case MINIMUM -> 1;
            case LOW -> 2;
            case MEDIUM -> Zstd.defaultCompressionLevel();
            case HIGH -> 16;
            case MAXIMUM -> Zstd.maxCompressionLevel();
        };
    }
}
