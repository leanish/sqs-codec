/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.checksum;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.google.errorprone.annotations.Immutable;

import io.github.leanish.sqs.codec.algorithms.encoding.Base64Codec;

/**
 * SHA-256 digest implementation.
 */
@Immutable
public class Sha256Digestor implements Digestor {

    @Override
    public String checksum(byte[] payload) {
        MessageDigest digest = digest();
        byte[] hash = digest.digest(payload);
        return Base64Codec.instance().encodeToString(hash);
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new UnavailableAlgorithmException("SHA-256 digest is not available", e);
        }
    }
}
