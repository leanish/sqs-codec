/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

/**
 * Constants for codec attribute names and the current codec version.
 */
public class CodecAttributes {

    /** Single codec metadata attribute containing version/compression/encoding/checksum/raw-length metadata. */
    public static final String META = "x-codec-meta";
    /** Metadata key for codec version. */
    public static final String META_VERSION_KEY = "v";
    /** Metadata key for compression algorithm id. */
    public static final String META_COMPRESSION_KEY = "c";
    /** Metadata key for encoding algorithm id. */
    public static final String META_ENCODING_KEY = "e";
    /** Metadata key for checksum algorithm id. */
    public static final String META_CHECKSUM_ALGORITHM_KEY = "h";
    /** Metadata key for raw payload length (before compression/encoding). */
    public static final String META_RAW_LENGTH_KEY = "l";
    /** Metadata key for checksum value. */
    public static final String META_CHECKSUM_VALUE_KEY = "s";
    public static final int VERSION_VALUE = 1;

    private CodecAttributes() {
    }

}
