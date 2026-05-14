package com.github.gabert.arachna.trace.recorder.destination;

import com.github.gabert.arachna.trace.recorder.buffer.RecordBuffer;
import com.github.gabert.arachna.trace.recorder.buffer.UnboundedRecordBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecordDrainerTest {

    // --- Records are delivered to destination ---

    @Test
    void drainsRecordsToDestination() throws Exception {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        CollectingDestination dest = new CollectingDestination();
        RecordDrainer drainer = new RecordDrainer(buffer, dest);

        buffer.offer(new byte[]{1, 2, 3});
        buffer.offer(new byte[]{4, 5, 6});

        drainer.start();
        waitUntilEmpty(buffer);
        drainer.stop();

        // No version record any more — startup-only records are emitted by
        // RecorderManager, not the drainer. Just the two offered records.
        assertEquals(2, dest.records.size());
        assertArrayEquals(new byte[]{1, 2, 3}, dest.records.get(0));
        assertArrayEquals(new byte[]{4, 5, 6}, dest.records.get(1));
        assertTrue(dest.flushed);
    }

    // --- Drains all offered records under load ---

    @Test
    void drainsAllOfferedRecords() throws Exception {
        // 100 records offered while the drainer is running — some land in the
        // poll loop, any stragglers fall through to drainRemaining on stop.
        // (Was misleadingly named drainsRemainingRecordsOnStop; the previous
        // name implied it isolated the post-stop codepath, which it doesn't —
        // most records get picked up by the loop.)
        RecordBuffer buffer = new UnboundedRecordBuffer();
        CollectingDestination dest = new CollectingDestination();
        RecordDrainer drainer = new RecordDrainer(buffer, dest);

        drainer.start();
        Thread.sleep(50);

        for (int i = 0; i < 100; i++) {
            buffer.offer(new byte[]{(byte) i});
        }

        drainer.stop();

        assertEquals(100, dest.records.size());
        assertTrue(dest.flushed);
    }

    // --- Flush is called on stop ---

    @Test
    void flushCalledOnStop() throws Exception {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        CollectingDestination dest = new CollectingDestination();
        RecordDrainer drainer = new RecordDrainer(buffer, dest);

        drainer.start();
        drainer.stop();

        assertTrue(dest.flushed);
    }

    // --- Drainer survives exception in destination ---

    @Test
    void continuesAfterDestinationException() throws Exception {
        RecordBuffer buffer = new UnboundedRecordBuffer();
        FailOnceDestination dest = new FailOnceDestination();
        RecordDrainer drainer = new RecordDrainer(buffer, dest);

        // First offered record will trigger the simulated failure;
        // the next two must still be delivered.
        buffer.offer(new byte[]{0});
        buffer.offer(new byte[]{1});
        buffer.offer(new byte[]{2});

        drainer.start();
        waitUntilEmpty(buffer);
        drainer.stop();

        assertEquals(2, dest.records.size());
        assertArrayEquals(new byte[]{1}, dest.records.get(0));
        assertArrayEquals(new byte[]{2}, dest.records.get(1));
    }

    // --- Intermediate flush when buffer empties ---

    @Test
    void intermediateFlushWhenBufferEmpties() throws Exception {
        // Drain loop contract: when the buffer empties after delivering at
        // least one record, the next iteration calls destination.flush()
        // (rather than spinning idle on an unflushed batch). This is what
        // makes file output readable while the application is still running
        // (lines visible before close) — distinct from the on-stop flush.
        RecordBuffer buffer = new UnboundedRecordBuffer();
        FlushCountingDestination dest = new FlushCountingDestination();
        RecordDrainer drainer = new RecordDrainer(buffer, dest);

        drainer.start();
        buffer.offer(new byte[]{1});
        // Wait for the loop to drain the offered record and then flush once.
        long deadline = System.currentTimeMillis() + 2000;
        while (dest.flushCount.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        int flushesAfterFirstBatch = dest.flushCount.get();
        assertTrue(flushesAfterFirstBatch >= 1,
                "drainer must flush at least once after the buffer empties (saw "
                        + flushesAfterFirstBatch + ")");

        drainer.stop();
    }

    // --- Utilities ---

    private static void waitUntilEmpty(RecordBuffer buffer) throws InterruptedException {
        for (int i = 0; i < 200 && !buffer.isEmpty(); i++) {
            Thread.sleep(10);
        }
    }

    // --- Test destinations ---

    private static class CollectingDestination implements Destination {
        final List<byte[]> records = new ArrayList<>();
        boolean flushed = false;

        @Override
        public void accept(byte[] record) {
            records.add(record);
        }

        @Override
        public void flush() {
            flushed = true;
        }

        @Override
        public void close() {}
    }

    private static class FlushCountingDestination implements Destination {
        final AtomicInteger flushCount = new AtomicInteger(0);

        @Override
        public void accept(byte[] record) {}

        @Override
        public void flush() {
            flushCount.incrementAndGet();
        }

        @Override
        public void close() {}
    }

    private static class FailOnceDestination implements Destination {
        final List<byte[]> records = new ArrayList<>();
        boolean flushed = false;
        private boolean failed = false;

        @Override
        public void accept(byte[] record) {
            if (!failed) {
                failed = true;
                throw new RuntimeException("simulated failure");
            }
            records.add(record);
        }

        @Override
        public void flush() {
            flushed = true;
        }

        @Override
        public void close() {}
    }
}
