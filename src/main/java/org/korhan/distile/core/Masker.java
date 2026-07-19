package org.korhan.distile.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces known-variable substrings with the wildcard placeholder {@code <*>}
 * <b>before</b> the line reaches the parse tree.
 *
 * <p>This is the single highest-leverage step for template quality. Variables at
 * the <em>start</em> of a line (timestamps above all) would otherwise create a
 * fresh tree branch per value and explode the template count. Masking them first
 * means every line starts with a stable {@code <*>} and collapses correctly.
 * Rule of thumb: <em>the more you mask up front, the less the tree has to do.</em>
 *
 * <p>Masking is <b>replace-in-place, never delete</b>. Rules operate per token,
 * so a token count and every token position are preserved exactly — position is
 * what the tree bucketing and the similarity metric both depend on. A rule may
 * rewrite part of a token (e.g. {@code id=1234} → {@code id=<*>}); it never
 * removes a token or merges two.
 *
 * <p>All patterns are compiled once at construction. Nothing here recompiles a
 * regex on the per-line hot path.
 */
public final class Masker {

    private final List<MaskRule> rules;

    public Masker(List<MaskRule> rules) {
        // Defensive copy so the caller can't mutate our rule order later.
        this.rules = List.copyOf(rules);
    }

    /** A {@code Masker} loaded with the default, ordered rule set. */
    public static Masker withDefaults() {
        return new Masker(defaultRules());
    }

    public List<MaskRule> rules() {
        return rules;
    }

    /**
     * Mask a token list in place-preserving fashion: same size, same positions,
     * with variable substrings rewritten to {@code <*>}.
     */
    public List<String> mask(List<String> tokens) {
        List<String> out = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            out.add(maskToken(token));
        }
        return out;
    }

    /** Apply every rule left-to-right to a single token. */
    public String maskToken(String token) {
        String t = token;
        for (MaskRule rule : rules) {
            // matcher.replaceAll allocates only when a match exists; for the
            // common no-match token it is a single scan with no new String.
            t = rule.pattern().matcher(t).replaceAll(rule.replacement());
        }
        return t;
    }

    /**
     * Default rule set, ordered from most specific to least specific. Order
     * matters: specific structural tokens (UUID, IP, hex) must be consumed
     * before the catch-all number rule, or the number rule would shred them into
     * a mess of partial wildcards.
     *
     * <p>Tokenization has already split on whitespace, so a "2021-01-01 12:00:00"
     * timestamp arrives as two separate tokens — hence separate date and time
     * rules rather than one datetime rule. Rules are unanchored so they also
     * catch bracketed/embedded forms like {@code [2021-01-01} and {@code 12:00:00]}.
     */
    public static List<MaskRule> defaultRules() {
        List<MaskRule> r = new ArrayList<>();

        // URLs (whole token) — before anything picks at the scheme/host/numbers.
        r.add(MaskRule.of("[a-zA-Z][a-zA-Z0-9+.\\-]*://\\S+"));
        // Emails.
        r.add(MaskRule.of("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"));

        // ISO-8601 datetime in a single token (T separator, optional zone).
        r.add(MaskRule.of("\\d{4}-\\d{2}-\\d{2}[T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+\\-]\\d{2}:?\\d{2})?"));
        // Date-only forms.
        r.add(MaskRule.of("\\d{4}-\\d{2}-\\d{2}"));
        r.add(MaskRule.of("\\d{4}/\\d{2}/\\d{2}"));
        r.add(MaskRule.of("\\d{2}/\\d{2}/\\d{4}"));
        r.add(MaskRule.of("\\d{2}/[A-Za-z]{3}/\\d{4}"));            // 10/Jul/2026 (common/apache log)
        // Time-only forms (with optional fractional seconds).
        r.add(MaskRule.of("\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?"));

        // UUID (before hex/number so it is consumed whole).
        r.add(MaskRule.of("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));

        // MAC address (before hex/number).
        r.add(MaskRule.of("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"));

        // IPv6 (full and :: compressed forms). Kept ahead of IPv4 and number.
        r.add(MaskRule.of("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])"));
        r.add(MaskRule.of("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:)+:(?:[0-9A-Fa-f]{1,4}:?)*(?![0-9A-Fa-f:])"));

        // IPv4.
        r.add(MaskRule.of("(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![0-9.])"));

        // Hex blobs / hashes.
        r.add(MaskRule.of("0[xX][0-9a-fA-F]+"));
        r.add(MaskRule.of("(?<![0-9A-Za-z])[0-9a-fA-F]{8,}(?![0-9A-Za-z])"));

        // Catch-all numbers (ints/decimals/scientific). Boundaries keep it from
        // eating the digit tail of identifiers like "worker3" while still masking
        // "id=42", "port:8080", and standalone "12.5". The quantifiers are
        // POSSESSIVE (\d++, (?:..)?+) on purpose: without that, a greedy match
        // backtracks to leave one digit so the trailing-letter guard passes,
        // turning "12ms" into the ugly partial "<*>2ms". Possessive makes the
        // rule all-or-nothing — "12ms" (a mixed token, not a pure number) is left
        // intact, matching the "pure numbers" intent.
        r.add(MaskRule.of("(?<![A-Za-z0-9])[+\\-]?\\d++(?:\\.\\d++)?+(?:[eE][+\\-]?\\d++)?+(?![A-Za-z0-9])"));

        return r;
    }
}
