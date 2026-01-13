/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CompressionAlgorithmTest {

    @Test
    void fromId_blank() {
        String value = " ";
        assertThatThrownBy(() -> CompressionAlgorithm.fromId(value))
                .isInstanceOf(UnsupportedAlgorithmException.class)
                .hasMessage("Unsupported payload compression: " + value);
    }
}
