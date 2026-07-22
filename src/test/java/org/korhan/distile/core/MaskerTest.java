package org.korhan.distile.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Masking is the highest-leverage step, so it gets the most scrutiny: each
 * known-variable form must collapse to <*>, and — critically — masking
 * must never change a line's token count or shift positions.
 */
class MaskerTest {

    private final Masker masker = Masker.withDefaults();

    @Test
    void isoTimestampMasked() {
        assertEquals("<*>", masker.maskToken("2026-07-19T10:00:00Z"));
        assertEquals("<*>", masker.maskToken("2026-07-19T10:00:00.123+02:00"));
    }

    @Test
    void dateAndTimeMaskedSeparately() {
        // A "2026-07-19 10:00:00" timestamp arrives as two whitespace tokens.
        assertEquals("<*>", masker.maskToken("2026-07-19"));
        assertEquals("<*>", masker.maskToken("10:00:00"));
        assertEquals("<*>", masker.maskToken("10:00:00.500"));
    }

    @Test
    void ipv4Masked() {
        assertEquals("<*>", masker.maskToken("10.0.0.1"));
        assertEquals("<*>", masker.maskToken("192.168.255.254"));
    }

    @Test
    void ipv6Masked() {
        assertEquals("<*>", masker.maskToken("2001:db8:0:0:0:0:0:1"));
        assertEquals("<*>", masker.maskToken("fe80::1"));
    }

    @Test
    void uuidMasked() {
        assertEquals("<*>", masker.maskToken("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void hexMasked() {
        assertEquals("<*>", masker.maskToken("0xDEADBEEF"));
        assertEquals("<*>", masker.maskToken("deadbeefcafebabe")); // 16 hex chars
    }

    @Test
    void numbersMasked() {
        assertEquals("<*>", masker.maskToken("12345"));
        assertEquals("<*>", masker.maskToken("3.14"));
        assertEquals("<*>", masker.maskToken("-42"));
        assertEquals("<*>", masker.maskToken("1.5e10"));
    }

    @Test
    void numberInsideTokenMaskedButKeywordKept() {
        // "id=1234" -> "id=<*>": the number is masked in place, the key is kept.
        assertEquals("id=<*>", masker.maskToken("id=1234"));
        assertEquals("port:<*>", masker.maskToken("port:8080"));
        // A digit tail on an identifier is NOT masked (boundary guard).
        assertEquals("worker3", masker.maskToken("worker3"));
    }

    @Test
    void mixedNumberUnitTokenNotPartiallyMasked() {
        // Possessive quantifiers make the number rule all-or-nothing: a mixed
        // token like "12ms" is left intact rather than mangled to "<*>2ms".
        assertEquals("12ms", masker.maskToken("12ms"));
        assertEquals("8ms", masker.maskToken("8ms"));
        assertEquals("404handler", masker.maskToken("404handler"));
    }

    @Test
    void plainWordsUnchanged() {
        assertEquals("logged", masker.maskToken("logged"));
        assertEquals("User", masker.maskToken("User"));
    }

    @Test
    void maskPreservesTokenCountAndPositions() {
        List<String> in = List.of("2026-07-19", "INFO", "User", "alice", "paid", "$100", "from", "10.0.0.1");
        List<String> out = masker.mask(in);

        assertEquals(in.size(), out.size(), "masking must never change token count");
        assertEquals(List.of("<*>", "INFO", "User", "alice", "paid", "$<*>", "from", "<*>"), out);
    }
}
