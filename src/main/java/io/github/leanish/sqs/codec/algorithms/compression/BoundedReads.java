/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a decompressing stream to its end, refusing to produce more than a limit.
 */
final class BoundedReads {

    private static final int BUFFER_SIZE = 8192;

    private BoundedReads() {
    }

    /**
     * Reads {@code input} to its end, so trailers and checksums are still verified, and fails as soon as it yields more
     * than {@code maxLength} bytes: memory grows with what was read, never with what the payload claims.
     */
    static byte[] readAtMost(InputStream input, int maxLength, String algorithm) throws IOException {
        requireNonNegative(maxLength);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxLength, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxLength - output.size()) {
                throw DecompressedPayloadTooLargeException.exceeds(algorithm, maxLength);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static void requireNonNegative(int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must not be negative: " + maxLength);
        }
    }
}
