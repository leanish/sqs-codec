/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

public final class PayloadCodecAttributes {

    public static final String ENCODING = "x-codec-encoding";
    public static final String CHECKSUM_ALG = "x-codec-checksum-alg";
    public static final String CHECKSUM = "x-codec-checksum";
    public static final String VERSION = "x-codec-version";
    public static final String RAW_LENGTH = "x-codec-raw-length";

    public static final String CHECKSUM_SHA256 = "sha256";
    public static final String VERSION_VALUE = "1.0.0";

    private PayloadCodecAttributes() {
    }
}
