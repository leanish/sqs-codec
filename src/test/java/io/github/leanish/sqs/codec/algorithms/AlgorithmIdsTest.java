/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AlgorithmIdsTest {

    @ParameterizedTest(name = "{0} ids are unique")
    @MethodSource("algorithmValues")
    void algorithmIdsAreUnique(String name, Enum<?>[] values) {
        assertThat(uniqueIds(values))
                .hasSize(values.length);
    }

    private static Set<String> uniqueIds(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(value -> {
                    if (value instanceof CompressionAlgorithm algorithm) {
                        return algorithm.id();
                    }
                    if (value instanceof EncodingAlgorithm algorithm) {
                        return algorithm.id();
                    }
                    if (value instanceof ChecksumAlgorithm algorithm) {
                        return algorithm.id();
                    }
                    throw new IllegalStateException("Unsupported algorithm type: " + value.getClass());
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Stream<Arguments> algorithmValues() {
        return Stream.of(
                Arguments.of("CompressionAlgorithm", CompressionAlgorithm.values()),
                Arguments.of("EncodingAlgorithm", EncodingAlgorithm.values()),
                Arguments.of("ChecksumAlgorithm", ChecksumAlgorithm.values()));
    }
}
