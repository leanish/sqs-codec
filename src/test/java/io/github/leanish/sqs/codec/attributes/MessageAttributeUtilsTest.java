/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class MessageAttributeUtilsTest {

    @Test
    void attributeValue_returnsNullWhenAttributeIsMissing() {
        assertThat(MessageAttributeUtils.attributeValue(Map.of(), "missing"))
                .isNull();
    }
}
