/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.encoding;

import com.google.errorprone.annotations.Immutable;

/**
 * No-op payload codec that passes bytes through unchanged.
 */
@Immutable
public final class NoOpPayloadCodec implements PayloadCodec {

    private static final NoOpPayloadCodec INSTANCE = new NoOpPayloadCodec();

    private NoOpPayloadCodec() {
    }

    public static NoOpPayloadCodec instance() {
        return INSTANCE;
    }

    @Override
    public byte[] encode(byte[] payload) {
        return payload;
    }

    @Override
    public byte[] decode(byte[] encoded) {
        return encoded;
    }
}
