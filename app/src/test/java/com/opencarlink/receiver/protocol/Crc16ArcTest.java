package com.opencarlink.receiver.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class Crc16ArcTest {
    @Test
    public void standardCheckValueMatchesCrc16Arc() {
        assertEquals(0xBB3D, Crc16Arc.checksum("123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test
    public void emptyInputReturnsZero() {
        assertEquals(0x0000, Crc16Arc.checksum(new byte[0]));
    }

    @Test
    public void rangeOnlyIncludesRequestedBytes() {
        byte[] data = "x123456789y".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(0xBB3D, Crc16Arc.checksum(data, 1, 9));
        assertNotEquals(Crc16Arc.checksum(data, 0, data.length), Crc16Arc.checksum(data, 1, 9));
    }

    @Test
    public void rejectsInvalidRanges() {
        assertThrows(IndexOutOfBoundsException.class, () -> Crc16Arc.checksum(new byte[4], -1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> Crc16Arc.checksum(new byte[4], 2, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> Crc16Arc.checksum(new byte[4], 0, -1));
    }

    @Test
    public void rejectsNullInput() {
        assertThrows(NullPointerException.class, () -> Crc16Arc.checksum(null, 0, 0));
    }
}
