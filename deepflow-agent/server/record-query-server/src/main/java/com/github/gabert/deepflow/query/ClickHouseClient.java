package com.github.gabert.deepflow.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the ClickHouse HTTP interface for read-only queries.
 * Returns rows as a list of maps (one per row), shape-preserving.
 *
 * <p>This is a query <em>gateway</em> for the UI, not a generic JDBC layer.
 * SQL stays in {@link QueryHandler}; this class just executes the strings
 * it gets and parses {@code JSONEachRow} responses.</p>
 */
public class ClickHouseClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI baseUri;
    private final String basicAuth;
    private final HttpClient http;

    public ClickHouseClient(QueryServerConfig config) {
        this.baseUri = URI.create(config.getClickhouseUrl());
        this.basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (config.getClickhouseUser() + ":" + config.getClickhousePassword())
                        .getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Run a SELECT and return rows as JSON objects. The query is appended with
     * {@code FORMAT JSONEachRow} automatically.
     */
    public List<Map<String, Object>> query(String sql) throws IOException, InterruptedException {
        String body = sql + "\nFORMAT JSONEachRow";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(baseUri.resolve("/?database=" + URLEncoder.encode(
                        "deepflow", StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", basicAuth)
                .header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("ClickHouse query failed: HTTP " + response.statusCode()
                    + " — " + response.body());
        }
        return parseJsonEachRow(response.body());
    }

    private static List<Map<String, Object>> parseJsonEachRow(String body) throws IOException {
        java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (String line : body.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            rows.add(MAPPER.convertValue(node, Map.class));
        }
        return rows;
    }
}
