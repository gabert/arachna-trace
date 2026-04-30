package com.github.gabert.deepflow.server;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

public class KafkaRecordForwarder {
    private final KafkaProducer<String, byte[]> producer;
    private final String topic;

    public KafkaRecordForwarder(ServerConfig config) {
        this.topic = config.getKafkaTopic();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);

        this.producer = new KafkaProducer<>(props);
    }

    /**
     * Forward one HTTP-batch payload to Kafka. {@code agentHeaders} carries the
     * agent-run identity ({@link com.github.gabert.deepflow.recorder.AgentRun.Headers})
     * lifted from the HTTP request, copied verbatim onto the Kafka record so the
     * processor can attribute the body without parsing it.
     */
    public void send(byte[] rawRecords, Map<String, String> agentHeaders) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, rawRecords);
        Headers headers = record.headers();
        for (Map.Entry<String, String> e : agentHeaders.entrySet()) {
            headers.add(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                System.err.println("[DeepFlow] Kafka send failed: " + exception.getMessage());
            }
        });
    }

    public void close() {
        producer.flush();
        producer.close();
    }
}
