package com.github.gabert.deepflow.recorder.record;

import java.util.UUID;

/**
 * Per-call observation-order sequence number.
 *
 * <p>Emitted by the agent immediately after the {@link MethodStartRecord} of
 * a traced method, when {@code emit_tags} includes {@code SQ}. The
 * <em>seq</em> value is a strictly monotonic ordinal scoped to the agent
 * run — i.e. it grows from 0 across every traced method entry of the JVM,
 * regardless of thread or request. It is the canonical ordering primitive
 * for a request's call list (sub-millisecond ties on {@code ts_in} are
 * disambiguated by {@code seq}).</p>
 *
 * <p>The {@code callId} field links this record to its matching
 * {@code MethodStartRecord} regardless of the on-wire position; the
 * processor pairs by {@code callId} just as it does for MS↔ME.</p>
 *
 * <p>Payload layout: {@code [callId:16][seq:8]} — fixed 24 bytes.</p>
 */
public record SequenceRecord(UUID callId, long seq) implements TraceRecord {

    public static final byte TYPE = RecordType.SEQUENCE;

    @Override
    public byte typeByte() {
        return TYPE;
    }

    @Override
    public byte[] payloadBytes() {
        byte[] payload = new byte[RecordType.UUID_SIZE + RecordType.SEQ_SIZE];
        int pos = BinaryUtil.putUuid(payload, 0, callId);
        BinaryUtil.putLong(payload, pos, seq);
        return payload;
    }

    public static SequenceRecord parse(byte[] payload) {
        UUID callId = BinaryUtil.getNullableUuid(payload, 0);
        long seq = BinaryUtil.getLong(payload, RecordType.UUID_SIZE);
        return new SequenceRecord(callId, seq);
    }
}
