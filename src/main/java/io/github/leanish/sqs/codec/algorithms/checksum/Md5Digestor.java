/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.checksum;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.google.errorprone.annotations.Immutable;

import io.github.leanish.sqs.codec.algorithms.encoding.Base64PayloadCodec;

/**
 * MD5 digest implementation.
 */
@Immutable
public class Md5Digestor implements Digestor {

    @Override
    public String checksum(byte[] payload) {
        MessageDigest digest = digest();
        byte[] hash = digest.digest(payload);
        return Base64PayloadCodec.instance().encodeToString(hash);
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new UnavailableAlgorithmException("MD5 digest is not available", e);
        }
    }
}
