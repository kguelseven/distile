package org.korhan.distile.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

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
 * <h2>Hot-path performance</h2>
 * Masking is the most expensive step in the per-line pipeline (it runs every rule
 * against every token), so two things happen here to keep it cheap:
 * <ul>
 *   <li><b>Matchers are reused, never re-allocated.</b> {@code Pattern.matcher()}
 *       allocates a fresh {@code Matcher} on every call; doing that per
 *       (token × rule) is ~one allocation per rule per token and dominates GC
 *       churn at millions of lines. Instead each thread keeps one reusable
 *       {@code Matcher} per rule and {@code reset()}s it. The {@link ThreadLocal}
 *       keeps {@link #maskToken} safe for library embedders who mask from several
 *       threads; the CLI ingest path is single-threaded and pays only the cheap
 *       thread-local lookup.</li>
 *   <li><b>Necessary-condition gate.</b> A rule can only match a token that
 *       contains the characters the pattern structurally requires (a URL needs
 *       {@code ':'}, IPv4 needs {@code '.'}, the number rule needs a digit, …).
 *       Each default rule declares the single feature it cannot match without;
 *       tokens are scanned once for those features and rules whose required
 *       feature is absent are skipped without running the regex at all. For a
 *       plain word like {@code INFO} or {@code login} this skips <em>every</em>
 *       numeric/structural rule — and real logs are mostly such words. The gate
 *       tests a <em>necessary</em> condition, so it never skips a rule that could
 *       have matched. Custom rules loaded from a file carry no gate (they always
 *       run), preserving their exact behaviour.</li>
 * </ul>
 * All patterns are compiled once at construction. Nothing here recompiles a regex
 * on the per-line hot path.
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

    /** A {@code Masker} loaded with the default, ordered, gated rule set. */
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
     * with variable substrings rewritten to {@code <*>}.
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
     * Default rule set, ordered from most specific to least specific, each paired
     * with a <b>necessary</b> token feature (see class doc). Order matters:
     * specific structural tokens (UUID, IP, hex) must be consumed before the
     * catch-all number rule, or the number rule would shred them into a mess of
     * partial wildcards.
     *
     * <p>Tokenization has already split on whitespace, so a "2021-01-01 12:00:00"
     * timestamp arrives as two separate tokens — hence separate date and time
     * rules rather than one datetime rule. Rules are unanchored so they also
     * catch bracketed/embedded forms like {@code [2021-01-01} and {@code 12:00:00]}.
     *
     * <p>Each gate is the ONE character class the pattern cannot match without.
     * It must stay a genuine necessary condition — e.g. hex runs and IPv6/UUID/MAC
     * are NOT gated on a digit because {@code deadbeef}-style all-letter hex is
     * legal; they gate on structure (length, {@code '-'}, {@code ':'}) instead.
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
