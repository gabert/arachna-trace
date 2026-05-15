package com.github.gabert.arachna.trace.server;

import com.github.gabert.arachna.trace.codec.AgentRun;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KafkaIntegrationTest {

    private static final String TOPIC = "arachna-trace-records";

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    private static RecordCollectorServer server;

    @BeforeAll
    static void startServer() throws Exception {
        ServerConfig config = ServerConfig.load(new String[]{
                "server_port=0",
                "kafka_bootstrap_servers=" + kafka.getBootstrapServers(),
                "kafka_topic=" + TOPIC
        });
        server = new RecordCollectorServer(config);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void payloadArrivesInKafkaBytePerfect() throws Exception {
        byte[] payload = new byte[32];
        new Random().nextBytes(payload);

        try (KafkaConsumer<String, byte[]> consumer = createConsumer()) {
            int httpStatus = postRecords(payload, Map.of());
            assertEquals(200, httpStatus, "POST /records should return 200");

            ConsumerRecord<String, byte[]> received = pollOneRecord(consumer);
            assertNotNull(received, "Should receive a message from Kafka");
            assertArrayEquals(payload, received.value(),
                    "Kafka message should match sent payload byte-for-byte");
        }
    }

    @Test
    void agentRunHeadersArriveOnKafkaRecord() throws Exception {
        byte[] payload = new byte[]{42};

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AgentRun.Headers.AGENT_RUN_ID,  "11111111-2222-3333-4444-555555555555");
        headers.put(AgentRun.Headers.HOSTNAME,      "host-it");
        headers.put(AgentRun.Headers.AGENT_VERSION, "1.2.3");
        headers.put(AgentRun.Headers.CODE_VERSION,  "abc123");
        headers.put(AgentRun.Headers.ENV,           "prod");
        headers.put(AgentRun.Headers.PROCESS_PID,   "4242");
        headers.put(AgentRun.Headers.STARTED_AT_MS, "9000000");

        try (KafkaConsumer<String, byte[]> consumer = createConsumer()) {
            int httpStatus = postRecords(payload, headers);
            assertEquals(200, httpStatus, "POST /records should return 200");

            ConsumerRecord<String, byte[]> received = pollOneRecord(consumer);
            assertNotNull(received, "Should receive a message from Kafka");

            for (Map.Entry<String, String> e : headers.entrySet()) {
                Header h = received.headers().lastHeader(e.getKey());
                assertNotNull(h, "Kafka record missing agent-run header " + e.getKey());
                assertEquals(e.getValue(), new String(h.value(), StandardCharsets.UTF_8),
                        "Kafka header value mismatch for " + e.getKey());
            }
        }
    }

    private int postRecords(byte[] payload, Map<String, String> headers) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/records"))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private KafkaConsumer<String, byte[]> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(TOPIC));
        consumer.poll(Duration.ofSeconds(5));
        return consumer;
    }

    private ConsumerRecord<String, byte[]> pollOneRecord(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> record : records) {
                return record;
            }
        }
        return null;
    }
}
