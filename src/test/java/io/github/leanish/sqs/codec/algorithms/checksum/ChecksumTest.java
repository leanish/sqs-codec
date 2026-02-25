/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.checksum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ChecksumTest {

    @Test
    void checksum_happyCase() {
        byte[] payload = "payload-42".getBytes(StandardCharsets.UTF_8);

        Md5Digestor md5 = new Md5Digestor();
        Sha256Digestor sha256 = new Sha256Digestor();

        assertThat(md5.checksum(payload))
                .isEqualTo("t2tngCwK9b7C9eqVQunqfg==");
        assertThat(sha256.checksum(payload))
                .isEqualTo("eQTFzG7BGaPUgIUuq8rJBeIyQhPVNOfDTHjJAxb8udg=");
    }

    @Test
    void checksum_undigested() {
        UndigestedDigestor digestor = new UndigestedDigestor();

        assertThatThrownBy(() -> digestor.checksum("payload".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Digestor algorithm is none");
    }
}
