/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import io.github.leanish.sqs.codec.algorithms.compression.Compressor;
import io.github.leanish.sqs.codec.algorithms.encoding.Encoder;

class PayloadCodec {

    private final Compressor compressor;
    private final Encoder encoder;

    PayloadCodec() {
        this(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE);
    }

    PayloadCodec(
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encoding) {
        EncodingAlgorithm effectiveEncoding = EncodingAlgorithm.effectiveFor(compressionAlgorithm, encoding);
        this.compressor = compressionAlgorithm.compressor();
        this.encoder = effectiveEncoding.encoder();
    }

    public byte[] encode(byte[] payload) {
        return encoder.encode(compressor.compress(payload));
    }

    public byte[] decode(byte[] encoded) {
        return compressor.decompress(encoder.decode(encoded));
    }
}
