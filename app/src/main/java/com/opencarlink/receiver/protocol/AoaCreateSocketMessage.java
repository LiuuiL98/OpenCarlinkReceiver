package com.opencarlink.receiver.protocol;

/**
 * Parser for the create-socket payload delivered on AOA channel 1.
 *
 * Static analysis of the official ICCOA receiver shows a 10-byte minimum and
 * fields at offsets 0, 4, 8 and 9. Socket type 1 selects the server-socket
 * path and type 2 selects the client-socket path. This parser is pure Java
 * and never opens sockets.
 */
public final class AoaCreateSocketMessage {
    public static final int MIN_LENGTH = 10;
    public static final int SOCKET_TYPE_SERVER = 1;
    public static final int SOCKET_TYPE_CLIENT = 2;
    public static final int SERVER_CHANNEL_ID_SENTINEL = -1;

    public final int port;
    public final int channelId;
    public final int socketType;
    public final int channelMessageType;

    private AoaCreateSocketMessage(int port, int channelId,
                                   int socketType, int channelMessageType) {
        this.port = port;
        this.channelId = channelId;
        this.socketType = socketType;
        this.channelMessageType = channelMessageType;
    }

    public boolean isServerSocketRequest() {
        return socketType == SOCKET_TYPE_SERVER;
    }

    public boolean isClientSocketRequest() {
        return socketType == SOCKET_TYPE_CLIENT;
    }

    /**
     * Parse only the declared payload range. The caller must pass the logical
     * body length, not the larger wire-read length used for bodies shorter
     * than eight bytes.
     */
    public static AoaCreateSocketMessage parse(byte[] body, int declaredLength) {
        if (body == null) throw new IllegalArgumentException("body == null");
        if (declaredLength < MIN_LENGTH) {
            throw new IllegalArgumentException("create-socket payload shorter than 10 bytes");
        }
        if (declaredLength > body.length) {
            throw new IllegalArgumentException("declared length exceeds available payload");
        }
        int port = (int) AoaTransportHeaderProbe.wordBigEndian(body, 0);
        int channelId = (int) AoaTransportHeaderProbe.wordBigEndian(body, 4);
        int socketType = body[8] & 0xff;
        int channelMessageType = body[9] & 0xff;
        return new AoaCreateSocketMessage(port, channelId, socketType, channelMessageType);
    }
}
