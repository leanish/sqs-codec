/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

public interface PayloadCodec {

    PayloadEncoding encoding();

    String encode(String payload);

    String encode(byte[] payload);

    String decodeToString(String encoded);

    byte[] decodeToBytes(String encoded);
}
