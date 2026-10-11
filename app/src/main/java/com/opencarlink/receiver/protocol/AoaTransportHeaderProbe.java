package com.opencarlink.receiver.protocol;

/**
 * Diagnostic helpers for the official ICCOA AOA transport's 8-byte prefix.
 *
 * Static DEX inspection establishes two big-endian 32-bit words: channel ID
 * (1..240, where 1 denotes a create-socket record) and declared body length
 * (1..64 MiB). This class decodes the header only; it does not allocate the
 * body or assume a raw USB Bulk IN return is a complete logical record.
 */
public final class AoaTransportHeaderProbe {
    public static final int HEADER_LENGTH = 8;
    public static final int MIN_CHANNEL_ID = 1;
    public static final int MAX_CHANNEL_ID = 240;
    public static final int CREATE_SOCKET_CHANNEL_ID = 1;
    public static final int MIN_BODY_LENGTH = 1;
    public static final int OFFICIAL_MAX_BODY_LENGTH = 64 * 1024 * 1024;

    private AoaTransportHeaderProbe() {}

    public static final class Header {
        public final int channelId;
        public final int bodyLength;

        private Header(int channelId, int bodyLength) {
            this.channelId = channelId;
            this.bodyLength = bodyLength;
        }

        /** The official code requests at least 8 bytes even for a shorter declared body. */
        public int requestedBodyReadLength() {
            return Math.max(bodyLength, HEADER_LENGTH);
        }

        public boolean isCreateSocketRecord() {
            return channelId == CREATE_SOCKET_CHANNEL_ID;
        }
    }

    public static boolean isAvailable(byte[] data, int returnedLength) {
        return data != null && data.length >= HEADER_LENGTH && returnedLength >= HEADER_LENGTH;
    }

    /**
     * Decode one already-reassembled 8-byte AOA header. The caller must not
     * use this on arbitrary raw USB chunks unless the header boundary is known.
     */
    public static Header decode(byte[] data, int returnedLength) {
        if (!isAvailable(data, returnedLength)) {
            throw new IllegalArgumentException("need a complete 8-byte AOA header");
        }
        long channelWord = wordBigEndian(data, 0);
        long lengthWord = wordBigEndian(data, 4);
        if (channelWord < MIN_CHANNEL_ID || channelWord > MAX_CHANNEL_ID) {
            throw new IllegalArgumentException("channel ID out of range: " + channelWord);
        }
        if (lengthWord < MIN_BODY_LENGTH || lengthWord > OFFICIAL_MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("body length out of range: " + lengthWord);
        }
        return new Header((int) channelWord, (int) lengthWord);
    }

    public static long wordBigEndian(byte[] data, int offset) {
        checkRange(data, offset);
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (long) (data[offset + 3] & 0xff);
    }

    /** Kept for diagnostics and test vectors; this is not the official decoder. */
    public static long wordLittleEndian(byte[] data, int offset) {
        checkRange(data, offset);
        return ((long) (data[offset + 3] & 0xff) << 24)
                | ((long) (data[offset + 2] & 0xff) << 16)
                | ((long) (data[offset + 1] & 0xff) << 8)
                | (long) (data[offset] & 0xff);
    }

    private static void checkRange(byte[] data, int offset) {
        if (data == null) throw new IllegalArgumentException("data == null");
        if (offset < 0 || offset > data.length - 4) {
            throw new IllegalArgumentException("need four bytes at offset " + offset);
        }
    }
}
