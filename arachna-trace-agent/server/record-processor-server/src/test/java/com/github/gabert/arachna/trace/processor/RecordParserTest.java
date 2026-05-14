package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.recorder.destination.RecordRenderer.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecordParserTest {

    private static final UUID OUT  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INN  = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTH  = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void singleCallProducesOneParsedCall() {
        Result r = result(
                "TS;1000",
                "SI;sess-1",
                "MS;com.example.Foo.bar()V",
                "TN;main",
                "RI;42",
                "CL;55",
                "CI;" + OUT,
                "TI;7",
                "AR;{\"__meta__\":{\"id\":7,\"class\":\"X\",\"hash\":\"abc\"}}",
                "TE;1500",
                "TN;main",
                "RI;42",
                "CI;" + OUT,
                "RT;VOID");

        List<ParsedCall> calls = new RecordParser().parse(r);

        assertEquals(1, calls.size());
        ParsedCall c = calls.get(0);
        assertEquals(OUT, c.callId());
        assertNull(c.parentCallId());
        assertEquals("sess-1", c.sessionId());
        assertEquals(42L, c.requestId());
        assertEquals("main", c.threadName());
        assertEquals(1000L, c.tsInMillis());
        assertEquals(1500L, c.tsOutMillis());
        assertEquals("com.example.Foo.bar()V", c.signature());
        assertEquals(55, c.callerLine());
        assertEquals("VOID", c.returnType());
        assertEquals(7L, c.thisIdRef());
        assertNull(c.thisJson());
    }

    @Test
    void nestedCallsAreEmittedInPostOrderAndCarryParentLink() {
        // Outer A calls inner B. Inner ends first, outer ends second.
        Result r = result(
                "TS;1000", "MS;A.outer()V", "TN;t", "RI;1", "CL;10",
                "CI;" + OUT, "AR;{}",
                "TS;1100", "MS;B.inner()V", "TN;t", "RI;1", "CL;20",
                "CI;" + INN, "PI;" + OUT, "AR;{}",
                "TE;1200", "TN;t", "RI;1", "CI;" + INN, "RT;VOID",
                "TE;1300", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID");

        List<ParsedCall> calls = new RecordParser().parse(r);

        assertEquals(2, calls.size());
        ParsedCall inner = calls.get(0);
        assertEquals("B.inner()V", inner.signature());
        assertEquals(1100L, inner.tsInMillis());
        assertEquals(1200L, inner.tsOutMillis());
        assertEquals(INN, inner.callId());
        assertEquals(OUT, inner.parentCallId(), "inner's parent must be outer");

        ParsedCall outer = calls.get(1);
        assertEquals("A.outer()V", outer.signature());
        assertEquals(1000L, outer.tsInMillis());
        assertEquals(1300L, outer.tsOutMillis());
        assertEquals(OUT, outer.callId());
        assertNull(outer.parentCallId(), "outer is the root — no parent");
    }

    @Test
    void valueReturnIsCaptured() {
        Result r = result(
                "TS;1", "MS;F.f()I", "TN;t", "RI;1", "CL;1", "CI;" + OUT,
                "TE;2", "TN;t", "RI;1", "CI;" + OUT, "RT;VALUE", "RE;42");
        ParsedCall c = new RecordParser().parse(r).get(0);
        assertEquals("VALUE", c.returnType());
        assertEquals("42", c.returnJson());
    }

    @Test
    void exceptionReturnIsCaptured() {
        Result r = result(
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1", "CI;" + OUT,
                "TE;2", "TN;t", "RI;1", "CI;" + OUT,
                "RT;EXCEPTION", "RE;{\"class\":\"java.lang.RuntimeException\"}");
        ParsedCall c = new RecordParser().parse(r).get(0);
        assertEquals("EXCEPTION", c.returnType());
        assertNotNull(c.returnJson());
    }

    @Test
    void argsExitIsCapturedSeparately() {
        Result r = result(
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1", "CI;" + OUT,
                "AR;{\"v\":1}",
                "TE;2", "TN;t", "RI;1", "CI;" + OUT,
                "RT;VOID", "AX;{\"v\":2}");
        ParsedCall c = new RecordParser().parse(r).get(0);
        assertEquals("{\"v\":1}", c.argsJson());
        assertEquals("{\"v\":2}", c.argsExitJson());
    }

    @Test
    void thisAsFullJsonGoesIntoThisJsonNotRef() {
        Result r = result(
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1", "CI;" + OUT,
                "TI;{\"__meta__\":{\"id\":99,\"class\":\"X\",\"hash\":\"a\"}}",
                "TE;2", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID");
        ParsedCall c = new RecordParser().parse(r).get(0);
        assertNull(c.thisIdRef());
        assertNotNull(c.thisJson());
    }

    @Test
    void staticMethodHasNeitherThisRefNorJson() {
        Result r = result(
                "TS;1", "MS;F.staticThing()V", "TN;t", "RI;1", "CL;1", "CI;" + OUT,
                "AR;{}",
                "TE;2", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID");
        ParsedCall c = new RecordParser().parse(r).get(0);
        assertNull(c.thisIdRef());
        assertNull(c.thisJson());
    }

    @Test
    void unmatchedTeWithoutOpenCallIsIgnored() {
        // ME with a callId that was never seen as MS — orphan, drop silently.
        Result r = result("TE;1", "TN;t", "RI;1", "CI;" + OUT);
        assertEquals(0, new RecordParser().parse(r).size());
    }

    @Test
    void unmatchedMsWithoutTeStaysOpenAcrossBatches() {
        // Truncated stream — agent crashed mid-call. The MS lives in the
        // open-calls map until eviction (not implemented yet); it does not
        // surface as a completed call.
        RecordParser parser = new RecordParser();
        Result r = result(
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1", "CI;" + OUT);
        assertEquals(0, parser.parse(r).size());
    }

    @Test
    void agentOrderTeBeforeRtAttachesReturnToCorrectCall() {
        // Real wire order from RequestRecorder.recordExit():
        //   METHOD_END, RETURN, ARGUMENTS_EXIT
        // i.e. TE comes BEFORE the call's own RT/RE/AX. The parser's
        // exit-context state holds across these tags until next TS/TE.
        Result r = result(
                "TS;1000", "MS;F.f()I", "TN;t", "RI;1", "CL;1", "CI;" + OUT,
                "AR;{}",
                "TE;2000", "TN;t", "RI;1", "CI;" + OUT,
                "RT;VALUE", "RE;42",
                "AX;{}");
        List<ParsedCall> calls = new RecordParser().parse(r);
        assertEquals(1, calls.size());
        ParsedCall c = calls.get(0);
        assertEquals(2000L, c.tsOutMillis());
        assertEquals("VALUE", c.returnType());
        assertEquals("42", c.returnJson());
        assertEquals("{}", c.argsExitJson());
    }

    @Test
    void agentOrderNestedDoesNotLeakReturnToParent() {
        // Outer A calls inner B. Inner exits (TE first, then RT/RE), then outer exits.
        Result r = result(
                "TS;1000", "MS;A.outer()V", "TN;t", "RI;1", "CL;10",
                "CI;" + OUT, "AR;{}",
                "TS;1100", "MS;B.inner()I", "TN;t", "RI;1", "CL;20",
                "CI;" + INN, "PI;" + OUT, "AR;{}",
                "TE;1200", "TN;t", "RI;1", "CI;" + INN,
                "RT;VALUE", "RE;7",
                "TE;1300", "TN;t", "RI;1", "CI;" + OUT,
                "RT;VOID");
        List<ParsedCall> calls = new RecordParser().parse(r);
        assertEquals(2, calls.size());
        ParsedCall inner = calls.get(0);
        assertEquals("B.inner()I", inner.signature());
        assertEquals("VALUE", inner.returnType());
        assertEquals("7", inner.returnJson());
        ParsedCall outer = calls.get(1);
        assertEquals("A.outer()V", outer.signature());
        assertEquals("VOID", outer.returnType());
        assertNull(outer.returnJson());
    }

    @Test
    void versionBannerIsIgnored() {
        Result r = result(
                "VR;1.3",
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1", "CI;" + OUT,
                "TE;2", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID");
        assertEquals(1, new RecordParser().parse(r).size());
    }

    // ============================================================
    //  Bug fix: cross-batch pairing — the central reason for this
    //  parser refactor. The OLD parser would silently drop the call
    //  whose MS landed in batch N and ME landed in batch N+1.
    // ============================================================

    @Test
    void msInOneBatchAndMeInAnotherStillPair() {
        RecordParser parser = new RecordParser();

        // Batch 1: just the MS half.
        Result batch1 = result(
                "TS;1000", "MS;F.f()V", "TN;t", "RI;1", "CL;1",
                "CI;" + OUT, "AR;{}");
        assertEquals(0, parser.parse(batch1).size(),
                "MS without ME yields no completed call yet");

        // Batch 2: the ME half. Old parser dropped this on the floor.
        Result batch2 = result(
                "TE;2000", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID");
        List<ParsedCall> completed = parser.parse(batch2);

        assertEquals(1, completed.size(), "MS↔ME must pair across batches");
        ParsedCall c = completed.get(0);
        assertEquals(OUT, c.callId());
        assertEquals(1000L, c.tsInMillis());
        assertEquals(2000L, c.tsOutMillis());
    }

    @Test
    void interleavedThreadsInOneBatchPairCorrectlyByCallId() {
        // Two concurrent calls on different threads, lines interleaved
        // (which is what happens in production — the global RecordBuffer
        // is drained in time order, mixing threads). The old stack-based
        // parser would mispair these; UUID-keyed pairing handles it.
        Result r = result(
                "TS;1000", "MS;A.a()V", "TN;t1", "RI;1", "CL;1", "CI;" + OUT,
                "TS;1010", "MS;B.b()V", "TN;t2", "RI;2", "CL;2", "CI;" + INN,
                "TE;1020", "TN;t2", "RI;2", "CI;" + INN, "RT;VOID",
                "TE;1030", "TN;t1", "RI;1", "CI;" + OUT, "RT;VOID");

        List<ParsedCall> calls = new RecordParser().parse(r);

        assertEquals(2, calls.size());
        // First completed: t2's call (its TE came first).
        ParsedCall first = calls.get(0);
        assertEquals(INN, first.callId());
        assertEquals("t2", first.threadName());
        assertEquals(1010L, first.tsInMillis());
        assertEquals(1020L, first.tsOutMillis());
        // Second completed: t1's call.
        ParsedCall second = calls.get(1);
        assertEquals(OUT, second.callId());
        assertEquals("t1", second.threadName());
        assertEquals(1000L, second.tsInMillis());
        assertEquals(1030L, second.tsOutMillis());
    }

    @Test
    void duplicateCiInEntryBlockIsIgnoredNotLeaked() {
        // Defensive (B-02 in KNOWN_BUGS.md): a malformed MS block with two
        // CI tags must NOT leak the first builder under a stale key. The
        // second CI is ignored; only the first key is in openCalls; pairing
        // works on that one.
        Result r = result(
                "TS;1000", "MS;F.f()V", "TN;t", "RI;1", "CL;1",
                "CI;" + OUT,
                "CI;" + INN,             // duplicate — must be ignored
                "TE;2000", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID");

        List<ParsedCall> calls = new RecordParser().parse(r);

        assertEquals(1, calls.size());
        assertEquals(OUT, calls.get(0).callId(),
                "first CI wins; second is ignored");

        // Verify pairing succeeded by going through the second-CI's id too:
        // a TE for INN should now be orphan (not in openCalls) — confirming
        // the second CI never made it into the map.
        Result orphan = result(
                "TE;3000", "TN;t", "RI;1", "CI;" + INN, "RT;VOID");
        assertEquals(0, new RecordParser().parse(orphan).size());
    }

    @Test
    void orphanMeIsDroppedWhenNoMatchingMsExists() {
        // RequestRecorder's failed-entry contract guarantees a failed entry
        // suppresses its matching exit, but defense-in-depth: if we ever see
        // a stray ME (callId not in openCalls), it must be dropped silently.
        Result r = result(
                "TE;100", "TN;t", "RI;1", "CI;" + OTH, "RT;VOID");
        assertEquals(0, new RecordParser().parse(r).size());
    }

    @Test
    void staleOpenCallIsEvictedAfterTtl() {
        // L-01: an MS without a matching ME (agent crash mid-call) used to
        // sit in openCalls forever. The TTL sweep at the end of parse() must
        // reap it once admission age exceeds the 10-minute TTL. Sweep is
        // throttled to once per minute, so we advance the clock past both.
        long[] now = { 0L };
        RecordParser parser = new RecordParser(() -> now[0]);

        now[0] = 0L;
        parser.parse(result(
                "TS;0", "MS;F.f()V", "TN;t", "RI;1", "CL;1", "CI;" + OUT, "AR;{}"));
        assertEquals(1, parser.openCallCount(),
                "MS without ME should leave one builder in openCalls");

        // Inside TTL — sweep runs but evicts nothing.
        now[0] = 5 * 60 * 1000L;
        parser.parse(result());
        assertEquals(1, parser.openCallCount(),
                "entry under TTL must not be evicted");

        // Past TTL, past sweep interval — eviction fires.
        now[0] = 11 * 60 * 1000L;
        parser.parse(result());
        assertEquals(0, parser.openCallCount(),
                "entry older than TTL must be evicted by the sweep");

        // A late ME for the evicted call is now an orphan, dropped silently.
        List<ParsedCall> late = parser.parse(result(
                "TE;12000", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID"));
        assertEquals(0, late.size(),
                "ME for a previously-evicted call must be treated as orphan");
    }

    @Test
    void sqTagAttachesSeqByCallId() {
        Result r = result(
                "TS;1000", "MS;A()V", "TN;t", "RI;1", "CL;1",
                "CI;" + OUT,
                "SQ;" + OUT + "|7",
                "TE;1100", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID");

        ParsedCall c = new RecordParser().parse(r).get(0);
        assertEquals(7L, c.seq());
    }

    @Test
    void sqTagRoutesByCallIdNotAdjacency() {
        // Two MS blocks interleaved with their SQ records.
        // SQ;OUT|10 must land on OUT's call, SQ;INN|11 on INN's, regardless
        // of which one came first in the stream.
        Result r = result(
                "TS;1000", "MS;A()V", "TN;t", "RI;1", "CL;1", "CI;" + OUT,
                "TS;1100", "MS;B()V", "TN;t", "RI;1", "CL;2", "CI;" + INN, "PI;" + OUT,
                // The two SQ records arrive in the "wrong" order vs their MSs.
                "SQ;" + INN + "|11",
                "SQ;" + OUT + "|10",
                "TE;1200", "TN;t", "RI;1", "CI;" + INN, "RT;VOID",
                "TE;1300", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID");

        List<ParsedCall> calls = new RecordParser().parse(r);
        // Order: inner emitted first (TE for INN) then outer.
        assertEquals(11L, calls.get(0).seq(), "B's seq must be 11");
        assertEquals(10L, calls.get(1).seq(), "A's seq must be 10");
    }

    @Test
    void sqTagAbsentLeavesSeqAtZero() {
        // No SQ in the stream — seq stays at the default 0.
        Result r = result(
                "TS;1000", "MS;A()V", "TN;t", "RI;1", "CL;1", "CI;" + OUT,
                "TE;1100", "TN;t", "RI;1", "CI;" + OUT, "RT;VOID");

        assertEquals(0L, new RecordParser().parse(r).get(0).seq());
    }

    private static Result result(String... lines) {
        return new Result("test-thread", List.of(lines));
    }
}
