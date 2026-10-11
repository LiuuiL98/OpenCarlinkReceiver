package com.opencarlink.receiver.protocol;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class AoaRecordReaderTest {
    @Test public void readsHeaderThenExactBodyForNormalChannel() throws Exception {
        byte[] body = new byte[] {10, 11, 12, 13, 0, 0, 0, 0};
        AoaRecordReader.Record record = AoaRecordReader.readOne(
                new ScriptedSource(new byte[] {0, 0, 0, 2, 0, 0, 0, 4}, body, null, null), 1024);
        assertNotNull(record);
        assertEquals(2, record.header.channelId);
        assertEquals(4, record.logicalBodyLength);
        assertEquals(8, record.body.length);
        assertFalse(record.isCreateSocketRecord());
        assertNull(record.createSocket);
        assertEquals(10, record.body[0]);
        assertEquals(13, record.body[3]);
    }

    @Test public void decodesCreateSocketBodyAfterExactRead() throws Exception {
        byte[] body = new byte[] {0, 0, 0x1f, (byte)0x90, 0, 0, 0, 7, 2, 3};
        AoaRecordReader.Record record = AoaRecordReader.readOne(
                new ScriptedSource(new byte[] {0, 0, 0, 1, 0, 0, 0, 10}, body, null, null), 1024);
        assertTrue(record.isCreateSocketRecord());
        assertNotNull(record.createSocket);
        assertEquals(8080, record.createSocket.port);
        assertEquals(7, record.createSocket.channelId);
        assertEquals(2, record.createSocket.socketType);
        assertEquals(3, record.createSocket.channelMessageType);
    }

    @Test public void returnsNullOnlyWhenNoHeaderStarts() throws Exception {
        assertNull(AoaRecordReader.readOne(new ScriptedSource(null, null, null, null), 1024));
    }

    @Test public void rejectsPartialHeaderAndPartialBody() throws Exception {
        assertReadFailure(new ScriptedSource(new byte[8], null, 7, null), 1024);
        assertReadFailure(new ScriptedSource(new byte[] {0, 0, 0, 2, 0, 0, 0, 9},
                new byte[9], null, 8), 1024);
    }

    @Test public void rejectsOversizedDeclaredBodyBeforeReadingBody() throws Exception {
        ScriptedSource source = new ScriptedSource(
                new byte[] {0, 0, 0, 2, 0, 0, 0x10, 1}, null, null, null);
        try {
            AoaRecordReader.readOne(source, 1024);
            fail("expected local cap rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals(1, source.calls);
        }
    }

    private static void assertReadFailure(ScriptedSource source, int cap) throws Exception {
        try {
            AoaRecordReader.readOne(source, cap);
            fail("expected incomplete read rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("incomplete"));
        }
    }

    private static final class ScriptedSource implements AoaRecordReader.ExactLengthSource {
        final byte[] header;
        final byte[] body;
        final Integer forcedFirstResult;
        final Integer forcedSecondResult;
        int calls;

        ScriptedSource(byte[] header, byte[] body, Integer forcedFirstResult,
                       Integer forcedSecondResult) {
            this.header = header;
            this.body = body;
            this.forcedFirstResult = forcedFirstResult;
            this.forcedSecondResult = forcedSecondResult;
        }

        @Override public int readExact(byte[] destination, int requestedLength) throws IOException {
            calls++;
            byte[] source = calls == 1 ? header : body;
            if (source == null) return 0;
            Integer forced = calls == 1 ? forcedFirstResult : forcedSecondResult;
            int reported = forced == null ? source.length : forced;
            if (reported == requestedLength && source.length >= requestedLength) {
                System.arraycopy(source, 0, destination, 0, requestedLength);
            }
            return reported;
        }
    }
}
