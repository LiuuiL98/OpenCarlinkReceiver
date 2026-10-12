package com.opencarlink.receiver.protocol;

/**
 * Encodes the 8-byte AOA outer header plus the 10-byte create-socket body.
 *
 * The outer channel ID and body layout are grounded in static reverse engineering.
 * The RTSP/UIBC outgoing socket-type values are reverse-engineered; messageType is
 * caller-supplied because the exact response semantics still require hardware validation.
 */
public final class AoaCreateSocketFrameEncoder {
    public static final int OUTER_CREATE_SOCKET_CHANNEL_ID = 1;
    public static final int BODY_LENGTH = 10;
    public static final int SOCKET_TYPE_RTSP = 3;
    public static final int SOCKET_TYPE_UIBC = 4;

    private AoaCreateSocketFrameEncoder() { }

    public static byte[] encode(int port, int assignedChannelId, int socketType, int messageType) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
        if (assignedChannelId < 1 || assignedChannelId > 240) {
            throw new IllegalArgumentException("assigned channel ID must be 1..240");
        }
        if (socketType < 0 || socketType > 255) throw new IllegalArgumentException("socketType out of range");
        if (messageType < 0 || messageType > 255) throw new IllegalArgumentException("messageType out of range");

        byte[] frame = new byte[8 + BODY_LENGTH];
        writeU32be(frame, 0, OUTER_CREATE_SOCKET_CHANNEL_ID);
        writeU32be(frame, 4, BODY_LENGTH);
        writeU32be(frame, 8, port);
        writeU32be(frame, 12, assignedChannelId);
        frame[16] = (byte) socketType;
        frame[17] = (byte) messageType;
        return frame;
    }

    private static void writeU32be(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 24) & 0xff);
        target[offset + 1] = (byte) ((value >>> 16) & 0xff);
        target[offset + 2] = (byte) ((value >>> 8) & 0xff);
        target[offset + 3] = (byte) (value & 0xff);
    }
}
