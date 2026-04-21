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
 * Shared JDK {@link MessageDigest}-backed checksum implementation.
 */
@Immutable
class MessageDigestDigestor implements Digestor {

    private final String algorithm;
    private final String unavailableMessage;

    MessageDigestDigestor(String algorithm, String unavailableMessage) {
        this.algorithm = algorithm;
        this.unavailableMessage = unavailableMessage;
    }

    @Override
    public String checksum(byte[] payload) {
        return Base64PayloadCodec.instance().encodeToString(messageDigest().digest(payload));
    }

    private MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new UnavailableAlgorithmException(unavailableMessage, e);
        }
    }
}
