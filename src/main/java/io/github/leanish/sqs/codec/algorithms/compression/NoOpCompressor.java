/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

/**
 * No-op compressor that passes payload bytes through unchanged.
 */
public class NoOpCompressor implements Compressor {

    @Override
    public byte[] compress(byte[] payload) {
        return payload;
    }

    @Override
    public byte[] decompress(byte[] payload) {
        return payload;
    }
}
