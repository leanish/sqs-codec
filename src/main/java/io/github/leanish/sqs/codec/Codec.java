/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import org.jspecify.annotations.Nullable;

import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionLevel;
import io.github.leanish.sqs.codec.algorithms.compression.Compressor;
import io.github.leanish.sqs.codec.algorithms.encoding.Base64Codec;

class Codec {

    private static final Base64Codec BASE64_CODEC = Base64Codec.instance();

    private final Compressor compressor;
    private final boolean compressionEnabled;

    Codec(CompressionAlgorithm compressionAlgorithm) {
        this(compressionAlgorithm, null);
    }

    Codec(CompressionAlgorithm compressionAlgorithm, @Nullable CompressionLevel compressionLevel) {
        this.compressor = compressionLevel == null
                ? compressionAlgorithm.implementation()
                : compressionAlgorithm.implementation(compressionLevel);
        this.compressionEnabled = compressionAlgorithm != CompressionAlgorithm.NONE;
    }

    public byte[] encode(byte[] payload) {
        if (!compressionEnabled) {
            return payload;
        }

        byte[] compressed = compressor.compress(payload);
        return BASE64_CODEC.encode(compressed);
    }

    public byte[] decode(byte[] payload) {
        if (!compressionEnabled) {
            return payload;
        }

        byte[] decoded = BASE64_CODEC.decode(payload);
        return compressor.decompress(decoded);
    }
}
