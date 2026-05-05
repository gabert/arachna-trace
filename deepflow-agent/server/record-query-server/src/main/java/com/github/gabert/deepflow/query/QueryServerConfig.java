package com.github.gabert.deepflow.query;

import com.github.gabert.deepflow.config.ConfigLoader;

import java.io.IOException;
import java.util.Map;

public class QueryServerConfig {
    private static final int DEFAULT_PORT = 8082;
    private static final String DEFAULT_CLICKHOUSE_URL = "http://localhost:8123";
    private static final String DEFAULT_CLICKHOUSE_DATABASE = "deepflow";
    private static final String DEFAULT_CLICKHOUSE_USER = "deepflow";
    private static final String DEFAULT_CLICKHOUSE_PASSWORD = "deepflow";
    private static final String DEFAULT_CORS_ORIGIN = "http://localhost:5173";

    private final int serverPort;
    private final String clickhouseUrl;
    private final String clickhouseDatabase;
    private final String clickhouseUser;
    private final String clickhousePassword;
    private final String corsOrigin;

    private QueryServerConfig(Map<String, String> configMap) {
        this.serverPort = Integer.parseInt(
                configMap.getOrDefault("server_port", String.valueOf(DEFAULT_PORT)));
        this.clickhouseUrl = configMap.getOrDefault("clickhouse_url", DEFAULT_CLICKHOUSE_URL);
        this.clickhouseDatabase = configMap.getOrDefault("clickhouse_database", DEFAULT_CLICKHOUSE_DATABASE);
        this.clickhouseUser = configMap.getOrDefault("clickhouse_user", DEFAULT_CLICKHOUSE_USER);
        this.clickhousePassword = configMap.getOrDefault("clickhouse_password", DEFAULT_CLICKHOUSE_PASSWORD);
        this.corsOrigin = configMap.getOrDefault("cors_origin", DEFAULT_CORS_ORIGIN);
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getClickhouseUrl() {
        return clickhouseUrl;
    }

    public String getClickhouseDatabase() {
        return clickhouseDatabase;
    }

    public String getClickhouseUser() {
        return clickhouseUser;
    }

    public String getClickhousePassword() {
        return clickhousePassword;
    }

    public String getCorsOrigin() {
        return corsOrigin;
    }

    public static QueryServerConfig load(String[] args) throws IOException {
        Map<String, String> argMap = ConfigLoader.parseCliArgs(args);
        return new QueryServerConfig(ConfigLoader.mergeWithFile(argMap));
    }
}
