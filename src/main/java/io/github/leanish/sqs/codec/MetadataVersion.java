/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import io.github.leanish.sqs.codec.attributes.CodecAttributes;

/**
 * The codec metadata version ({@code v}) the interceptor writes on send. Receiving reads every supported version,
 * whichever is written.
 */
public enum MetadataVersion {

    /**
     * The 0.5.x version, which 0.5.x consumers read: written while upgrading from 0.5.x, so clients that both send and
     * receive can be upgraded in any order. The payload, checksum and raw length ({@code l}) are the same as with
     * {@link #V2}.
     *
     * @deprecated removed in 0.7.0, together with reading {@code v=1}; switch to {@link #V2} once every consumer runs
     *     0.6.x.
     */
    @Deprecated(since = "0.6.0")
    V1(CodecAttributes.LEGACY_VERSION_VALUE),

    /** The current version, the default: 0.5.x consumers refuse it. */
    V2(CodecAttributes.VERSION_VALUE);

    private final int value;

    MetadataVersion(int value) {
        this.value = value;
    }

    /** The version as written in {@code x-codec-meta}. */
    public int value() {
        return value;
    }
}
