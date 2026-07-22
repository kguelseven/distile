package org.korhan.distile.core;

import java.util.regex.Pattern;

/**
 * A single masking rule: a precompiled pattern and the replacement string to
 * substitute for every match found within a token.
 *
 * <p>Patterns are compiled once at startup (never per line) — see the
 * performance guardrails: no regex recompilation on the hot path.
 */
public record MaskRule(Pattern pattern, String replacement) {

    /** The canonical wildcard placeholder used throughout distile. */
    public static final String WILDCARD = "<*>";

    /** Convenience factory: compile regex and replace matches with <*>. */
    public static MaskRule of(String regex) {
        return new MaskRule(Pattern.compile(regex), WILDCARD);
    }

    /** Compile regex with an explicit replacement token. */
    public static MaskRule of(String regex, String replacement) {
        return new MaskRule(Pattern.compile(regex), replacement);
    }
}
