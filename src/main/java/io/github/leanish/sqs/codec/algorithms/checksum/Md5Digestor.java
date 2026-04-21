/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.checksum;

/**
 * MD5 digest implementation.
 */
public class Md5Digestor extends MessageDigestDigestor {

    public Md5Digestor() {
        super("MD5", "MD5 digest is not available");
    }
}
