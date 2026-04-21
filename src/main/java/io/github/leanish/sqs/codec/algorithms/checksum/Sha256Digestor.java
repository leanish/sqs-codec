/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.checksum;

/**
 * SHA-256 digest implementation.
 */
public class Sha256Digestor extends MessageDigestDigestor {

    public Sha256Digestor() {
        super("SHA-256", "SHA-256 digest is not available");
    }
}
