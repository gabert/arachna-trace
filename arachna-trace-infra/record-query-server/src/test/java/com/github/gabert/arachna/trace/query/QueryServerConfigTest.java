package com.github.gabert.arachna.trace.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryServerConfigTest {

    @Test
    void defaultValues() throws IOException {
        QueryServerConfig config = QueryServerConfig.load(new String[]{});

        assertEquals(8082, config.getServerPort());
        assertEquals("http://localhost:8123", config.getClickhouseUrl());
        assertEquals("arachna_trace", config.getClickhouseDatabase());
        assertEquals("arachna_trace", config.getClickhouseUser());
        assertEquals("arachna_trace", config.getClickhousePassword());
        assertEquals("http://localhost:5173", config.getCorsOrigin());
    }

    @Test
    void cliOverridesDefaults() throws IOException {
        QueryServerConfig config = QueryServerConfig.load(new String[]{
                "server_port=9090",
                "clickhouse_url=http://ch:9000",
                "clickhouse_database=other_db",
                "clickhouse_user=u",
                "clickhouse_password=p",
                "cors_origin=https://ui.example.com"
        });

        assertEquals(9090, config.getServerPort());
        assertEquals("http://ch:9000", config.getClickhouseUrl());
        assertEquals("other_db", config.getClickhouseDatabase());
        assertEquals("u", config.getClickhouseUser());
        assertEquals("p", config.getClickhousePassword());
        assertEquals("https://ui.example.com", config.getCorsOrigin());
    }

    @Test
    void loadFromConfigFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("query.cfg");
        Files.writeString(configFile, """
                # Query server config
                server_port=7070
                clickhouse_url=http://ch-prod:8123
                cors_origin=https://prod.example.com
                """);

        QueryServerConfig config = QueryServerConfig.load(new String[]{
                "config=" + configFile.toAbsolutePath()
        });

        assertEquals(7070, config.getServerPort());
        assertEquals("http://ch-prod:8123", config.getClickhouseUrl());
        assertEquals("https://prod.example.com", config.getCorsOrigin());
    }

    @Test
    void cliOverridesConfigFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("query.cfg");
        Files.writeString(configFile, "server_port=7070\ncors_origin=https://file.example.com\n");

        QueryServerConfig config = QueryServerConfig.load(new String[]{
                "config=" + configFile.toAbsolutePath(),
                "server_port=9999",
                "cors_origin=https://cli.example.com"
        });

        assertEquals(9999, config.getServerPort());
        assertEquals("https://cli.example.com", config.getCorsOrigin());
    }
}
