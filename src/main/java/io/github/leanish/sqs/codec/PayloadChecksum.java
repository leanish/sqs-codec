/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

final class PayloadChecksum {

    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder();

    private PayloadChecksum() {
    }

    static String sha256Base64(byte[] payload) {
        MessageDigest digest = sha256Digest();
        byte[] hash = digest.digest(payload);
        return BASE64_ENCODER.encodeToString(hash);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new PayloadCodecException("SHA-256 digest is not available", e);
        }
    }
}
