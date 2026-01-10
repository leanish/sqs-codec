/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

public final class PayloadCodecs {

    private static final PayloadCodec ZSTD_BASE64 = new ZstdBase64PayloadCodec();
    private static final PayloadCodec BASE64 = new Base64PayloadCodec();
    private static final PayloadCodec NONE = new NonePayloadCodec();

    private PayloadCodecs() {
    }

    public static PayloadCodec forEncoding(PayloadEncoding encoding) {
        return switch (encoding) {
            case ZSTD_BASE64 -> ZSTD_BASE64;
            case BASE64 -> BASE64;
            case NONE -> NONE;
        };
    }
}
