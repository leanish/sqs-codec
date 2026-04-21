/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import io.github.leanish.sqs.codec.algorithms.compression.Compressor;
import io.github.leanish.sqs.codec.algorithms.encoding.PayloadCodec;

class Codec {

    private final Compressor compressor;
    private final PayloadCodec payloadCodec;

    Codec(CompressionAlgorithm compressionAlgorithm) {
        this(compressionAlgorithm, EncodingAlgorithm.NONE);
    }

    Codec(
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm) {
        EncodingAlgorithm effectiveEncoding = EncodingAlgorithm.effectiveFor(compressionAlgorithm, encodingAlgorithm);
        this.compressor = compressionAlgorithm.implementation();
        this.payloadCodec = effectiveEncoding.implementation();
    }

    public byte[] encode(byte[] payload) {
        return payloadCodec.encode(compressor.compress(payload));
    }

    public byte[] decode(byte[] payload) {
        return compressor.decompress(payloadCodec.decode(payload));
    }
}
