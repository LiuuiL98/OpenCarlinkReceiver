package com.opencarlink.receiver.protocol;

import java.io.IOException;

/**
 * Pure-Java safety policy for allocating and validating an AOA record body.
 * USB access is deliberately kept outside this class.
 */
public final class AoaBodyReadPolicy {
    private AoaBodyReadPolicy() {}

    /**
     * Compute the official requested read length after enforcing the receiver's
     * chosen local cap. The official helper requests at least 8 bytes even
     * when the declared body is shorter.
     */
    public static int requestedReadLength(AoaTransportHeaderProbe.Header header,
                                          int localBodyLimit) {
        if (header == null) throw new IllegalArgumentException("header == null");
        if (localBodyLimit < AoaTransportHeaderProbe.MIN_BODY_LENGTH
                || localBodyLimit > AoaTransportHeaderProbe.OFFICIAL_MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("local body limit out of range: " + localBodyLimit);
        }
        if (header.bodyLength < AoaTransportHeaderProbe.MIN_BODY_LENGTH
                || header.bodyLength > localBodyLimit) {
            throw new IllegalArgumentException("declared body length exceeds local limit");
        }
        return Math.max(header.bodyLength, AoaTransportHeaderProbe.HEADER_LENGTH);
    }

    /** Allocate only after the caller has selected and checked a local cap. */
    public static byte[] allocate(AoaTransportHeaderProbe.Header header, int localBodyLimit) {
        return new byte[requestedReadLength(header, localBodyLimit)];
    }

    /**
     * Verify a helper's return value before dispatch. The official Java helper
     * returns zero for no data and throws for a nonzero short read; neither
     * outcome is a complete body.
     */
    public static void requireCompleteRead(int requestedLength, int returnedLength)
            throws IOException {
        if (requestedLength < 1) throw new IllegalArgumentException("requestedLength must be positive");
        if (returnedLength != requestedLength) {
            throw new IOException("incomplete AOA body read: expected " + requestedLength
                    + " bytes, received " + returnedLength);
        }
    }
}
