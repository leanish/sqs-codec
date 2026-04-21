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

import org.junit.jupiter.api.Test;

class AlgorithmIdsTest {

    @Test
    void compressionAlgorithmIdsAreUnique() {
        assertThat(uniqueCompressionIds())
                .hasSize(CompressionAlgorithm.values().length);
    }

    @Test
    void encodingAlgorithmIdsAreUnique() {
        assertThat(uniqueEncodingIds())
                .hasSize(EncodingAlgorithm.values().length);
    }

    @Test
    void checksumAlgorithmIdsAreUnique() {
        assertThat(uniqueChecksumIds())
                .hasSize(ChecksumAlgorithm.values().length);
    }

    private static Set<String> uniqueCompressionIds() {
        return Arrays.stream(CompressionAlgorithm.values())
                .map(CompressionAlgorithm::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> uniqueEncodingIds() {
        return Arrays.stream(EncodingAlgorithm.values())
                .map(EncodingAlgorithm::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> uniqueChecksumIds() {
        return Arrays.stream(ChecksumAlgorithm.values())
                .map(ChecksumAlgorithm::id)
                .collect(Collectors.toUnmodifiableSet());
    }
}
