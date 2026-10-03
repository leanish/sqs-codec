/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.encoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class NoOpPayloadCodecTest {

    @Test
    void noOp_passesPayloadThroughUnchanged() {
        NoOpPayloadCodec noOpCodec = NoOpPayloadCodec.instance();
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

        assertThat(noOpCodec.encode(payload))
                .isSameAs(payload);
        assertThat(noOpCodec.decode(payload))
                .isSameAs(payload);
    }
}
