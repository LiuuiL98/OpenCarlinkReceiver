package com.opencarlink.receiver.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Incremental parser for the UCar stream framing: a 20-byte header followed by
 * a body, with total frame length stored as a big-endian int at bytes 0..3.
 *
 * This parser is deliberately passive: it never sends data. Invalid candidate
 * headers are skipped one byte at a time so a later valid header can be found.
 * The framing assumptions must still be verified against a genuine full frame.
 */
public final class UCarStreamParser {
    public static final int DEFAULT_MAX_FRAME_LENGTH = 1024 * 1024;

    private final int maxFrameLength;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    private long discardedBytes;
    private long invalidLengthCandidates;
    private long invalidChecksumCandidates;
    private long parsedFrames;

    public UCarStreamParser() {
        this(DEFAULT_MAX_FRAME_LENGTH);
    }

    public UCarStreamParser(int maxFrameLength) {
        if (maxFrameLength < UCarHeaderChecksum.HEADER_MIN_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must be at least 20");
        }
        this.maxFrameLength = maxFrameLength;
    }

    /**
     * Adds an arbitrary transport chunk and returns all complete valid frames.
     * The returned frames are independent copies.
     */
    public synchronized List<byte[]> feed(byte[] chunk) {
        if (chunk == null) {
            throw new NullPointerException("chunk");
        }
        if (chunk.length == 0) {
            return Collections.emptyList();
        }

        pending.write(chunk, 0, chunk.length);
        byte[] data = pending.toByteArray();
        int cursor = 0;
        List<byte[]> frames = new ArrayList<>();

        while (data.length - cursor >= UCarHeaderChecksum.HEADER_MIN_LENGTH) {
            int totalLength = readInt32BigEndian(data, cursor);
            if (totalLength < UCarHeaderChecksum.HEADER_MIN_LENGTH
                    || totalLength > maxFrameLength) {
                invalidLengthCandidates++;
                cursor++;
                discardedBytes++;
                continue;
            }

            if (data.length - cursor < UCarHeaderChecksum.HEADER_MIN_LENGTH) {
                break;
            }

            byte[] header = Arrays.copyOfRange(data, cursor,
                    cursor + UCarHeaderChecksum.HEADER_MIN_LENGTH);
            if (!UCarHeaderChecksum.isChecksumValid(header)) {
                invalidChecksumCandidates++;
                cursor++;
                discardedBytes++;
                continue;
            }

            if (data.length - cursor < totalLength) {
                break;
            }

            frames.add(Arrays.copyOfRange(data, cursor, cursor + totalLength));
            parsedFrames++;
            cursor += totalLength;
        }

        if (cursor > 0) {
            pending.reset();
            pending.write(data, cursor, data.length - cursor);
        }

        // Bound retained garbage when no plausible header has been found.
        // Preserve the last 19 bytes because they may be the start of a split header.
        if (pending.size() > maxFrameLength) {
            byte[] retained = pending.toByteArray();
            int keep = Math.min(UCarHeaderChecksum.HEADER_MIN_LENGTH - 1, retained.length);
            pending.reset();
            pending.write(retained, retained.length - keep, keep);
            discardedBytes += retained.length - keep;
        }
        return frames;
    }

    public synchronized int getPendingByteCount() {
        return pending.size();
    }

    public synchronized long getDiscardedBytes() {
        return discardedBytes;
    }

    public synchronized long getInvalidLengthCandidates() {
        return invalidLengthCandidates;
    }

    public synchronized long getInvalidChecksumCandidates() {
        return invalidChecksumCandidates;
    }

    public synchronized long getParsedFrames() {
        return parsedFrames;
    }

    public synchronized void reset() {
        pending.reset();
        discardedBytes = 0;
        invalidLengthCandidates = 0;
        invalidChecksumCandidates = 0;
        parsedFrames = 0;
    }

    private static int readInt32BigEndian(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
