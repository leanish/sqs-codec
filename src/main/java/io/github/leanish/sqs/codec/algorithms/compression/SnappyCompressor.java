/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.io.IOException;

import org.xerial.snappy.Snappy;
import org.xerial.snappy.SnappyError;

import com.google.errorprone.annotations.Immutable;

/**
 * Snappy implementation of the compressor strategy.
 */
@Immutable
public class SnappyCompressor implements Compressor {

    private static final String ALGORITHM = "snappy";

    @Override
    public byte[] compress(byte[] payload) {
        try {
            return Snappy.compress(payload);
        } catch (IOException | RuntimeException e) {
            throw CompressionException.compress(ALGORITHM, e);
        } catch (SnappyError e) {
            throw CompressionException.compress(ALGORITHM, e);
        }
    }

    @Override
    public byte[] decompress(byte[] payload) {
        try {
            return Snappy.uncompress(payload);
        } catch (IOException | RuntimeException e) {
            throw CompressionException.decompress(ALGORITHM, e);
        } catch (SnappyError e) {
            throw CompressionException.decompress(ALGORITHM, e);
        }
    }
}
