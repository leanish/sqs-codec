/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.checksum;

/**
 * Digestor implementation that always throws for NONE.
 */
public class UndigestedDigestor implements Digestor {

    @Override
    public String checksum(byte[] payload) {
        throw new UnavailableAlgorithmException("Digestor algorithm is none");
    }
}
