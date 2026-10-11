package com.opencarlink.receiver.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class AoaBodyLengthPolicyTest {
    @Test public void allowsBodyWithinLocalCap() {
        AoaTransportHeaderProbe.Header header = AoaTransportHeaderProbe.decode(
                new byte[] {0, 0, 0, 2, 0, 0, 0, 32}, 8);
        AoaBodyLengthPolicy policy = new AoaBodyLengthPolicy(64);
        assertEquals(32, policy.validateAndGetRequestedReadLength(header));
        assertEquals(64, policy.getMaxBodyLength());
    }

    @Test public void rejectsOfficiallyValidButLocallyOversizedBody() {
        AoaTransportHeaderProbe.Header header = AoaTransportHeaderProbe.decode(
                new byte[] {0, 0, 0, 2, 0, 0, 4, 1}, 8); // 1025 bytes
        AoaBodyLengthPolicy policy = new AoaBodyLengthPolicy(1024);
        try {
            policy.validateAndGetRequestedReadLength(header);
            fail("expected local cap rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("local allocation cap"));
        }
    }

    @Test public void keepsOfficialMinimumReadSizeForShortLogicalBodies() {
        AoaTransportHeaderProbe.Header header = AoaTransportHeaderProbe.decode(
                new byte[] {0, 0, 0, 2, 0, 0, 0, 4}, 8);
        assertEquals(8, new AoaBodyLengthPolicy(64).validateAndGetRequestedReadLength(header));
    }

    @Test public void rejectsTruncatedOrInconsistentBody() {
        AoaBodyLengthPolicy policy = new AoaBodyLengthPolicy(64);
        assertBodyInvalid(policy, new byte[10], 10, 9);
        assertBodyInvalid(policy, new byte[10], 10, 11);
        policy.validateAvailableBody(new byte[12], 10, 12);
    }

    @Test public void rejectsNullHeaderBodyAndInvalidCaps() {
        try {
            new AoaBodyLengthPolicy(0);
            fail("expected invalid cap rejection");
        } catch (IllegalArgumentException expected) { }
        try {
            new AoaBodyLengthPolicy(65 * 1024 * 1024);
            fail("expected over-official-cap rejection");
        } catch (IllegalArgumentException expected) { }
        try {
            new AoaBodyLengthPolicy().validateAndGetRequestedReadLength(null);
            fail("expected null header rejection");
        } catch (IllegalArgumentException expected) { }
        try {
            new AoaBodyLengthPolicy().validateAvailableBody(null, 1, 1);
            fail("expected null body rejection");
        } catch (IllegalArgumentException expected) { }
    }

    private static void assertBodyInvalid(AoaBodyLengthPolicy policy, byte[] body,
                                          int declaredLength, int returnedLength) {
        try {
            policy.validateAvailableBody(body, declaredLength, returnedLength);
            fail("expected invalid body rejection");
        } catch (IllegalArgumentException expected) { }
    }
}
