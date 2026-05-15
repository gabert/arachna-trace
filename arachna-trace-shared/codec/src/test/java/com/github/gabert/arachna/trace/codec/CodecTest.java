package com.github.gabert.arachna.trace.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end Codec tests at the public-API layer: {@link Codec#encode(Object)} →
 * {@link Codec#decode(byte[])} → {@link Codec#toReadableJson(Object)}. Asserts the field-id ↔ field-name mapping
 * contract that the rest of the pipeline (RecordRenderer, Hasher) depends on.
 *
 * <p>EnvelopeSerializerTest exercises the encoder side at the CBOR level and bypasses
 * {@code toReadableJson}; this test class fills the gap by asserting the humanized JSON shape directly.
 */
class CodecTest {

   private static final ObjectMapper JSON = new ObjectMapper();

   static class Person {

      public String name;

      public int age;

      Person(String name, int age) {
         this.name = name;
         this.age = age;
      }
   }

   /** Round-trip helper: encode → decode → toReadableJson → parse into a Map. */
   private static Map<String, Object> humanize(Object value) throws Exception {
      byte[] cbor = Codec.encode(value);
      Object decoded = Codec.decode(cbor);
      String json = Codec.toReadableJson(decoded);
      return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
      });
   }

   @Test
   void pojoHumanizesToEnvelopeWithFlattenedFields() throws Exception {
      // POJO → envelope with object_id, class, and user fields inlined as
      // siblings (NOT under a nested "value" entry).
      Map<String, Object> out = humanize(new Person("Alice", 30));

      assertTrue(out.containsKey("object_id"), "humanized envelope must carry object_id");
      assertEquals(Person.class.getName(), out.get("class"));
      assertEquals("Alice", out.get("name"), "POJO fields must be siblings of object_id/class");
      assertEquals(30, out.get("age"));
      assertFalse(out.containsKey("value"),
         "POJO content must be flattened, not nested under \"value\"");
   }

   @Test
   void listHumanizesToEnvelopeWithItemsKey() throws Exception {
      // Collections render under "items" — distinguishes container envelopes
      // from POJO envelopes for downstream consumers (the UI / processor).
      List<Person> people = new ArrayList<>();
      people.add(new Person("Alice", 30));
      people.add(new Person("Bob", 25));

      Map<String, Object> out = humanize(people);

      assertTrue(out.containsKey("items"), "List must humanize under \"items\"");
      assertFalse(out.containsKey("value"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
      assertEquals(2, items.size());
      assertEquals("Alice", items.get(0).get("name"));
      assertEquals("Bob", items.get(1).get("name"));
   }

   @Test
   void cyclicGraphHumanizesWithRefIdAndCycleRef() throws Exception {
      // Cycle back-reference deserialises to a map with ref_id + cycle_ref:true.
      // No object_id/class — those mark fully-serialized envelopes only.
      EnvelopeSerializerCycleFixture root = new EnvelopeSerializerCycleFixture("root");
      EnvelopeSerializerCycleFixture child = new EnvelopeSerializerCycleFixture("child");
      root.next = child;
      child.next = root;

      Map<String, Object> outer = humanize(root);
      @SuppressWarnings("unchecked")
      Map<String, Object> childEnv = (Map<String, Object>) outer.get("next");
      assertNotNull(childEnv, "next field must be a humanized child envelope");
      @SuppressWarnings("unchecked")
      Map<String, Object> cycleNode = (Map<String, Object>) childEnv.get("next");
      assertNotNull(cycleNode, "child.next must be present");
      assertEquals(Boolean.TRUE, cycleNode.get("cycle_ref"),
         "cycle node must carry cycle_ref:true");
      assertEquals(((Number) outer.get("object_id")).longValue(),
         ((Number) cycleNode.get("ref_id")).longValue(),
         "ref_id must equal the root's object_id");
   }

   /** Cycle fixture — top-level so Jackson can find the field with reflection. */
   public static class EnvelopeSerializerCycleFixture {

      public String label;

      public EnvelopeSerializerCycleFixture next;

      EnvelopeSerializerCycleFixture(String label) {
         this.label = label;
      }
   }

   @Test
   void plainMapHumanizesWithoutEnvelopeMarkers() throws Exception {
      // A Map<String,Object> (declared field) gets wrapped at the envelope
      // boundary but its inner entries — when scalar — do NOT acquire object_id.
      Map<String, Object> input = new LinkedHashMap<>();
      input.put("timeout", 30);
      input.put("retries", 3);

      Map<String, Object> out = humanize(input);

      // The outer Map itself becomes an envelope (object_id+class), but its
      // scalar entries are inlined as siblings (Codec treats the Map's VALUE
      // payload as inline fields, same as a POJO).
      assertEquals(30, out.get("timeout"));
      assertEquals(3, out.get("retries"));
   }

   @Test
   void nullValueRoundTripsAsJsonNull() throws Exception {
      // Top-level null passes through both encode and humanize without
      // exploding — the pipeline must tolerate null arguments / returns.
      byte[] cbor = Codec.encode(null);
      Object decoded = Codec.decode(cbor);
      String json = Codec.toReadableJson(decoded);
      assertEquals("null", json, "null round-trips as JSON null literal");
   }

   @Test
   void scalarTopLevelRoundTripsAsRawValue() throws Exception {
      // Scalars are not wrapped — they round-trip as raw JSON.
      assertEquals("\"hello\"", Codec.toReadableJson(Codec.decode(Codec.encode("hello"))));
      assertEquals("42", Codec.toReadableJson(Codec.decode(Codec.encode(42))));
      assertEquals("true", Codec.toReadableJson(Codec.decode(Codec.encode(true))));
   }

   @Test
   void humanizeMapsIntegerKeysToReadableNames() throws Exception {
      // The integer CBOR field-ids (1=OBJECT_ID, 2=CLASS_NAME, 3=VALUE,
      // 4=REF_ID, 5=CYCLE_REF) must be renamed to their English-language
      // equivalents on the JSON side. This is the load-bearing contract
      // for every downstream consumer.
      Map<String, Object> out = humanize(new Person("X", 1));

      // Keys must be readable names, not raw integers.
      for (String key : out.keySet()) {
         assertFalse(key.matches("\\d+"),
            "humanized JSON must rename integer CBOR keys; saw raw int key: " + key);
      }
      assertTrue(out.containsKey("object_id"));
      assertTrue(out.containsKey("class"));
   }

   @Test
   void encodedBytesAreNonEmptyForPojo() throws Exception {
      // Bare smoke test that encode() yields a non-trivial payload for a
      // simple POJO. Catches catastrophic encoder breakage.
      byte[] bytes = Codec.encode(new Person("X", 1));
      assertNotNull(bytes);
      assertTrue(bytes.length > 10, "encoded POJO must be more than a few bytes");
   }

   @Test
   void decodedShapeUsesIntegerFieldIds() throws Exception {
      // Verifies the agent-side raw shape (pre-humanize). The processor must
      // be able to consume this directly when it bypasses toReadableJson.
      byte[] cbor = Codec.encode(new Person("X", 1));
      Object decoded = Codec.decode(cbor);
      assertInstanceOf(Map.class, decoded);
      @SuppressWarnings("unchecked")
      Map<Object, Object> env = (Map<Object, Object>) decoded;
      // CBOR decoder produces Integer keys for small ids; the field-id
      // constants are public on FieldIds for that reason.
      assertTrue(env.containsKey(1) || env.containsKey("1"),
         "decoded envelope must carry OBJECT_ID (field-id 1)");
      assertTrue(env.containsKey(2) || env.containsKey("2"),
         "decoded envelope must carry CLASS_NAME (field-id 2)");
   }
}
