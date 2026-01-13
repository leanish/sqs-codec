/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.checksum;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * MD5 digest implementation.
 */
public class Md5Digestor implements Digestor {

    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder();

    @Override
    public String checksum(byte[] payload) {
        MessageDigest digest = digest();
        byte[] hash = digest.digest(payload);
        return BASE64_ENCODER.encodeToString(hash);
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new UnavailableAlgorithmException("MD5 digest is not available", e);
        }
    }
}
