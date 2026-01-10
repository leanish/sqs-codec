/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PayloadCodecTest {

    private final PayloadCodec codec = PayloadCodecs.forEncoding(PayloadEncoding.ZSTD_BASE64);

    @Test
    void roundTripPreservesPayload() {
        String payload = "{\"value\":42}";
        String encoded = codec.encode(payload);

        String decoded = codec.decodeToString(encoded);

        assertThat(decoded).isEqualTo(payload);
    }

    @Test
    void roundTripPreservesPayloadWithoutCompression() {
        PayloadCodec base64Codec = PayloadCodecs.forEncoding(PayloadEncoding.BASE64);
        String payload = "{\"value\":42}";
        String encoded = base64Codec.encode(payload);

        String decoded = base64Codec.decodeToString(encoded);

        assertThat(decoded).isEqualTo(payload);
    }

    @Test
    void roundTripPreservesPayloadWithoutEncoding() {
        PayloadCodec noneCodec = PayloadCodecs.forEncoding(PayloadEncoding.NONE);
        String payload = "{\"value\":42}";
        String encoded = noneCodec.encode(payload);

        String decoded = noneCodec.decodeToString(encoded);

        assertThat(decoded).isEqualTo(payload);
    }

    @Test
    void encodeBytesRoundTripsWithDefaultEncoding() {
        byte[] payload = "payload-42".getBytes(StandardCharsets.UTF_8);
        String encoded = codec.encode(payload);

        byte[] decoded = codec.decodeToBytes(encoded);

        assertThat(decoded).isEqualTo(payload);
    }

    @Test
    void decodeToStringUsesUtf8ForEncodedBytes() {
        byte[] payload = "payload-42".getBytes(StandardCharsets.UTF_8);
        String encoded = codec.encode(payload);

        String decoded = codec.decodeToString(encoded);

        assertThat(decoded).isEqualTo("payload-42");
    }

    @Test
    void decodeBytesPassesThroughForPlaintext() {
        PayloadCodec noneCodec = PayloadCodecs.forEncoding(PayloadEncoding.NONE);
        byte[] payload = "payload-42".getBytes(StandardCharsets.UTF_8);
        String encoded = noneCodec.encode(payload);

        byte[] decoded = noneCodec.decodeToBytes(encoded);

        assertThat(decoded).isEqualTo(payload);
    }

    @Test
    void payloadCodecExceptionStoresCause() {
        Throwable cause = new IllegalStateException("cause");

        PayloadCodecException exception = new PayloadCodecException("message", cause);

        assertThat(exception.getMessage()).isEqualTo("message");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void decodeToBytesWrapsInvalidBase64() {
        PayloadCodec base64Codec = PayloadCodecs.forEncoding(PayloadEncoding.BASE64);

        assertThatThrownBy(() -> base64Codec.decodeToBytes("!!!"))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Invalid base64 payload")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void base64CodecReportsEncoding() {
        PayloadCodec base64Codec = PayloadCodecs.forEncoding(PayloadEncoding.BASE64);

        assertThat(base64Codec.encoding()).isEqualTo(PayloadEncoding.BASE64);
    }
}
