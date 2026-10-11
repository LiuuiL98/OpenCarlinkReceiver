package com.opencarlink.receiver.protocol;

/**
 * Checksum helpers for the 20-byte UCar message header.
 *
 * Header layout relevant to checksum validation:
 * - bytes 0..17: included in CRC-16/ARC calculation
 * - bytes 18..19: stored checksum, unsigned big-endian
 */
public final class UCarHeaderChecksum {
    public static final int HEADER_MIN_LENGTH = 20;
    public static final int CHECKSUM_OFFSET = 18;
    public static final int CHECKSUM_INPUT_LENGTH = 18;

    private UCarHeaderChecksum() {
    }

    /**
     * Returns the stored header checksum as an unsigned 16-bit integer.
     */
    public static int readStoredChecksum(byte[] header) {
        requireHeader(header);
        return ((header[CHECKSUM_OFFSET] & 0xFF) << 8)
                | (header[CHECKSUM_OFFSET + 1] & 0xFF);
    }

    /**
     * Computes CRC-16/ARC over header bytes 0..17.
     */
    public static int computeChecksum(byte[] header) {
        requireHeader(header);
        return Crc16Arc.checksum(header, 0, CHECKSUM_INPUT_LENGTH);
    }

    /**
     * Validates the stored big-endian checksum against bytes 0..17.
     */
    public static boolean isChecksumValid(byte[] header) {
        return readStoredChecksum(header) == computeChecksum(header);
    }

    private static void requireHeader(byte[] header) {
        if (header == null) {
            throw new NullPointerException("header");
        }
        if (header.length < HEADER_MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "UCar header must be at least " + HEADER_MIN_LENGTH + " bytes; got " + header.length);
        }
    }
}
