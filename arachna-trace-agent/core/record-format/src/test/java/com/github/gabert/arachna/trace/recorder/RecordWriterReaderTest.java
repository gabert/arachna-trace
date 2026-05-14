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

    @Test
    void nestedMethodTraceRoundtrip() throws Exception {
        String sigOuter = "com.example::Service.handle() -> void [public]";
        String sigInner = "com.example::Dao.save(java.lang::String) -> int [public]";
        long ts1 = 1000L;
        long ts2 = 2000L;
        long ts3 = 3000L;
        long ts4 = 4000L;
        byte[] outerArgs = Codec.encode(new Object[]{});
        byte[] innerArgs = Codec.encode(new Object[]{"data"});
        byte[] innerRet = Codec.encode(1);

        byte[] entryOuter = RecordWriter.logEntry(SESSION, sigOuter, "http-handler-1", ts1, 10, 0L, null, null, null, outerArgs);
        byte[] entryInner = RecordWriter.logEntry(SESSION, sigInner, "http-handler-1", ts2, 20, 1L, null, null, null, innerArgs);
        byte[] exitInner = RecordWriter.logExit(SESSION, "http-handler-1", ts3, 0L, null, innerRet, false);
        byte[] exitOuter = RecordWriter.logExit(SESSION, "http-handler-1", ts4, 0L, null, null, true);

        byte[] stream = concat(entryOuter, entryInner, exitInner, exitOuter);

        List<TraceRecord> records = RecordReader.readAll(stream);
        assertEquals(8, records.size());

        // entry outer: METHOD_START + ARGUMENTS
        MethodStartRecord outer = assertInstanceOf(MethodStartRecord.class, records.get(0));
        assertInstanceOf(ArgumentsRecord.class, records.get(1));
        // entry inner: METHOD_START + ARGUMENTS
        MethodStartRecord inner = assertInstanceOf(MethodStartRecord.class, records.get(2));
        assertInstanceOf(ArgumentsRecord.class, records.get(3));
        // exit inner: METHOD_END + RETURN
        MethodEndRecord innerEnd = assertInstanceOf(MethodEndRecord.class, records.get(4));
        ReturnRecord innerRetRecord = assertInstanceOf(ReturnRecord.class, records.get(5));
        // exit outer: METHOD_END + RETURN (void)
        MethodEndRecord outerEnd = assertInstanceOf(MethodEndRecord.class, records.get(6));
        ReturnRecord outerRetRecord = assertInstanceOf(ReturnRecord.class, records.get(7));

        // Session ID on all start/end frames
        assertEquals(SESSION, outer.sessionId());
        assertEquals(SESSION, inner.sessionId());
        assertEquals(SESSION, innerEnd.sessionId());
        assertEquals(SESSION, outerEnd.sessionId());

        // Signatures, threads, request IDs, timestamps
        assertEquals(sigOuter, outer.signature());
        assertEquals(sigInner, inner.signature());
        assertEquals("http-handler-1", outer.threadName());
        assertEquals("http-handler-1", inner.threadName());
        assertEquals(0L, outer.requestId());
        assertEquals(1L, inner.requestId());
        assertEquals(ts1, outer.timestamp());
        assertEquals(ts2, inner.timestamp());
        assertEquals(ts3, innerEnd.timestamp());
        assertEquals(ts4, outerEnd.timestamp());

        // Inner return has payload, outer return is void
        assertFalse(innerRetRecord.isVoid());
        assertTrue(outerRetRecord.isVoid());
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
        byte[] data = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 1, 0L, null, null, null, Codec.encode(new Object[]{"x"}));
        byte[] chopped = Arrays.copyOf(data, data.length - 1);
        assertThrows(IllegalArgumentException.class, () -> RecordReader.readAll(chopped));
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
