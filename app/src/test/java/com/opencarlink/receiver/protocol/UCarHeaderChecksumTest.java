package com.opencarlink.receiver.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class UCarHeaderChecksumTest {
    private byte[] headerWithValidChecksum() {
        byte[] header = new byte[UCarHeaderChecksum.HEADER_MIN_LENGTH];
        for (int i = 0; i < UCarHeaderChecksum.CHECKSUM_INPUT_LENGTH; i++) {
            header[i] = (byte) (i * 7 + 3);
        }
        int crc = UCarHeaderChecksum.computeChecksum(header);
        header[18] = (byte) ((crc >>> 8) & 0xFF);
        header[19] = (byte) (crc & 0xFF);
        return header;
    }

    @Test
    public void validatesBigEndianStoredChecksum() {
        byte[] header = headerWithValidChecksum();
        assertTrue(UCarHeaderChecksum.isChecksumValid(header));
        assertEquals(UCarHeaderChecksum.computeChecksum(header),
                UCarHeaderChecksum.readStoredChecksum(header));
    }

    @Test
    public void changingCoveredByteInvalidatesChecksum() {
        byte[] header = headerWithValidChecksum();
        header[7] ^= 0x01;
        assertFalse(UCarHeaderChecksum.isChecksumValid(header));
    }

    @Test
    public void checksumBytesAreExcludedFromCalculation() {
        byte[] header = headerWithValidChecksum();
        int before = UCarHeaderChecksum.computeChecksum(header);
        header[18] ^= 0x55;
        header[19] ^= 0x22;
        assertEquals(before, UCarHeaderChecksum.computeChecksum(header));
        assertFalse(UCarHeaderChecksum.isChecksumValid(header));
    }

    @Test
    public void rejectsShortHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> UCarHeaderChecksum.computeChecksum(new byte[19]));
        assertThrows(IllegalArgumentException.class,
                () -> UCarHeaderChecksum.readStoredChecksum(new byte[19]));
    }

    @Test
    public void rejectsNullHeader() {
        assertThrows(NullPointerException.class,
                () -> UCarHeaderChecksum.isChecksumValid(null));
    }
}
