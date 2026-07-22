package org.korhan.distile.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Replaces known-variable substrings (timestamps, IPs, numbers, …) with the
 * wildcard <*> before a line reaches the parse tree. This is the
 * highest-leverage step for template quality: without it, a variable near the
 * start of a line (e.g. a timestamp above all) spawns a fresh tree branch per value
 * and explodes the template count.
 *
 * <p>Masking replaces in place and never deletes: it may rewrite part of a token
 * (id=1234 → id=<*>) but never removes or merges tokens, so token
 * count and position are preserved — which the tree bucketing and similarity
 * metric both rely on.
 *
 * <p>Masking runs every rule against every token, so it is the pipeline's hottest
 * step and is kept cheap two ways. (1) Matchers are reused, not re-allocated: each
 * thread keeps one Matcher per rule (via ThreadLocal, so embedders
 * can mask from several threads) and reset() s it. (2) A necessary-condition
 * gate skips a rule when the token lacks a character the pattern requires (a digit
 * for the number rule, ('.' for IPv4, …); a plain word like INFO
 * thus skips every numeric rule. The gate tests a necessary condition, so it never
 * skips a rule that could have matched. All patterns compile once at construction;
 * nothing recompiles on the hot path. (Custom file-loaded rules carry no gate.)
 */
public final class Masker {

    // Token feature bits used by the necessary-condition gate. A rule tagged with
    // one of these is run only if the token contains that feature.
    private static final int F_DIGIT = 1;        // any 0-9
    private static final int F_COLON = 1 << 1;   // ':'
    private static final int F_AT    = 1 << 2;   // '@'
    private static final int F_DOT   = 1 << 3;   // '.'
    private static final int F_DASH  = 1 << 4;   // '-'
    private static final int F_X     = 1 << 5;   // 'x' or 'X'
    private static final int F_LEN8  = 1 << 6;   // length >= 8
    private static final int GATE_NONE = 0;      // no gate — always run (custom rules)

    private final List<MaskRule> rules;
    private final int[] gates;              // parallel to rules; feature bit each rule requires (0 = none)
    private final String[] replacements;    // parallel to rules; cached to avoid record accessor calls
    // One reusable Matcher per rule, per thread — see class doc.
    private final ThreadLocal<Matcher[]> matchers;

    /**
     * Build a masker with no per-rule gates: every rule runs against every token.
     * This is the safe, behaviour-preserving path for custom rule sets loaded from
     * a file, where we cannot know each pattern's structural requirements.
     */
    public Masker(List<MaskRule> rules) {
        this(rules, new int[rules.size()]);
    }

    private Masker(List<MaskRule> rules, int[] gates) {
        // Defensive copy so the caller can't mutate our rule order later.
        this.rules = List.copyOf(rules);
        this.gates = gates.clone();
        this.replacements = new String[this.rules.size()];
        for (int i = 0; i < this.rules.size(); i++) {
            this.replacements[i] = this.rules.get(i).replacement();
        }
        this.matchers = ThreadLocal.withInitial(() -> {
            Matcher[] m = new Matcher[this.rules.size()];
            for (int i = 0; i < m.length; i++) {
                m[i] = this.rules.get(i).pattern().matcher("");
            }
            return m;
        });
    }

    /** A Masker loaded with the default, ordered, gated rule set. */
    public static Masker withDefaults() {
        List<GatedRule> gr = defaultGatedRules();
        List<MaskRule> r = new ArrayList<>(gr.size());
        int[] g = new int[gr.size()];
        for (int i = 0; i < gr.size(); i++) {
            r.add(gr.get(i).rule());
            g[i] = gr.get(i).gate();
        }
        return new Masker(r, g);
    }

    public List<MaskRule> rules() {
        return rules;
    }

    /**
     * Mask a token list in place-preserving fashion: same size, same positions,
     * with variable substrings rewritten to <*>.
     */
    public List<String> mask(List<String> tokens) {
        List<String> out = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            out.add(maskToken(token));
        }
        return out;
    }

    /** Apply every rule left-to-right to a single token, skipping gated-out rules. */
    public String maskToken(String token) {
        // Feature bits are computed once on the ORIGINAL token. Masking only ever
        // replaces specifics with "<*>", which can only REMOVE features, so the
        // original token's features are a superset of any intermediate state's.
        // Gating on the superset can only over-run a rule (harmless — it just
        // fails to match), never wrongly skip one. So a single scan is correct.
        int feats = features(token);
        Matcher[] ms = matchers.get();
        String t = token;
        for (int i = 0; i < gates.length; i++) {
            int need = gates[i];
            if (need != GATE_NONE && (feats & need) != need) {
                continue; // token cannot contain what this rule requires — skip the regex
            }
            // reset(t) rebinds the reused matcher to the current token; replaceAll
            // allocates a new String only when there is a match (no-match returns t).
            t = ms[i].reset(t).replaceAll(replacements[i]);
            if (t.equals(MaskRule.WILDCARD)) {
                return t; // fully generalised — no later rule can change it further
            }
        }
        return t;
    }

    /** Cheap single pass computing the feature bits the gate consults. */
    private static int features(String token) {
        int f = 0;
        int len = token.length();
        if (len >= 8) {
            f |= F_LEN8;
        }
        for (int i = 0; i < len; i++) {
            char c = token.charAt(i);
            if (c >= '0' && c <= '9') {
                f |= F_DIGIT;
            } else {
                switch (c) {
                    case ':' -> f |= F_COLON;
                    case '@' -> f |= F_AT;
                    case '.' -> f |= F_DOT;
                    case '-' -> f |= F_DASH;
                    case 'x', 'X' -> f |= F_X;
                    default -> { /* not a gate feature */ }
                }
            }
        }
        return f;
    }

    /** A default rule paired with the token feature it structurally requires. */
    private record GatedRule(MaskRule rule, int gate) {
    }

    /**
     * Default rule set, ordered most specific to least, each paired with a
     * necessary token feature (see class doc). Order matters: structural tokens
     * (UUID, IP, hex) must be masked before the catch-all number rule, which would
     * otherwise shred them into partial wildcards.
     *
     * <p>Whitespace tokenization splits "2021-01-01 12:00:00" into two tokens,
     * hence separate date and time rules; both are unanchored so they also catch
     * embedded forms like [2021-01-01 and 12:00:00].
     *
     * <p>Each gate is the one character class its pattern cannot match without,
     * and must stay a genuine necessary condition — e.g. hex/IPv6/UUID/MAC gate on
     * structure (length, '-', ':'), not a digit, since all-letter
     * hex like deadbeef is legal.
     */
    private static List<GatedRule> defaultGatedRules() {
        List<GatedRule> r = new ArrayList<>();

        // URLs (whole token) — before anything picks at the scheme/host/numbers.
        r.add(new GatedRule(MaskRule.of("[a-zA-Z][a-zA-Z0-9+.\\-]*://\\S+"), F_COLON));
        // Emails.
        r.add(new GatedRule(MaskRule.of("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"), F_AT));

        // ISO-8601 datetime in a single token (T separator, optional zone).
        r.add(new GatedRule(MaskRule.of("\\d{4}-\\d{2}-\\d{2}[T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+\\-]\\d{2}:?\\d{2})?"), F_DIGIT));
        // Date-only forms.
        r.add(new GatedRule(MaskRule.of("\\d{4}-\\d{2}-\\d{2}"), F_DIGIT));
        r.add(new GatedRule(MaskRule.of("\\d{4}/\\d{2}/\\d{2}"), F_DIGIT));
        r.add(new GatedRule(MaskRule.of("\\d{2}/\\d{2}/\\d{4}"), F_DIGIT));
        r.add(new GatedRule(MaskRule.of("\\d{2}/[A-Za-z]{3}/\\d{4}"), F_DIGIT));   // 10/Jul/2026 (common/apache log)
        // Time-only forms (with optional fractional seconds).
        r.add(new GatedRule(MaskRule.of("\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?"), F_DIGIT));

        // UUID (before hex/number so it is consumed whole). All-letter hex is legal,
        // so gate on the mandatory '-', not on a digit.
        r.add(new GatedRule(MaskRule.of("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), F_DASH));

        // MAC address (before hex/number). Gate on the mandatory ':'.
        r.add(new GatedRule(MaskRule.of("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"), F_COLON));

        // IPv6 (full and :: compressed forms). Kept ahead of IPv4 and number. Gate on ':'.
        r.add(new GatedRule(MaskRule.of("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])"), F_COLON));
        r.add(new GatedRule(MaskRule.of("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:)+:(?:[0-9A-Fa-f]{1,4}:?)*(?![0-9A-Fa-f:])"), F_COLON));

        // IPv4. Gate on the mandatory '.'.
        r.add(new GatedRule(MaskRule.of("(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![0-9.])"), F_DOT));

        // Hex blobs / hashes. "0x..." must contain 'x'/'X'; a bare hex run needs
        // >=8 chars, so gate the run on length (all-letter hex has no digit to gate on).
        r.add(new GatedRule(MaskRule.of("0[xX][0-9a-fA-F]+"), F_X));
        r.add(new GatedRule(MaskRule.of("(?<![0-9A-Za-z])[0-9a-fA-F]{8,}(?![0-9A-Za-z])"), F_LEN8));

        // Catch-all numbers (ints/decimals/scientific). Boundaries keep it from
        // eating the digit tail of identifiers like "worker3" while still masking
        // "id=42", "port:8080", and standalone "12.5". The quantifiers are
        // POSSESSIVE (\d++, (?:..)?+) on purpose: without that, a greedy match
        // backtracks to leave one digit so the trailing-letter guard passes,
        // turning "12ms" into the ugly partial "<*>2ms". Possessive makes the
        // rule all-or-nothing — "12ms" (a mixed token, not a pure number) is left
        // intact, matching the "pure numbers" intent. Requires a digit.
        r.add(new GatedRule(MaskRule.of("(?<![A-Za-z0-9])[+\\-]?\\d++(?:\\.\\d++)?+(?:[eE][+\\-]?\\d++)?+(?![A-Za-z0-9])"), F_DIGIT));

        return r;
    }

    /**
     * Default rule set (patterns only), for callers that build their own masker.
     * The CLI uses {@link #withDefaults()} which additionally applies the
     * per-rule gate; this accessor exists for API compatibility.
     */
    public static List<MaskRule> defaultRules() {
        List<GatedRule> gr = defaultGatedRules();
        List<MaskRule> r = new ArrayList<>(gr.size());
        for (GatedRule g : gr) {
            r.add(g.rule());
        }
        return r;
    }
}
