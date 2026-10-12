package com.opencarlink.receiver.protocol;

/**
 * Read-only decoder for the official receiver's create-socket body.
 *
 * Static DEX inspection shows a 10-byte minimum and four fields at offsets
 * 0, 4, 8 and 9. This parser does not open sockets or trust the outer header.
 */
public final class AoaCreateSocketRecord {
    public static final int MIN_RECORD_LENGTH = 10;

/** Official DEX branch: socketType 1 constructs a local ServerSocket(port). */
public static final int SOCKET_TYPE_SERVER = 1;

/** The official server-socket branch expects channelId == -1. */
public static final int SERVER_SOCKET_CHANNEL_ID = -1;

    public final int port;
    public final int channelId;
    public final int socketType;
    public final int channelMessageType;

    private AoaCreateSocketRecord(int port, int channelId, int socketType, int channelMessageType) {
        this.port = port;
        this.channelId = channelId;
        this.socketType = socketType;
        this.channelMessageType = channelMessageType;
    }

    /**
     * Decode only the first {@code returnedLength} bytes. The caller must pass
     * a complete body as returned by the AOA framing layer, not an arbitrary
     * raw USB chunk.
     */
    public static AoaCreateSocketRecord decode(byte[] data, int returnedLength) {
        if (data == null) throw new IllegalArgumentException("data == null");
        if (returnedLength < MIN_RECORD_LENGTH || returnedLength > data.length) {
            throw new IllegalArgumentException("create-socket record must contain at least 10 available bytes");
        }
        return new AoaCreateSocketRecord(
                (int) readUnsignedIntBigEndian(data, 0),
                (int) readUnsignedIntBigEndian(data, 4),
                data[8] & 0xff,
                data[9] & 0xff);
    }

    /** True only for the socketType branch confirmed to create ServerSocket(port). */
    public boolean requestsServerSocket() {
        return socketType == SOCKET_TYPE_SERVER;
    }

    private static long readUnsignedIntBigEndian(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (long) (data[offset + 3] & 0xff);
    }
}
