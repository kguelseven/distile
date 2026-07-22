package org.korhan.distile.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a raw log line into tokens on runs of whitespace.
 *
 * <p>Deliberately dumb: tokenization is the cheap first step of the per-line
 * pipeline and must not do anything clever (that is the Masker's job).
 * Splitting on whitespace keeps token positions stable, which the rest of the
 * Drain pipeline relies on for position-based similarity.
 */
public final class Tokenizer {

    /**
     * Tokenize a line into whitespace-delimited tokens, dropping empty tokens
     * produced by leading/trailing/repeated whitespace.
     *
     * <p>Hand-rolled rather than line.trim().split("\\s+") to avoid
     * per-line regex work and intermediate-array allocation on the hot path.
     */
    public List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        int n = line.length();
        int i = 0;
        while (i < n) {
            // Skip whitespace.
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            tokens.add(line.substring(start, i));
        }
        return tokens;
    }
}
