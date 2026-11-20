/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
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
    private static final int ONE_STEP_INITIAL_CAPACITY = 1024 * 1024;
    private static final int ONE_STEP_GROWTH = 4;
    // The one-step buffer's ceiling: HotSpot refuses array lengths close to Integer.MAX_VALUE even with heap to spare
    // (down to 2^31 - 32 bytes with -XX:ObjectAlignmentInBytes=256), so it stays clear of them.
    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 64;
    private final @Nullable CompressionLevel compressionLevel;

    public ZstdCompressor() {
        this.compressionLevel = null;
    }

    public ZstdCompressor(CompressionLevel compressionLevel) {
        this.compressionLevel = Objects.requireNonNull(compressionLevel, "compressionLevel");
    }

    /**
     * Compresses in one pass, so the frame records its content size and declares a window no larger than the payload,
     * so any limit {@link #decompress} is given that allows the payload also allows its window.
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
            // Streaming allocates the window a frame declares before producing anything, so it may not exceed the limit.
            inputStream.setLongMax(windowLogFor(maxLength));
            return BoundedReads.readAtMost(inputStream, maxLength, ALGORITHM);
        } catch (DecompressedPayloadTooLargeException e) {
            throw e;
        } catch (ZstdIOException e) {
            if (e.getErrorCode() == Zstd.errFrameParameterWindowTooLarge()) {
                // Until 0.7.0: 0.5.x streamed its frames, which declare the level's window (128 MiB at MAXIMUM) whatever
                // their size. The streaming decoder is closed by now, and the payload is decoded again from the start.
                return decompressInOneStep(payload, maxLength);
            }
            throw CompressionException.decompress(ALGORITHM, e);
        } catch (IOException | RuntimeException e) {
            throw CompressionException.decompress(ALGORITHM, e);
        }
    }

    /**
     * Decompresses {@code payload}, producing at most 16 MiB (the interceptor's default limit).
     *
     * @param payload the compressed bytes
     * @return the decompressed bytes
     * @deprecated use {@link #decompress(byte[], int)}, which takes the limit
     */
    @Deprecated(since = "0.6.0")
    @Override
    // Declared again so code that looks the 0.5.x method up on this class (getDeclaredMethod) still finds it.
    public byte[] decompress(byte[] payload) {
        return Compressor.super.decompress(payload);
    }

    /**
     * Decodes the whole payload in one step into a buffer of at most {@code maxLength} bytes, after streaming refused a
     * frame's window. zstd then uses the output as its window and allocates none, whatever window the frames declare. The
     * buffer starts at 1 MiB (or the limit, if smaller) and grows only while the output doesn't fit, so a small payload
     * doesn't make it allocate the whole limit (a malformed one may, before zstd finds the error); each growth decodes the
     * payload again.
     */
    private static byte[] decompressInOneStep(byte[] payload, int maxLength) {
        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            return decompressInOneStep(context, payload, maxLength);
        } catch (CompressionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw CompressionException.decompress(ALGORITHM, e);
        }
    }

    private static byte[] decompressInOneStep(ZstdDecompressCtx context, byte[] payload, int maxLength) {
        int ceiling = oneStepCeiling(maxLength);
        int capacity = Math.min(ceiling, ONE_STEP_INITIAL_CAPACITY);
        while (true) {
            byte[] output = new byte[capacity];
            try {
                int length = context.decompressByteArray(output, 0, capacity, payload, 0, payload.length);
                return length == capacity ? output : Arrays.copyOf(output, length);
            } catch (ZstdException e) {
                if (e.getErrorCode() != Zstd.errDstSizeTooSmall()) {
                    throw CompressionException.decompress(ALGORITHM, e);
                }
                if (capacity == ceiling) {
                    throw DecompressedPayloadTooLargeException.exceeds(ALGORITHM, ceiling);
                }
                capacity = nextCapacity(capacity, ceiling);
            }
        }
    }

    /**
     * The largest buffer the one-step decoder may use: the limit, or a ceiling just under the array lengths HotSpot
     * refuses, if smaller.
     */
    static int oneStepCeiling(int maxLength) {
        return Math.min(maxLength, MAX_ARRAY_LENGTH);
    }

    /**
     * The next buffer size after {@code capacity} didn't fit: four times larger, at most {@code ceiling}.
     */
    static int nextCapacity(int capacity, int ceiling) {
        return (int) Math.min(ceiling, (long) capacity * ONE_STEP_GROWTH);
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
