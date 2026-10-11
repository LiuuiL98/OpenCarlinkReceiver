package com.opencarlink.receiver.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class AoaTransportHeaderProbeTest {
    @Test public void decodesOfficialBigEndianChannelAndLength() {
        byte[] header = new byte[] {0, 0, 0, 2, 0, 0, 0, 12};
        AoaTransportHeaderProbe.Header decoded = AoaTransportHeaderProbe.decode(header, header.length);
        assertEquals(2, decoded.channelId);
        assertEquals(12, decoded.bodyLength);
        assertEquals(12, decoded.requestedBodyReadLength());
        assertFalse(decoded.isCreateSocketRecord());
    }

    @Test public void identifiesCreateSocketAndAppliesMinimumReadLength() {
        byte[] header = new byte[] {0, 0, 0, 1, 0, 0, 0, 4};
        AoaTransportHeaderProbe.Header decoded = AoaTransportHeaderProbe.decode(header, header.length);
        assertTrue(decoded.isCreateSocketRecord());
        assertEquals(4, decoded.bodyLength);
        assertEquals(8, decoded.requestedBodyReadLength());
    }

    @Test public void enforcesOfficialChannelAndLengthBounds() {
        assertInvalid(new byte[] {0, 0, 0, 0, 0, 0, 0, 1});
        assertInvalid(new byte[] {0, 0, 0, (byte)241, 0, 0, 0, 1});
        assertInvalid(new byte[] {0, 0, 0, 2, 0, 0, 0, 0});
        assertInvalid(new byte[] {0, 0, 0, 2, 4, 0, 0, 1}); // 64 MiB + 1
    }

    @Test public void requiresACompleteHeaderAccordingToReturnedLength() {
        byte[] data = new byte[16];
        assertFalse(AoaTransportHeaderProbe.isAvailable(null, 8));
        assertFalse(AoaTransportHeaderProbe.isAvailable(data, 7));
        assertFalse(AoaTransportHeaderProbe.isAvailable(new byte[7], 8));
        assertTrue(AoaTransportHeaderProbe.isAvailable(data, 8));
        try {
            AoaTransportHeaderProbe.decode(data, 7);
            fail("expected incomplete header rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test public void rejectsOutOfRangeWord() {
        try {
            AoaTransportHeaderProbe.wordBigEndian(new byte[8], 5);
            fail("expected invalid range");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertInvalid(byte[] header) {
        try {
            AoaTransportHeaderProbe.decode(header, header.length);
            fail("expected invalid header rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
