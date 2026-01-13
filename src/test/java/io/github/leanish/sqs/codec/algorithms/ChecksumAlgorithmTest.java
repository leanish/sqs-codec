/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChecksumAlgorithmTest {

    @ParameterizedTest
    @MethodSource("checksumIdCases")
    void fromId_happyCase(String value, ChecksumAlgorithm expected) {
        assertThat(ChecksumAlgorithm.fromId(value))
                .isEqualTo(expected);
    }

    @Test
    void fromId_unknown() {
        assertThatThrownBy(() -> ChecksumAlgorithm.fromId("sha1"))
                .isInstanceOf(UnsupportedAlgorithmException.class)
                .hasMessage("Unsupported checksum algorithm: sha1");
    }

    @Test
    void fromId_blank() {
        assertThatThrownBy(() -> ChecksumAlgorithm.fromId("  "))
                .isInstanceOf(UnsupportedAlgorithmException.class)
                .hasMessage("Unsupported checksum algorithm:   ");
    }

    private static Stream<Arguments> checksumIdCases() {
        return Stream.of(
                Arguments.of("MD5", ChecksumAlgorithm.MD5),
                Arguments.of("sha256", ChecksumAlgorithm.SHA256),
                Arguments.of("None", ChecksumAlgorithm.NONE));
    }
}
