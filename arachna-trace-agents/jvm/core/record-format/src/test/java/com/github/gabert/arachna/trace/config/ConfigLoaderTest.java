package com.github.gabert.arachna.trace.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round 2 added this — ConfigLoader had no direct tests despite being on the
 * critical config path for both the agent (AgentConfig.fromAgentArgs) and the
 * destinations (FileDestination, emit-tag parsing).
 */
class ConfigLoaderTest {

   @Test
   void parseAgentArgsNullReturnsEmptyMap() {
      // The agent is sometimes attached with no inline args (config=… is
      // optional). A null input must not throw.
      assertTrue(ConfigLoader.parseAgentArgs(null).isEmpty());
   }

   @Test
   void parseAgentArgsSplitsOnAmpersand() {
      Map<String, String> map = ConfigLoader.parseAgentArgs("a=1&b=2&c=hello world");
      assertEquals("1", map.get("a"));
      assertEquals("2", map.get("b"));
      assertEquals("hello world", map.get("c"));
   }

   @Test
   void parseAgentArgsSkipsMalformedPairsWithoutEquals() {
      // Tokens without '=' are dropped (not exception-throwing) so a
      // stray empty segment from "a=1&&b=2" or a typo doesn't kill the
      // attach. Lock this permissive behaviour.
      Map<String, String> map = ConfigLoader.parseAgentArgs("a=1&garbage&b=2");
      assertEquals("1", map.get("a"));
      assertEquals("2", map.get("b"));
      assertNull(map.get("garbage"));
   }

   @Test
   void mergeWithFileArgsOverrideFileValues(@TempDir Path tmp) throws IOException {
      // Inline agent args must beat file values. Documented behaviour:
      // see CLAUDE.md "config can be passed as a file path ... inline
      // values override file values".
      Path cfg = tmp.resolve("test.cfg");
      Files.writeString(cfg, "# header\nfromFile=fileVal\nshared=fileVal\n");

      Map<String, String> args = ConfigLoader.parseAgentArgs(
              "config=" + cfg + "&shared=argVal");
      Map<String, String> merged = ConfigLoader.mergeWithFile(args);

      assertEquals("fileVal", merged.get("fromFile"), "file-only key survives");
      assertEquals("argVal", merged.get("shared"), "arg overrides file");
   }

   @Test
   void parseEmitTagsAlwaysIncludesMS() {
      // MS is structurally required by downstream parsers (the method-start
      // anchor); the parser must inject it whether or not the user lists it.
      Set<String> tags = ConfigLoader.parseEmitTags("TI,AR", null);
      assertTrue(tags.contains("MS"));
      assertTrue(tags.contains("TI"));
      assertTrue(tags.contains("AR"));
   }

   @Test
   void parseEmitTagsUppercasesAndTrims() {
      Set<String> tags = ConfigLoader.parseEmitTags(" ri , ts , cl ", "");
      assertTrue(tags.contains("RI"));
      assertTrue(tags.contains("TS"));
      assertTrue(tags.contains("CL"));
   }

   @Test
   void parseEmitTagsNullInputAndNullDefaultFallsBackToMSOnly() {
      // Before the fix this combination NPE'd on input.split(",").
      // The agent guards this in production by always passing a default,
      // but the defensive fallback should still hold.
      Set<String> tags = ConfigLoader.parseEmitTags(null, null);
      assertEquals(Set.of("MS"), tags);
   }
}
