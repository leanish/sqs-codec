/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class Base64PayloadCodec implements PayloadCodec {

    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    @Override
    public PayloadEncoding encoding() {
        return PayloadEncoding.BASE64;
    }

    @Override
    public String encode(String payload) {
        return encode(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String encode(byte[] payload) {
        return BASE64_ENCODER.encodeToString(payload);
    }

    @Override
    public String decodeToString(String encoded) {
        return new String(decodeToBytes(encoded), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] decodeToBytes(String encoded) {
        try {
            return BASE64_DECODER.decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new PayloadCodecException("Invalid base64 payload", e);
        }
    }
}
