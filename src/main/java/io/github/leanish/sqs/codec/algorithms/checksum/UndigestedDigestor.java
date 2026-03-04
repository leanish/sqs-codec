/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.checksum;

import com.google.errorprone.annotations.Immutable;

/**
 * Digestor implementation that always throws for NONE.
 */
@Immutable
public class UndigestedDigestor implements Digestor {

    @Override
    public String checksum(byte[] payload) {
        throw new IllegalStateException("Digestor algorithm is none");
    }
}
