/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.encoding;

import java.util.Base64;

/**
 * Standard Base64 encoder implementation.
 */
public class StandardBase64Encoder implements Encoder {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    @Override
    public byte[] encode(byte[] payload) {
        return ENCODER.encode(payload);
    }

    @Override
    public byte[] decode(byte[] encoded) {
        try {
            return DECODER.decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new InvalidPayloadException("Invalid base64 payload", e);
        }
    }
}
