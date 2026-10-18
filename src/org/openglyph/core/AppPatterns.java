package org.openglyph.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-app notification patterns, stored in one setting as "pkg=PATTERN;pkg2=PATTERN".
 * Patterns are kept as strings here so this class stays free of the effects package.
 */
public final class AppPatterns {
    private AppPatterns() {}

    public static Map<String, String> parse(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) return out;
        for (String entry : value.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) continue;
            String pkg = entry.substring(0, eq).trim();
            String pattern = entry.substring(eq + 1).trim();
            if (!pkg.isEmpty() && !pattern.isEmpty()) out.put(pkg, pattern);
        }
        return out;
    }

    public static String format(Map<String, String> patterns) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : patterns.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Comma-separated package set, used for the essential-apps setting. */
    public static java.util.Set<String> parseSet(String value) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (value == null) return out;
        for (String s : value.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    public static String formatSet(java.util.Set<String> pkgs) {
        return String.join(",", pkgs);
    }
}
