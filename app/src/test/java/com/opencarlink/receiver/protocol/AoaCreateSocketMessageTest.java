package com.opencarlink.receiver.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class AoaCreateSocketMessageTest {
    @Test public void parsesBigEndianPortAndChannelAndUnsignedTypeBytes() {
        byte[] body = new byte[] {
                0, 0, 0x1c, 0x39, // port 7225
                0, 0, 0, 7,        // channel ID 7
                (byte) 0xfe, (byte) 0x80
        };
        AoaCreateSocketMessage parsed = AoaCreateSocketMessage.parse(body, body.length);
        assertEquals(7225, parsed.port);
        assertEquals(7, parsed.channelId);
        assertEquals(254, parsed.socketType);
        assertEquals(128, parsed.channelMessageType);
        assertFalse(parsed.isServerSocketRequest());
        assertFalse(parsed.isClientSocketRequest());
    }

    @Test public void classifiesServerAndClientSocketTypes() {
        byte[] serverBody = createSocketBody(7225, -1, AoaCreateSocketMessage.SOCKET_TYPE_SERVER, 0);
        AoaCreateSocketMessage server = AoaCreateSocketMessage.parse(serverBody, serverBody.length);
        assertTrue(server.isServerSocketRequest());
        assertFalse(server.isClientSocketRequest());
        assertEquals(AoaCreateSocketMessage.SERVER_CHANNEL_ID_SENTINEL, server.channelId);

        byte[] clientBody = createSocketBody(7225, 7, AoaCreateSocketMessage.SOCKET_TYPE_CLIENT, 0);
        AoaCreateSocketMessage client = AoaCreateSocketMessage.parse(clientBody, clientBody.length);
        assertFalse(client.isServerSocketRequest());
        assertTrue(client.isClientSocketRequest());
    }

    @Test public void allowsLargerBackingArrayButHonorsDeclaredPayloadLength() {
        byte[] body = new byte[32];
        try {
            AoaCreateSocketMessage.parse(body, 9);
            fail("expected minimum-length rejection");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsNullShortAndUnavailablePayloads() {
        assertInvalid(null, 10);
        assertInvalid(new byte[9], 9);
        assertInvalid(new byte[10], 11);
    }

    private static byte[] createSocketBody(int port, int channelId, int socketType, int messageType) {
        byte[] body = new byte[AoaCreateSocketMessage.MIN_LENGTH];
        putIntBigEndian(body, 0, port);
        putIntBigEndian(body, 4, channelId);
        body[8] = (byte) socketType;
        body[9] = (byte) messageType;
        return body;
    }

    private static void putIntBigEndian(byte[] output, int offset, int value) {
        output[offset] = (byte) (value >>> 24);
        output[offset + 1] = (byte) (value >>> 16);
        output[offset + 2] = (byte) (value >>> 8);
        output[offset + 3] = (byte) value;
    }

    private static void assertInvalid(byte[] body, int length) {
        try {
            AoaCreateSocketMessage.parse(body, length);
            fail("expected invalid create-socket payload");
        } catch (IllegalArgumentException expected) { }
    }
}
