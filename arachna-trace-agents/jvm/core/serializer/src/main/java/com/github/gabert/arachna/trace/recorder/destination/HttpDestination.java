package com.github.gabert.arachna.trace.recorder.destination;

import com.github.gabert.arachna.trace.recorder.AgentRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpDestination implements Destination {
    private static final int DEFAULT_FLUSH_THRESHOLD = 64 * 1024;
    private static final String DEFAULT_SERVER_URL = "http://localhost:8099/records";

    private final HttpClient httpClient;
    private final URI serverUri;
    private final int flushThreshold;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile AgentRun agentRun;

    public HttpDestination(Map<String, String> config) {
        String url = config.getOrDefault("http_server_url", DEFAULT_SERVER_URL);
        this.serverUri = URI.create(url);
        this.flushThreshold = Integer.parseInt(
                config.getOrDefault("http_flush_threshold", String.valueOf(DEFAULT_FLUSH_THRESHOLD)));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void setAgentRun(AgentRun agentRun) {
        this.agentRun = agentRun;
    }

    @Override
    public void accept(byte[] record) {
        buffer.writeBytes(record);
        if (buffer.size() >= flushThreshold) {
            sendBuffer();
        }
    }

    @Override
    public void flush() throws IOException {
        sendBuffer();
    }

    @Override
    public void close() throws IOException {
        sendBuffer();
    }

    private void sendBuffer() {
        if (buffer.size() == 0) return;

        byte[] payload = buffer.toByteArray();
        buffer.reset();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(serverUri)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .timeout(Duration.ofSeconds(10));

        applyAgentRunHeaders(builder);

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[ArachnaTrace] HTTP destination error: " + response.statusCode()
                        + " — " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[ArachnaTrace] HTTP destination send failed: " + e.getMessage());
        }
    }

    private void applyAgentRunHeaders(HttpRequest.Builder builder) {
        AgentRun run = this.agentRun;
        if (run == null) return;

        builder.header(AgentRun.Headers.AGENT_RUN_ID,  run.agentRunId().toString());
        builder.header(AgentRun.Headers.HOSTNAME,      run.hostname());
        builder.header(AgentRun.Headers.AGENT_VERSION, run.agentVersion());
        if (run.codeVersion() != null) builder.header(AgentRun.Headers.CODE_VERSION, run.codeVersion());
        if (run.env() != null)         builder.header(AgentRun.Headers.ENV,          run.env());
        builder.header(AgentRun.Headers.JVM_PID,       Long.toString(run.jvmPid()));
        builder.header(AgentRun.Headers.STARTED_AT_MS, Long.toString(run.startedAtMillis()));
    }
}
