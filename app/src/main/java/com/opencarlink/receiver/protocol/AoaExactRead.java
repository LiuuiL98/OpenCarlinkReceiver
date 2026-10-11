package com.opencarlink.receiver.protocol;

import java.io.IOException;

/**
 * Pure-Java model of AOAHostSetup.bulkTransferIn's exact-length contract.
 *
 * This helper is not wired to Android USB APIs. It makes the wrapper's
 * accumulation and short-read behavior testable with a fake chunk reader.
 */
public final class AoaExactRead {
    public static final int MAX_CHUNK_LENGTH = 16 * 1024;

    public interface ChunkReader {
        /** Return bytes read, 0 for no data, or a negative value for failure. */
        int read(byte[] target, int offset, int length) throws IOException;
    }

    private AoaExactRead() {}

    /**
     * Fill exactly {@code requestedLength} bytes unless the first read returns
     * zero (which mirrors the official helper's zero-byte result). A zero read
     * after partial progress is a short-read error. The reader owns transport
     * retry behavior and may throw IOException on a terminal failure.
     *
     * @return requestedLength when complete, zero if no bytes were received
     * @throws IOException if a read fails or ends after partial progress
     */
    public static int readExactly(byte[] target, int requestedLength, ChunkReader reader)
            throws IOException {
        if (target == null) throw new IllegalArgumentException("target == null");
        if (reader == null) throw new IllegalArgumentException("reader == null");
        if (requestedLength < 0 || requestedLength > target.length) {
            throw new IllegalArgumentException("requested length outside target buffer");
        }
        if (requestedLength == 0) return 0;

        int total = 0;
        while (total < requestedLength) {
            int chunkLength = Math.min(requestedLength - total, MAX_CHUNK_LENGTH);
            int count = reader.read(target, total, chunkLength);
            if (count < 0) {
                throw new IOException("read failed after " + total + " of " + requestedLength + " bytes");
            }
            if (count == 0) {
                if (total == 0) return 0;
                throw new IOException("short read: expected " + requestedLength + " bytes, received " + total);
            }
            if (count > chunkLength) {
                throw new IOException("reader returned " + count + " bytes for " + chunkLength + "-byte request");
            }
            total += count;
        }
        return total;
    }
}
