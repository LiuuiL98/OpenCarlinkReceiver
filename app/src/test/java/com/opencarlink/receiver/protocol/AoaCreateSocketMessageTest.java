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

    private static void assertInvalid(byte[] body, int length) {
        try {
            AoaCreateSocketMessage.parse(body, length);
            fail("expected invalid create-socket payload");
        } catch (IllegalArgumentException expected) { }
    }
}
