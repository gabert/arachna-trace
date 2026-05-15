package com.github.gabert.arachna.trace.recorder;

import com.github.gabert.arachna.trace.codec.Codec;
import com.github.gabert.arachna.trace.recorder.record.ArgumentsRecord;
import com.github.gabert.arachna.trace.recorder.record.ExceptionRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodEndRecord;
import com.github.gabert.arachna.trace.recorder.record.MethodStartRecord;
import com.github.gabert.arachna.trace.recorder.record.RecordReader;
import com.github.gabert.arachna.trace.recorder.record.RecordWriter;
import com.github.gabert.arachna.trace.recorder.record.ReturnRecord;
import com.github.gabert.arachna.trace.recorder.record.SequenceRecord;
import com.github.gabert.arachna.trace.recorder.record.TraceRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecordWriterReaderTest {

    private static final String SIGNATURE = "com.example::Foo.bar(java.lang::String) -> void [public]";
    private static final String THREAD = "main";
    private static final String SESSION = "test-session-01";

    // --- logEntry ---

    @Test
    void logEntryProducesTwoRecords() throws Exception {
        byte[] argsCbor = Codec.encode(new Object[]{"arg1", 42});
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, ts, 99, 0L, null, null, null, argsCbor);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());

        MethodStartRecord meta = assertInstanceOf(MethodStartRecord.class, records.get(0));
        assertEquals(SESSION, meta.sessionId());
        assertEquals(SIGNATURE, meta.signature());
        assertEquals(THREAD, meta.threadName());
        assertEquals(ts, meta.timestamp());
        assertEquals(99, meta.callerLine());

        ArgumentsRecord args = assertInstanceOf(ArgumentsRecord.class, records.get(1));
        assertArrayEquals(argsCbor, args.cbor());
    }

    @Test
    void logEntryWithNullSessionId() throws Exception {
        byte[] argsCbor = Codec.encode(new Object[]{"arg1"});
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, ts, 10, 0L, null, null, null, argsCbor);

        List<TraceRecord> records = RecordReader.readAll(data);
        MethodStartRecord meta = assertInstanceOf(MethodStartRecord.class, records.get(0));
        assertNull(meta.sessionId());
        assertEquals(SIGNATURE, meta.signature());
    }

    // --- logExit (value) ---

    @Test
    void logExitValueProducesTwoRecords() throws Exception {
        byte[] retCbor = Codec.encode("returned");
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logExit(SESSION, THREAD, ts, 5L, null, retCbor, false);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());

        MethodEndRecord meta = assertInstanceOf(MethodEndRecord.class, records.get(0));
        assertEquals(SESSION, meta.sessionId());
        assertEquals(ts, meta.timestamp());
        assertEquals(THREAD, meta.threadName());
        assertEquals(5L, meta.requestId());

        ReturnRecord ret = assertInstanceOf(ReturnRecord.class, records.get(1));
        assertFalse(ret.isVoid());
        assertArrayEquals(retCbor, ret.cbor());
    }

    // --- logExit (void) ---

    @Test
    void logExitVoidHasEmptyReturnPayload() {
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logExit(SESSION, THREAD, ts, 0L, null, null, true);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());

        MethodEndRecord meta = assertInstanceOf(MethodEndRecord.class, records.get(0));
        assertEquals(SESSION, meta.sessionId());

        ReturnRecord ret = assertInstanceOf(ReturnRecord.class, records.get(1));
        assertTrue(ret.isVoid());
        assertEquals(0, ret.payloadBytes().length);
    }

    @Test
    void logExitWithNullSessionId() throws Exception {
        byte[] retCbor = Codec.encode("val");
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logExit(null, THREAD, ts, 0L, null, retCbor, false);

        MethodEndRecord meta = assertInstanceOf(MethodEndRecord.class,
                RecordReader.readAll(data).get(0));
        assertNull(meta.sessionId());
        assertEquals(THREAD, meta.threadName());
    }

    // --- logExitException ---

    @Test
    void logExitExceptionProducesTwoRecords() throws Exception {
        byte[] excCbor = Codec.encode(Map.of("message", "NPE"));
        long ts = System.currentTimeMillis();

        byte[] data = RecordWriter.logExitException(SESSION, THREAD, ts, 0L, null, excCbor);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());

        MethodEndRecord meta = assertInstanceOf(MethodEndRecord.class, records.get(0));
        assertEquals(SESSION, meta.sessionId());
        assertEquals(ts, meta.timestamp());
        assertEquals(THREAD, meta.threadName());

        ExceptionRecord exc = assertInstanceOf(ExceptionRecord.class, records.get(1));
        assertArrayEquals(excCbor, exc.cbor());
    }

    // --- Full method trace ---

    @Test
    void fullMethodTraceRoundtrip() throws Exception {
        long tsStart = System.currentTimeMillis();
        long tsEnd = tsStart + 5;
        byte[] argsCbor = Codec.encode(new Object[]{"x", 1});
        byte[] retCbor = Codec.encode(42);

        byte[] entry = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, tsStart, 10, 0L, null, null, null, argsCbor);
        byte[] exit = RecordWriter.logExit(SESSION, THREAD, tsEnd, 0L, null, retCbor, false);

        byte[] stream = concat(entry, exit);

        List<TraceRecord> records = RecordReader.readAll(stream);
        assertEquals(4, records.size());

        MethodStartRecord startMeta = assertInstanceOf(MethodStartRecord.class, records.get(0));
        assertInstanceOf(ArgumentsRecord.class, records.get(1));
        MethodEndRecord endMeta = assertInstanceOf(MethodEndRecord.class, records.get(2));
        assertInstanceOf(ReturnRecord.class, records.get(3));

        assertEquals(SESSION, startMeta.sessionId());
        assertEquals(SESSION, endMeta.sessionId());
        assertEquals(tsStart, startMeta.timestamp());
        assertEquals(tsEnd, endMeta.timestamp());
        assertEquals(THREAD, endMeta.threadName());
        assertEquals(0L, endMeta.requestId());
    }

    @Test
    void fullMethodTraceWithException() throws Exception {
        long tsStart = System.currentTimeMillis();
        long tsEnd = tsStart + 3;
        byte[] argsCbor = Codec.encode(new Object[]{"input"});
        byte[] excCbor = Codec.encode(Map.of("message", "fail", "stacktrace", List.of("at X.y(X.java:5)")));

        byte[] entry = RecordWriter.logEntry(SESSION, SIGNATURE, THREAD, tsStart, 20, 0L, null, null, null, argsCbor);
        byte[] exit = RecordWriter.logExitException(SESSION, THREAD, tsEnd, 0L, null, excCbor);

        byte[] stream = concat(entry, exit);

        List<TraceRecord> records = RecordReader.readAll(stream);
        assertEquals(4, records.size());

        MethodStartRecord startMeta = assertInstanceOf(MethodStartRecord.class, records.get(0));
        assertInstanceOf(ArgumentsRecord.class, records.get(1));
        MethodEndRecord endMeta = assertInstanceOf(MethodEndRecord.class, records.get(2));
        assertInstanceOf(ExceptionRecord.class, records.get(3));

        assertEquals(SESSION, startMeta.sessionId());
        assertEquals(SESSION, endMeta.sessionId());
        assertEquals(tsEnd, endMeta.timestamp());
        assertEquals(THREAD, endMeta.threadName());
        assertEquals(0L, endMeta.requestId());
    }

    // --- RecordReader edge cases ---

    @Test
    void readAllFromInputStream() throws Exception {
        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 1, 0L, null, null, null, Codec.encode(new Object[]{}));

        List<TraceRecord> records = RecordReader.readAll(new ByteArrayInputStream(data));
        assertEquals(2, records.size());
        assertInstanceOf(MethodStartRecord.class, records.get(0));
    }

    @Test
    void emptyInputReturnsEmptyList() {
        List<TraceRecord> records = RecordReader.readAll(new byte[0]);
        assertTrue(records.isEmpty());
    }

    @Test
    void truncatedFrameThrows() throws Exception {
        // Chops 1 byte off the END so the header parses (declares length N)
        // but the payload is N-1 bytes — exercises the payload-truncation
        // branch. The header-truncation case (data shorter than 5 bytes)
        // is intentionally silent: see headerTruncationIsSilent.
        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 1, 0L, null, null, null, Codec.encode(new Object[]{"x"}));
        byte[] chopped = Arrays.copyOf(data, data.length - 1);
        assertThrows(IllegalArgumentException.class, () -> RecordReader.readAll(chopped));
    }

    @Test
    void headerTruncationIsSilent() {
        // A stream ending mid-header (less than 5 bytes remaining) is
        // treated as clean end-of-stream — the reader stops at whatever
        // it has parsed. Documents the asymmetry vs. payload truncation.
        List<TraceRecord> records = RecordReader.readAll(new byte[]{0x01, 0x00});
        assertTrue(records.isEmpty());
    }

    @Test
    void negativeLengthFrameThrows() {
        // Hand-crafted 5-byte header with a negative payload length.
        // Guards against a frame that would otherwise allocate a huge
        // copyOfRange or wrap around in arithmetic.
        byte[] data = new byte[]{
                0x01,                                   // type METHOD_START
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF  // length = -1
        };
        assertThrows(IllegalArgumentException.class, () -> RecordReader.readAll(data));
    }

    @Test
    void largePayload() throws Exception {
        byte[] bigArgs = new byte[100_000];
        Arrays.fill(bigArgs, (byte) 0x42);

        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 1, 0L, null, null, null, bigArgs);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(2, records.size());
        ArgumentsRecord args = assertInstanceOf(ArgumentsRecord.class, records.get(1));
        assertArrayEquals(bigArgs, args.cbor());
    }

    // --- SEQUENCE record ---

    @Test
    void sequenceRecordRoundtrip() {
        UUID callId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        byte[] data = RecordWriter.sequence(callId, 12345L);

        List<TraceRecord> records = RecordReader.readAll(data);
        assertEquals(1, records.size());

        SequenceRecord seq = assertInstanceOf(SequenceRecord.class, records.get(0));
        assertEquals(callId, seq.callId());
        assertEquals(12345L, seq.seq());
    }

    @Test
    void sequenceRecordWithMaxSeq() {
        UUID callId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        byte[] data = RecordWriter.sequence(callId, Long.MAX_VALUE);

        SequenceRecord seq = assertInstanceOf(SequenceRecord.class,
                RecordReader.readAll(data).get(0));
        assertEquals(Long.MAX_VALUE, seq.seq());
    }

    // --- String length boundary (fixes the silent (short)-truncation bug) ---

    @Test
    void signatureOver65kBytesThrowsLoud() {
        // Before the fix: putShort((short) len) silently truncated lengths
        // >= 65536 modulo 65536, producing a frame the reader misparsed
        // into a tiny string + garbage. Now the encoder fails fast with
        // IAE so a callable with a huge generic signature is impossible
        // to silently corrupt.
        char[] huge = new char[70_000];
        Arrays.fill(huge, 'A');
        String hugeSig = new String(huge);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RecordWriter.logEntrySimple(null, hugeSig, THREAD, 1000L, 1, 0L, null, null));
        assertTrue(ex.getMessage().contains("65535"),
                "error message must point at the 64 KiB limit");
    }

    @Test
    void signatureAt65535BytesRoundTrips() {
        // The exact-max-length boundary: must succeed, not throw.
        char[] max = new char[65_535];
        Arrays.fill(max, 'B');
        String maxSig = new String(max);

        byte[] data = RecordWriter.logEntrySimple(null, maxSig, THREAD, 0L, 0, 0L, null, null);
        MethodStartRecord ms = (MethodStartRecord) RecordReader.readAll(data).get(0);
        assertEquals(maxSig, ms.signature());
    }

    @Test
    void signatureWithNonAsciiUtf8RoundTrips() {
        // The length prefix counts UTF-8 BYTES, not chars. Locks the
        // contract: a string whose char-count fits but byte-count doesn't
        // (or vice versa) round-trips correctly.
        String sig = "Service.handle(λ→μ, 日本語) → Optional<结果>";
        byte[] data = RecordWriter.logEntrySimple(null, sig, THREAD, 0L, 0, 0L, null, null);
        MethodStartRecord ms = (MethodStartRecord) RecordReader.readAll(data).get(0);
        assertEquals(sig, ms.signature());
    }

    @Test
    void callerLineNegativeRoundTrips() {
        // The agent uses -1 to mark "unknown caller line." Putters/getters
        // must preserve sign across the int encoding.
        byte[] data = RecordWriter.logEntrySimple(null, SIGNATURE, THREAD, 0L, -1, 0L, null, null);
        MethodStartRecord ms = (MethodStartRecord) RecordReader.readAll(data).get(0);
        assertEquals(-1, ms.callerLine());
    }

    @Test
    void concatSkipsNullEntries() {
        // BinaryUtil.concat is the writer's null-tolerant joiner: this is
        // what lets RecordWriter.logEntry pass `null` when thisInstanceCbor
        // is absent without producing a bogus frame.
        byte[] a = {0x01, 0x02};
        byte[] b = {0x03};
        byte[] result = com.github.gabert.arachna.trace.recorder.record.BinaryUtil.concat(a, null, b, null);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, result);
    }

    // --- Test utilities ---

    private static byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }
        byte[] result = new byte[totalLength];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, pos, array.length);
            pos += array.length;
        }
        return result;
    }
}
