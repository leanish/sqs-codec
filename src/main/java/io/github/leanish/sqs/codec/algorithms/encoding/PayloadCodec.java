/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.encoding;

import com.google.errorprone.annotations.Immutable;

/**
 * Strategy interface for encoding and decoding payload bytes.
 */
@Immutable
public interface PayloadCodec {
    byte[] encode(byte[] payload);

    byte[] decode(byte[] encoded);
}
