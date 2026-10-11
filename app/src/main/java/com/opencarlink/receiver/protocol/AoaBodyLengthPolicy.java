package com.opencarlink.receiver.protocol;

/**
 * Local allocation policy for untrusted AOA body lengths.
 *
 * The official implementation accepts lengths up to 64 MiB, but that limit
 * is too permissive for an initial receiver. This class deliberately applies
 * a smaller configurable cap before allocation and keeps validation separate
 * from USB I/O.
 */
public final class AoaBodyLengthPolicy {
    /** Conservative initial cap; raise only after capturing real valid traffic. */
    public static final int DEFAULT_MAX_BODY_LENGTH = 1024 * 1024;

    private final int maxBodyLength;

    public AoaBodyLengthPolicy() {
        this(DEFAULT_MAX_BODY_LENGTH);
    }

    public AoaBodyLengthPolicy(int maxBodyLength) {
        if (maxBodyLength < AoaTransportHeaderProbe.HEADER_LENGTH
                || maxBodyLength > AoaTransportHeaderProbe.OFFICIAL_MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("local cap must be between 8 bytes and official maximum");
        }
        this.maxBodyLength = maxBodyLength;
    }

    public int getMaxBodyLength() {
        return maxBodyLength;
    }

    /**
     * Validate a decoded declaration before allocating. Returns the official
     * requested read size (max(declared length, 8)) if within local policy.
     */
    public int validateAndGetRequestedReadLength(AoaTransportHeaderProbe.Header header) {
        if (header == null) throw new IllegalArgumentException("header == null");
        if (header.bodyLength < AoaTransportHeaderProbe.MIN_BODY_LENGTH
                || header.bodyLength > AoaTransportHeaderProbe.OFFICIAL_MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("body length outside official bounds");
        }
        if (header.bodyLength > maxBodyLength) {
            throw new IllegalArgumentException("body length exceeds local allocation cap: "
                    + header.bodyLength + " > " + maxBodyLength);
        }
        return header.requestedBodyReadLength();
    }

    /** Reject a body buffer that does not contain the declared logical body. */
    public void validateAvailableBody(byte[] body, int declaredLength, int returnedLength) {
        if (body == null) throw new IllegalArgumentException("body == null");
        if (declaredLength < AoaTransportHeaderProbe.MIN_BODY_LENGTH
                || declaredLength > maxBodyLength) {
            throw new IllegalArgumentException("declared body length outside local policy");
        }
        if (returnedLength < declaredLength || returnedLength > body.length) {
            throw new IllegalArgumentException("body truncated or returned length inconsistent");
        }
    }
}
