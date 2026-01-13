/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.xerial.snappy.Snappy;

/**
 * Snappy implementation of the compressor strategy.
 */
public class SnappyCompressor implements Compressor {

    @Override
    public byte[] compress(byte[] payload) {
        try {
            return Snappy.compress(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] decompress(byte[] payload) {
        try {
            return Snappy.uncompress(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
