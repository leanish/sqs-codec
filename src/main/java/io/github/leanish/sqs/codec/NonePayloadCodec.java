/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import java.nio.charset.StandardCharsets;

final class NonePayloadCodec implements PayloadCodec {

    @Override
    public PayloadEncoding encoding() {
        return PayloadEncoding.NONE;
    }

    @Override
    public String encode(String payload) {
        return payload;
    }

    @Override
    public String encode(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }

    @Override
    public String decodeToString(String encoded) {
        return encoded;
    }

    @Override
    public byte[] decodeToBytes(String encoded) {
        return encoded.getBytes(StandardCharsets.UTF_8);
    }
}
