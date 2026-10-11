package com.opencarlink.receiver.protocol;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class AoaBodyReadPolicyTest {
    @Test public void usesDeclaredLengthWhenAtLeastEightBytes() {
        AoaTransportHeaderProbe.Header header = header(2, 64);
        assertEquals(64, AoaBodyReadPolicy.requestedReadLength(header, 1024));
        assertEquals(64, AoaBodyReadPolicy.allocate(header, 1024).length);
    }

    @Test public void preservesOfficialEightByteMinimumForShortBodies() {
        AoaTransportHeaderProbe.Header header = header(1, 3);
        assertEquals(8, AoaBodyReadPolicy.requestedReadLength(header, 1024));
        assertEquals(8, AoaBodyReadPolicy.allocate(header, 1024).length);
    }

    @Test public void rejectsDeclaredLengthAboveLocalCapBeforeAllocation() {
        AoaTransportHeaderProbe.Header header = header(2, 4097);
        try {
            AoaBodyReadPolicy.allocate(header, 4096);
            fail("expected local cap rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test public void rejectsInvalidLocalCap() {
        AoaTransportHeaderProbe.Header header = header(2, 1);
        try {
            AoaBodyReadPolicy.allocate(header, AoaTransportHeaderProbe.OFFICIAL_MAX_BODY_LENGTH + 1);
            fail("expected invalid cap rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test public void requiresExactReadBeforeDispatch() throws Exception {
        AoaBodyReadPolicy.requireCompleteRead(8, 8);
        assertIncomplete(8, 0);
        assertIncomplete(8, 7);
        assertIncomplete(8, -1);
    }

    private static AoaTransportHeaderProbe.Header header(int channelId, int length) {
        byte[] bytes = new byte[] {
                0, 0, 0, (byte) channelId,
                (byte) (length >>> 24), (byte) (length >>> 16),
                (byte) (length >>> 8), (byte) length
        };
        return AoaTransportHeaderProbe.decode(bytes, bytes.length, length);
    }

    private static void assertIncomplete(int expected, int actual) {
        try {
            AoaBodyReadPolicy.requireCompleteRead(expected, actual);
            fail("expected incomplete read rejection");
        } catch (IOException expectedException) {
            assertTrue(expectedException.getMessage().contains("incomplete"));
        }
    }
}
