/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

/**
 * Constants for codec attribute names and the current codec version.
 */
public class PayloadCodecAttributes {

    public static final String CHECKSUM = "x-codec-checksum";
    public static final String CONF = "x-codec-conf";
    public static final String RAW_LENGTH = "x-codec-raw-length";

    public static final int VERSION_VALUE = 1;

    private PayloadCodecAttributes() {
    }

}
