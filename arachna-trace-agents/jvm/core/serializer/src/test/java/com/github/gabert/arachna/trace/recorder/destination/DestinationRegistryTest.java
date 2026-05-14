package com.github.gabert.arachna.trace.recorder.destination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Round 3 added this — the factory map was previously untested. A typo in
 * {@code FACTORIES} (e.g., wrong key, swapped constructor) would only be caught
 * by a downstream end-to-end test that happens to use that destination type.
 */
class DestinationRegistryTest {

    @Test
    void createFileDestination(@TempDir Path tempDir) {
        Destination dest = DestinationRegistry.create("file",
                Map.of("session_dump_location", tempDir.toString()));
        assertInstanceOf(FileDestination.class, dest);
    }

    @Test
    void createHttpDestination() {
        Destination dest = DestinationRegistry.create("http", Map.of());
        assertInstanceOf(HttpDestination.class, dest);
    }

    @Test
    void createTestDestination() {
        Destination dest = DestinationRegistry.create("test", Map.of());
        assertInstanceOf(TestDestination.class, dest);
    }

    @Test
    void unknownTypeThrows() {
        // A typo in `destination=` config must fail loud at startup —
        // a silent fallback would mask the misconfiguration.
        assertThrows(IllegalArgumentException.class,
                () -> DestinationRegistry.create("kafka", Map.of()));
    }
}
