/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.encoding;

import java.util.Base64;

import com.google.errorprone.annotations.Immutable;

/**
 * Unpadded URL-safe Base64 codec.
 */
@Immutable
public class Base64Codec {

    private static final Base64Codec INSTANCE = new Base64Codec();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder()
            .withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Base64Codec() {
    }

    public static Base64Codec instance() {
        return INSTANCE;
    }

    public byte[] encode(byte[] payload) {
        return ENCODER.encode(payload);
    }

    public String encodeToString(byte[] payload) {
        return ENCODER.encodeToString(payload);
    }

    public byte[] decode(byte[] encoded) {
        try {
            return DECODER.decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new InvalidPayloadException("Invalid base64 payload", e);
        }
    }
}
