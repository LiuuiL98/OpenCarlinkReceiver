package com.opencarlink.receiver.protocol;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class AoaTransportRecordDecoderTest {
    @Test public void handlesSplitHeaderAndSplitBody() {
        AoaTransportRecordDecoder decoder = new AoaTransportRecordDecoder(1024);
        byte[] record = record(2, new byte[] {10, 11, 12, 13});
        assertTrue(decoder.feed(record, 0, 3).isEmpty());
        assertTrue(decoder.hasPartialRecord());
        assertTrue(decoder.feed(record, 3, 7).isEmpty());
        List<AoaTransportRecordDecoder.Record> decoded = decoder.feed(record, 10, record.length - 10);
        assertEquals(1, decoded.size());
        assertEquals(2, decoded.get(0).channelId);
        assertArrayEquals(new byte[] {10, 11, 12, 13}, decoded.get(0).copyBody());
        assertFalse(decoder.hasPartialRecord());
    }

    @Test public void decodesMultipleRecordsInOneInput() {
        AoaTransportRecordDecoder decoder = new AoaTransportRecordDecoder(1024);
        byte[] first = record(2, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        byte[] second = record(3, new byte[] {9, 10, 11, 12, 13, 14, 15, 16});
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        List<AoaTransportRecordDecoder.Record> records = decoder.feed(joined, 0, joined.length);
        assertEquals(2, records.size());
        assertEquals(2, records.get(0).channelId);
        assertEquals(3, records.get(1).channelId);
    }

    @Test public void consumesOfficialMinimumEightWireBytesButExposesDeclaredBody() {
        AoaTransportRecordDecoder decoder = new AoaTransportRecordDecoder(1024);
        byte[] header = new byte[] {0, 0, 0, 1, 0, 0, 0, 4};
        byte[] paddedBody = new byte[] {1, 2, 3, 4, 99, 98, 97, 96};
        byte[] record = new byte[16];
        System.arraycopy(header, 0, record, 0, 8);
        System.arraycopy(paddedBody, 0, record, 8, 8);
        List<AoaTransportRecordDecoder.Record> records = decoder.feed(record, 0, record.length);
        assertEquals(1, records.size());
        assertTrue(records.get(0).isCreateSocketRecord());
        assertEquals(4, records.get(0).declaredBodyLength);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, records.get(0).copyBody());
    }

    @Test public void rejectsDeclaredLengthAboveLocalCapBeforeBodyAllocation() {
        AoaTransportRecordDecoder decoder = new AoaTransportRecordDecoder(16);
        byte[] header = new byte[] {0, 0, 0, 2, 0, 0, 0, 17};
        try {
            decoder.feed(header, 0, header.length);
            fail("expected local cap rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("local cap"));
        }
        assertFalse(decoder.hasPartialRecord());
    }

    @Test public void malformedHeaderResetsDecoderForSubsequentInput() {
        AoaTransportRecordDecoder decoder = new AoaTransportRecordDecoder(1024);
        byte[] badHeader = new byte[] {0, 0, 0, 0, 0, 0, 0, 8};
        try {
            decoder.feed(badHeader, 0, badHeader.length);
            fail("expected invalid channel ID");
        } catch (IllegalArgumentException expected) { }
        assertFalse(decoder.hasPartialRecord());
        byte[] validRecord = record(2, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertEquals(1, decoder.feed(validRecord, 0, validRecord.length).size());
    }

    @Test public void resetDiscardsPartialRecord() {
        AoaTransportRecordDecoder decoder = new AoaTransportRecordDecoder(1024);
        byte[] record = record(2, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        decoder.feed(record, 0, 10);
        assertTrue(decoder.hasPartialRecord());
        decoder.reset();
        assertFalse(decoder.hasPartialRecord());
        assertEquals(1, decoder.feed(record, 0, record.length).size());
    }

    @Test public void rejectsInvalidRangesAndNullInput() {
        AoaTransportRecordDecoder decoder = new AoaTransportRecordDecoder(1024);
        try {
            decoder.feed(null, 0, 0);
            fail("expected null input rejection");
        } catch (IllegalArgumentException expected) { }
        try {
            decoder.feed(new byte[4], 3, 2);
            fail("expected invalid range rejection");
        } catch (IllegalArgumentException expected) { }
    }

    private static byte[] record(int channelId, byte[] logicalBody) {
        int wireBodyLength = Math.max(logicalBody.length, 8);
        byte[] record = new byte[8 + wireBodyLength];
        putIntBigEndian(record, 0, channelId);
        putIntBigEndian(record, 4, logicalBody.length);
        System.arraycopy(logicalBody, 0, record, 8, logicalBody.length);
        return record;
    }

    private static void putIntBigEndian(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }
}
