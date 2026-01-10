/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PayloadEncodingTest {

    @Test
    void fromAttributeValueRejectsBlank() {
        assertThatThrownBy(() -> PayloadEncoding.fromAttributeValue(""))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Unsupported payload encoding: ");
    }

    @Test
    void fromAttributeValueRejectsNull() {
        //noinspection DataFlowIssue // public class, public methods, this can happen
        assertThatThrownBy(() -> PayloadEncoding.fromAttributeValue(null))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Unsupported payload encoding: null");
    }
}
