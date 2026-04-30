package com.github.gabert.deepflow.recorder.record;

import java.util.UUID;

/**
 * Big-endian read/write helpers for the wire format.
 *
 * <p>The agent's record format is defined as fixed-width big-endian
 * integers with no native dependencies. These helpers are the single
 * source of truth for that encoding — used by both the writer and the
 * reader so the two cannot drift independently.</p>
 */
public final class BinaryUtil {

    private BinaryUtil() {}

    // --- Writers (return next position) ---

    public static int putShort(byte[] buf, int pos, short value) {
        buf[pos]     = (byte) (value >>> 8);
        buf[pos + 1] = (byte) value;
        return pos + 2;
    }

    public static int putInt(byte[] buf, int pos, int value) {
        buf[pos]     = (byte) (value >>> 24);
        buf[pos + 1] = (byte) (value >>> 16);
        buf[pos + 2] = (byte) (value >>> 8);
        buf[pos + 3] = (byte) value;
        return pos + 4;
    }

    public static int putLong(byte[] buf, int pos, long value) {
        buf[pos]     = (byte) (value >>> 56);
        buf[pos + 1] = (byte) (value >>> 48);
        buf[pos + 2] = (byte) (value >>> 40);
        buf[pos + 3] = (byte) (value >>> 32);
        buf[pos + 4] = (byte) (value >>> 24);
        buf[pos + 5] = (byte) (value >>> 16);
        buf[pos + 6] = (byte) (value >>> 8);
        buf[pos + 7] = (byte) value;
        return pos + 8;
    }

    // --- Readers ---

    public static int getShort(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 8)
             | (buf[pos + 1] & 0xFF);
    }

    public static int getInt(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 24)
             | ((buf[pos + 1] & 0xFF) << 16)
             | ((buf[pos + 2] & 0xFF) << 8)
             | (buf[pos + 3] & 0xFF);
    }

    public static long getLong(byte[] buf, int pos) {
        return ((long)(buf[pos] & 0xFF) << 56)
             | ((long)(buf[pos + 1] & 0xFF) << 48)
             | ((long)(buf[pos + 2] & 0xFF) << 40)
             | ((long)(buf[pos + 3] & 0xFF) << 32)
             | ((long)(buf[pos + 4] & 0xFF) << 24)
             | ((long)(buf[pos + 5] & 0xFF) << 16)
             | ((long)(buf[pos + 6] & 0xFF) << 8)
             | ((long)(buf[pos + 7] & 0xFF));
    }

    // --- UUID (16 bytes, big-endian: 8-byte MSB then 8-byte LSB) ---

    /**
     * Writes a UUID as two big-endian longs (MSB then LSB). A {@code null}
     * uuid is encoded as all-zero bytes — the sentinel reserved for "no UUID
     * present." Pair with {@link #getNullableUuid(byte[], int)} to decode the
     * sentinel back to {@code null}.
     */
    public static int putUuid(byte[] buf, int pos, UUID uuid) {
        long msb = uuid != null ? uuid.getMostSignificantBits() : 0L;
        long lsb = uuid != null ? uuid.getLeastSignificantBits() : 0L;
        pos = putLong(buf, pos, msb);
        return putLong(buf, pos, lsb);
    }

    /** Reads a UUID. Returns the all-zero UUID literally if that is what is on the wire. */
    public static UUID getUuid(byte[] buf, int pos) {
        long msb = getLong(buf, pos);
        long lsb = getLong(buf, pos + 8);
        return new UUID(msb, lsb);
    }

    /** Reads a UUID. Returns {@code null} when the encoded bytes are the all-zero sentinel. */
    public static UUID getNullableUuid(byte[] buf, int pos) {
        long msb = getLong(buf, pos);
        long lsb = getLong(buf, pos + 8);
        if (msb == 0L && lsb == 0L) return null;
        return new UUID(msb, lsb);
    }

    // --- Byte-array concat ---

    /** Concatenates byte arrays, skipping any null entries. */
    public static byte[] concat(byte[]... parts) {
        int totalLen = 0;
        for (byte[] part : parts) {
            if (part != null) totalLen += part.length;
        }
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] part : parts) {
            if (part != null) {
                System.arraycopy(part, 0, result, pos, part.length);
                pos += part.length;
            }
        }
        return result;
    }
}
