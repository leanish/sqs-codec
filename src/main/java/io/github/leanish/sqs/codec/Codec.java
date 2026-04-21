/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import org.jspecify.annotations.Nullable;

import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionLevel;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import io.github.leanish.sqs.codec.algorithms.compression.Compressor;
import io.github.leanish.sqs.codec.algorithms.encoding.PayloadCodec;

class Codec {

    private final Compressor compressor;
    private final PayloadCodec payloadCodec;

    Codec(CompressionAlgorithm compressionAlgorithm) {
        this(compressionAlgorithm, null, EncodingAlgorithm.NONE);
    }

    Codec(CompressionAlgorithm compressionAlgorithm, @Nullable CompressionLevel compressionLevel) {
        this(compressionAlgorithm, compressionLevel, EncodingAlgorithm.NONE);
    }

    Codec(
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm) {
        this(compressionAlgorithm, null, encodingAlgorithm);
    }

    Codec(
            CompressionAlgorithm compressionAlgorithm,
            @Nullable CompressionLevel compressionLevel,
            EncodingAlgorithm encodingAlgorithm) {
        EncodingAlgorithm effectiveEncoding = EncodingAlgorithm.effectiveFor(compressionAlgorithm, encodingAlgorithm);
        this.compressor = compressionLevel == null
                ? compressionAlgorithm.implementation()
                : compressionAlgorithm.implementation(compressionLevel);
        this.payloadCodec = effectiveEncoding.implementation();
    }

    public byte[] encode(byte[] payload) {
        return payloadCodec.encode(compressor.compress(payload));
    }

    public byte[] decode(byte[] payload) {
        return compressor.decompress(payloadCodec.decode(payload));
    }
}
