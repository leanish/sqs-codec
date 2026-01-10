/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import org.apache.commons.lang3.StringUtils;

public enum PayloadEncoding {
    ZSTD_BASE64("zstd+base64"),
    BASE64("base64"),
    NONE("none");

    private final String attributeValue;

    PayloadEncoding(String attributeValue) {
        this.attributeValue = attributeValue;
    }

    public String attributeValue() {
        return attributeValue;
    }

    public static PayloadEncoding fromAttributeValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new PayloadCodecException("Unsupported payload encoding: " + value);
        }
        for (PayloadEncoding encoding : values()) {
            if (encoding.attributeValue.equalsIgnoreCase(value)) {
                return encoding;
            }
        }
        throw new PayloadCodecException("Unsupported payload encoding: " + value);
    }
}
