/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.testsupport;

public final class TestPayloads {

    private TestPayloads() {
    }

    public static String levelSensitivePayload() {
        StringBuilder payload = new StringBuilder();
        for (int index = 0; index < 2_048; index++) {
            payload.append("{\"shop\":\"shop-")
                    .append(index % 17)
                    .append("\",\"product\":\"sku-")
                    .append(index % 97)
                    .append("\",\"description\":\"")
                    .append("abcdefghij".repeat(6))
                    .append(index)
                    .append("\"}\n");
        }
        return payload.toString();
    }
}
