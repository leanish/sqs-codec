/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.leanish.sqs.codec.algorithms.encoding.Ascii85PayloadCodec;
import io.github.leanish.sqs.codec.algorithms.encoding.Base64PayloadCodec;
import io.github.leanish.sqs.codec.algorithms.encoding.NoOpPayloadCodec;
import io.github.leanish.sqs.codec.algorithms.encoding.PayloadCodec;

/**
 * Supported payload encodings and their payload codec implementations.
 */
public enum EncodingAlgorithm {
    /** URL-safe unpadded Base64 for binary payload transport. */
    BASE64("base64", Base64PayloadCodec.instance()),
    /**
     * Strict canonical ASCII85 without framing or shorthand forms.
     *
     * <p><b>Experimental:</b> This encoding is still experimental and may change before a stable
     * release.
     */
    ASCII85("ascii85", Ascii85PayloadCodec.instance()),
    /** No payload encoding; message bodies are treated as UTF-8 text. */
    NONE("none", NoOpPayloadCodec.instance());

    private static final Map<String, EncodingAlgorithm> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    algorithm -> algorithm.id.toLowerCase(Locale.ROOT),
                    algorithm -> algorithm));

    private final String id;
    private final PayloadCodec payloadCodec;

    EncodingAlgorithm(String id, PayloadCodec payloadCodec) {
        this.id = id;
        this.payloadCodec = payloadCodec;
    }

    public String id() {
        return id;
    }

    public PayloadCodec implementation() {
        return payloadCodec;
    }

    public static EncodingAlgorithm fromId(String value) {
        if (value.isBlank()) {
            throw UnsupportedAlgorithmException.encoding(value);
        }
        EncodingAlgorithm encoding = BY_ID.get(value.toLowerCase(Locale.ROOT));
        if (encoding == null) {
            throw UnsupportedAlgorithmException.encoding(value);
        }
        return encoding;
    }

    public static EncodingAlgorithm effectiveFor(
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm) {
        if (compressionAlgorithm != CompressionAlgorithm.NONE
                && encodingAlgorithm == EncodingAlgorithm.NONE) {
            return EncodingAlgorithm.BASE64;
        }
        return encodingAlgorithm;
    }
}
