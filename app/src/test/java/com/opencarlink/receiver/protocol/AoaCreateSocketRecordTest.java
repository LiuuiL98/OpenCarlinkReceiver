package com.opencarlink.receiver.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class AoaCreateSocketRecordTest {
    @Test public void decodesFieldsAtOfficialOffsets() {
        byte[] body = new byte[] {
                0x00, 0x00, (byte)0xdf, 0x59, // port 57177
                0x00, 0x00, 0x00, 0x07,       // channel ID 7
                (byte)0xfe, (byte)0x80, 0x55  // unsigned bytes; trailing byte ignored
        };
        AoaCreateSocketRecord record = AoaCreateSocketRecord.decode(body, body.length);
        assertEquals(0xDF59, record.port);
        assertEquals(7, record.channelId);
        assertEquals(254, record.socketType);
        assertEquals(128, record.channelMessageType);
        assertFalse(record.requestsServerSocket());
    }

    @Test public void capturedHardwareRecordsRequestServerSockets() {
        byte[] rtsp = new byte[] {
                0x00, 0x00, 0x1c, 0x44,
                (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff,
                0x01, 0x02
        };
        byte[] uibc = new byte[] {
                0x00, 0x00, 0x10, (byte)0xe1,
                (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff,
                0x01, 0x02
        };

        AoaCreateSocketRecord rtspRecord = AoaCreateSocketRecord.decode(rtsp, rtsp.length);
        AoaCreateSocketRecord uibcRecord = AoaCreateSocketRecord.decode(uibc, uibc.length);

        assertEquals(7236, rtspRecord.port);
        assertEquals(4321, uibcRecord.port);
        assertEquals(AoaCreateSocketRecord.SERVER_SOCKET_CHANNEL_ID, rtspRecord.channelId);
        assertEquals(AoaCreateSocketRecord.SERVER_SOCKET_CHANNEL_ID, uibcRecord.channelId);
        assertTrue(rtspRecord.requestsServerSocket());
        assertTrue(uibcRecord.requestsServerSocket());
    }

    @Test public void acceptsExactlyTenBytes() {
        byte[] body = new byte[10];
        AoaCreateSocketRecord record = AoaCreateSocketRecord.decode(body, 10);
        assertEquals(0, record.port);
        assertEquals(0, record.channelId);
    }

    @Test public void rejectsNullShortAndUnavailableBytes() {
        assertInvalid(null, 10);
        assertInvalid(new byte[9], 9);
        assertInvalid(new byte[10], 9);
        assertInvalid(new byte[10], 11);
    }

    private static void assertInvalid(byte[] data, int length) {
        try {
            AoaCreateSocketRecord.decode(data, length);
            fail("expected invalid create-socket record rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
