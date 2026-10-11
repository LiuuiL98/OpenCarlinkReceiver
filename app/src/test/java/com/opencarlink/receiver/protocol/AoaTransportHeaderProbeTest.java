package com.opencarlink.receiver.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class AoaTransportHeaderProbeTest {
    @Test public void decodesBothEndianCandidatesWithoutNamingFields() {
        byte[] header = new byte[] {0x01, 0x23, 0x45, 0x67, (byte)0x89, (byte)0xab, (byte)0xcd, (byte)0xef};
        assertEquals(0x01234567L, AoaTransportHeaderProbe.wordBigEndian(header, 0));
        assertEquals(0x67452301L, AoaTransportHeaderProbe.wordLittleEndian(header, 0));
        assertEquals(0x89abcdefL, AoaTransportHeaderProbe.wordBigEndian(header, 4));
        assertEquals(0xefcdab89L, AoaTransportHeaderProbe.wordLittleEndian(header, 4));
    }

    @Test public void availabilityUsesActualReturnedLength() {
        byte[] data = new byte[16];
        assertFalse(AoaTransportHeaderProbe.isAvailable(null, 8));
        assertFalse(AoaTransportHeaderProbe.isAvailable(data, 7));
        assertFalse(AoaTransportHeaderProbe.isAvailable(new byte[7], 8));
        assertTrue(AoaTransportHeaderProbe.isAvailable(data, 8));
    }

    @Test public void rejectsOutOfRangeWord() {
        try {
            AoaTransportHeaderProbe.wordBigEndian(new byte[8], 5);
            fail("expected invalid range");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
