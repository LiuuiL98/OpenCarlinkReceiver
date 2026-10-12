package com.opencarlink.receiver.protocol;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Regression fixture derived from the 2026-10-12 Galaxy S9+ raw Bulk IN trace.
 *
 * The trace showed separate 8-byte header reads followed by 10-byte body reads.
 * These tests validate framing and raw field decoding only; they do not assign
 * business meaning to the decoded values or perform socket/authentication work.
 */
public class AoaHardwareTraceRegressionTest {
    @Test
    public void decodesFirstCapturedCreateSocketRecord() throws Exception {
        AoaRecordReader.Record record = AoaRecordReader.readOne(
                new CapturedRecordSource(
                        hex("00 00 00 01 00 00 00 0A"),
                        hex("00 00 1C 44 FF FF FF FF 01 02")),
                1024);

        assertNotNull(record);
        assertEquals(1, record.header.channelId);
        assertEquals(10, record.logicalBodyLength);
        assertTrue(record.isCreateSocketRecord());
        assertNotNull(record.createSocket);
        assertEquals(7236, record.createSocket.port);
        assertEquals(-1, record.createSocket.channelId);
        assertEquals(1, record.createSocket.socketType);
        assertEquals(2, record.createSocket.channelMessageType);
    }

    @Test
    public void decodesSecondCapturedCreateSocketRecord() throws Exception {
        AoaRecordReader.Record record = AoaRecordReader.readOne(
                new CapturedRecordSource(
                        hex("00 00 00 01 00 00 00 0A"),
                        hex("00 00 10 E1 FF FF FF FF 01 02")),
                1024);

        assertNotNull(record);
        assertEquals(1, record.header.channelId);
        assertEquals(10, record.logicalBodyLength);
        assertTrue(record.isCreateSocketRecord());
        assertNotNull(record.createSocket);
        assertEquals(4321, record.createSocket.port);
        assertEquals(-1, record.createSocket.channelId);
        assertEquals(1, record.createSocket.socketType);
        assertEquals(2, record.createSocket.channelMessageType);
    }

    @Test
    public void rejectsInvalidHeaderBeforeAttemptingBodyRead() throws Exception {
        CapturedRecordSource source = new CapturedRecordSource(
                hex("00 00 1C 44 FF FF FF FF"), new byte[10]);

        try {
            AoaRecordReader.readOne(source, 1024);
            fail("invalid channel and body length must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("only the header should have been requested", 1, source.calls);
        }
    }

    private static byte[] hex(String text) {
        String[] pieces = text.trim().split("\\s+");
        byte[] result = new byte[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            result[i] = (byte) Integer.parseInt(pieces[i], 16);
        }
        return result;
    }

    private static final class CapturedRecordSource implements AoaRecordReader.ExactLengthSource {
        private final byte[] header;
        private final byte[] body;
        private int calls;

        CapturedRecordSource(byte[] header, byte[] body) {
            this.header = header;
            this.body = body;
        }

        @Override
        public int readExact(byte[] destination, int requestedLength) throws IOException {
            calls++;
            byte[] source = calls == 1 ? header : body;
            if (source == null || source.length < requestedLength) {
                throw new IOException("incomplete captured fixture");
            }
            System.arraycopy(source, 0, destination, 0, requestedLength);
            return requestedLength;
        }
    }
}
