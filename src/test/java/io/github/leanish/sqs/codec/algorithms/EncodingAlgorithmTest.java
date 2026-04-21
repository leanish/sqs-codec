/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EncodingAlgorithmTest {

    @Test
    void effectiveFor_usesBase64WhenCompressedAndEncodingIsNone() {
        assertThat(EncodingAlgorithm.effectiveFor(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE))
                .isEqualTo(EncodingAlgorithm.BASE64);
        assertThat(EncodingAlgorithm.effectiveFor(CompressionAlgorithm.SNAPPY, EncodingAlgorithm.NONE))
                .isEqualTo(EncodingAlgorithm.BASE64);
        assertThat(EncodingAlgorithm.effectiveFor(CompressionAlgorithm.GZIP, EncodingAlgorithm.ASCII85))
                .isEqualTo(EncodingAlgorithm.ASCII85);
        assertThat(EncodingAlgorithm.effectiveFor(CompressionAlgorithm.GZIP, EncodingAlgorithm.BASE64))
                .isEqualTo(EncodingAlgorithm.BASE64);
        assertThat(EncodingAlgorithm.effectiveFor(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE))
                .isEqualTo(EncodingAlgorithm.NONE);
    }

    @Test
    void fromId_caseInsensitive() {
        assertThat(EncodingAlgorithm.fromId("BASE64"))
                .isEqualTo(EncodingAlgorithm.BASE64);
        assertThat(EncodingAlgorithm.fromId("ASCII85"))
                .isEqualTo(EncodingAlgorithm.ASCII85);
    }

    @Test
    void fromId_unsupported() {
        assertThatThrownBy(() -> EncodingAlgorithm.fromId("base85"))
                .isInstanceOf(UnsupportedAlgorithmException.class)
                .hasMessage("Unsupported payload encoding: base85");
    }
}
