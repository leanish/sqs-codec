/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.leanish.sqs.codec.SqsCodecInterceptor;
import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import io.github.leanish.sqs.codec.algorithms.encoding.InvalidPayloadException;
import io.github.leanish.sqs.codec.attributes.ChecksumValidationException;
import io.github.leanish.sqs.codec.attributes.CodecAttributes;
import io.github.leanish.sqs.codec.attributes.MessageAttributeUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Tag("integration")
@Testcontainers
class SqsCodecInterceptorIntegrationTest {

    @Container
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.0.2"))
            .withServices("sqs");

    @Test
    void happyCase() {
        String payload = "{\"value\":42}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        try (SqsClient client = sqsClient(
                CompressionAlgorithm.ZSTD,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5)) {
            String queueUrl = createQueue(client);

            client.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(payload)
                    .messageAttributes(Map.of("shopId", MessageAttributeUtils.stringAttribute("shop-1")))
                    .build());

            Message message = receiveSingleMessage(client, queueUrl);

            assertThat(message.body())
                    .isEqualTo(payload);
            assertThat(message.messageAttributes())
                    .containsKeys(
                            CodecAttributes.META,
                            "shopId");

            Map<String, MessageAttributeValue> attributes = message.messageAttributes();
            String expectedChecksum = ChecksumAlgorithm.MD5.implementation().checksum(payloadBytes);
            assertThat(attributes.get(CodecAttributes.META).stringValue())
                    .isEqualTo("v=1;c=zstd;e=base64;h=md5;s=" + expectedChecksum + ";l=12");
        }
    }

    @Test
    void noChecksum() {
        String payload = "payload-no-checksum";

        try (SqsClient client = sqsClient(
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.NONE)) {
            String queueUrl = createQueue(client);

            client.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(payload)
                    .build());

            Message message = receiveSingleMessage(client, queueUrl);

            assertThat(message.body())
                    .isEqualTo(payload);
            assertThat(message.messageAttributes())
                    .containsOnlyKeys(CodecAttributes.META);
            assertThat(message.messageAttributes().get(CodecAttributes.META).stringValue())
                    .isEqualTo("v=1;c=none;e=none;h=none;l=19");
        }
    }

    @Test
    void happyCaseBatchMessages() {
        List<String> payloads = List.of(
                "{\"value\":1}",
                "{\"value\":2}",
                "{\"value\":3}");

        try (SqsClient client = sqsClient(
                CompressionAlgorithm.GZIP,
                EncodingAlgorithm.BASE64_STD,
                ChecksumAlgorithm.SHA256)) {
            String queueUrl = createQueue(client);
            List<SendMessageBatchRequestEntry> entries = new ArrayList<>();
            for (int i = 0; i < payloads.size(); i++) {
                entries.add(SendMessageBatchRequestEntry.builder()
                        .id("msg-" + i)
                        .messageBody(payloads.get(i))
                        .messageAttributes(Map.of(
                                "shopId",
                                MessageAttributeUtils.stringAttribute("shop-" + i)))
                        .build());
            }

            client.sendMessageBatch(SendMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build());

            List<Message> messages = receiveMessages(client, queueUrl, payloads.size());

            assertThat(messages)
                    .hasSize(payloads.size());
            assertThat(messages.stream().map(Message::body).toList())
                    .containsExactlyInAnyOrderElementsOf(payloads);
            for (Message message : messages) {
                Map<String, MessageAttributeValue> attributes = message.messageAttributes();
                byte[] payloadBytes = message.body().getBytes(StandardCharsets.UTF_8);

                assertThat(attributes)
                        .containsKeys(
                                CodecAttributes.META,
                                "shopId");
                assertThat(attributes.get(CodecAttributes.META).stringValue())
                        .isEqualTo(
                                "v=1;c=gzip;e=base64-std;h=sha256;s="
                                        + ChecksumAlgorithm.SHA256.implementation().checksum(payloadBytes)
                                        + ";l="
                                        + payloadBytes.length);
            }
        }
    }

    @Test
    void corruptedPayload() {
        try (SqsClient sender = rawSqsClient();
                SqsClient receiver = sqsClient(
                        CompressionAlgorithm.NONE,
                        EncodingAlgorithm.NONE,
                        ChecksumAlgorithm.MD5)) {
            String queueUrl = createQueue(sender);

            sender.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody("!!")
                    .messageAttributes(Map.of(
                            CodecAttributes.META,
                            MessageAttributeUtils.stringAttribute("v=1;c=none;e=base64;h=none;l=2")))
                    .build());

            assertReceiveThrows(
                    receiver,
                    queueUrl,
                    InvalidPayloadException.class,
                    "Invalid base64 payload");
        }
    }

    @Test
    void checksumMismatch() {
        String payload = "payload-checksum";

        try (SqsClient sender = rawSqsClient();
                SqsClient receiver = sqsClient(
                        CompressionAlgorithm.NONE,
                        EncodingAlgorithm.NONE,
                        ChecksumAlgorithm.MD5)) {
            String queueUrl = createQueue(sender);

            sender.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(payload)
                    .messageAttributes(Map.of(
                            CodecAttributes.META,
                            MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=md5;s=bad;l=16")))
                    .build());

            assertReceiveThrows(
                    receiver,
                    queueUrl,
                    ChecksumValidationException.class,
                    "Payload checksum mismatch");
        }
    }

    private static Message receiveSingleMessage(SqsClient client, String queueUrl) {
        return receiveMessages(client, queueUrl, 1).getFirst();
    }

    private static List<Message> receiveMessages(SqsClient client, String queueUrl, int expectedCount) {
        List<Message> messages = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && messages.size() < expectedCount) {
            ReceiveMessageResponse response = client.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(Math.min(10, expectedCount - messages.size()))
                    .messageAttributeNames("All")
                    .waitTimeSeconds(1)
                    .build());
            if (!response.messages().isEmpty()) {
                messages.addAll(response.messages());
            }
        }
        if (messages.size() < expectedCount) {
            throw new AssertionError("Expected " + expectedCount + " messages from queue " + queueUrl
                    + ", received " + messages.size());
        }
        return messages;
    }

    private static void assertReceiveThrows(
            SqsClient client,
            String queueUrl,
            Class<? extends RuntimeException> expectedType,
            String expectedMessage) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ReceiveMessageResponse response = client.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(1)
                        .messageAttributeNames("All")
                        .waitTimeSeconds(1)
                        .build());
                if (response.messages().isEmpty()) {
                    continue;
                }
                throw new AssertionError("Expected receive to fail for queue " + queueUrl);
            } catch (RuntimeException e) {
                RuntimeException match = findCause(e, expectedType);
                if (match != null && expectedMessage.equals(match.getMessage())) {
                    return;
                }
                throw e;
            }
        }
        throw new AssertionError("Expected receive to fail for queue " + queueUrl);
    }

    private static RuntimeException findCause(
            RuntimeException exception,
            Class<? extends RuntimeException> expectedType) {
        Throwable current = exception;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static SqsClient sqsClient(
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm,
            ChecksumAlgorithm checksumAlgorithm) {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .overrideConfiguration(config -> config.addExecutionInterceptor(
                        SqsCodecInterceptor.defaultInterceptor()
                                .withCompressionAlgorithm(compressionAlgorithm)
                                .withEncodingAlgorithm(encodingAlgorithm)
                                .withChecksumAlgorithm(checksumAlgorithm)
                                // Keep integration expectations deterministic for tiny test payloads.
                                .withPreferSmallerPayloadEnabled(false)))
                .checksumValidationEnabled(false)
                .build();
    }

    private static SqsClient rawSqsClient() {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .checksumValidationEnabled(false)
                .build();
    }

    private static String createQueue(SqsClient client) {
        String queueName = "codec-it-" + UUID.randomUUID();
        return client.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .build())
                .queueUrl();
    }
}
