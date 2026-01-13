/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EncodingAlgorithmTest {

    @ParameterizedTest
    @MethodSource("effectiveEncodingCases")
    void effectiveFor_effectiveEncodingCases(
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm,
            EncodingAlgorithm expected) {
        assertThat(EncodingAlgorithm.effectiveFor(compressionAlgorithm, encodingAlgorithm))
                .isEqualTo(expected);
    }

    @Test
    void fromId_blank() {
        String value = " ";
        assertThatThrownBy(() -> EncodingAlgorithm.fromId(value))
                .isInstanceOf(UnsupportedAlgorithmException.class)
                .hasMessage("Unsupported payload encoding: " + value);
    }

    private static Stream<Arguments> effectiveEncodingCases() {
        Stream<Arguments> noCompressionCases = Arrays.stream(EncodingAlgorithm.values())
                .map(encoding -> Arguments.of(
                        CompressionAlgorithm.NONE,
                        encoding,
                        encoding)); // aka: encoding in, encoding out
        Stream<Arguments> compressionYetNoEncodingCases = Arrays.stream(CompressionAlgorithm.values())
                .filter(compression -> compression != CompressionAlgorithm.NONE)
                .map(compression -> Arguments.of(
                        compression,
                        EncodingAlgorithm.NONE,
                        EncodingAlgorithm.BASE64)); // aka: no encoding in, base64 out
        Stream<Arguments> compressionAndEncodingCases = Arrays.stream(CompressionAlgorithm.values())
                .filter(compression -> compression != CompressionAlgorithm.NONE)
                .flatMap(compression -> Arrays.stream(EncodingAlgorithm.values())
                        .filter(encoding -> encoding != EncodingAlgorithm.NONE)
                        .map(encoding -> Arguments.of(
                                compression,
                                encoding,
                                encoding))); // aka: encoding other than
        return Stream.concat(Stream.concat(noCompressionCases, compressionYetNoEncodingCases), compressionAndEncodingCases);
    }
}
