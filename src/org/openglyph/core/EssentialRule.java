package org.openglyph.core;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One Essential Notifications rule: notifications from any of {@link #apps} (none = all apps),
 * mentioning any of {@link #people} (none = anyone) and containing any of {@link #keywords}
 * (none = anything) keep {@link #icon} on the Glyph Matrix until they are dismissed.
 *
 * Rules are stored in one setting, one rule per line, fields separated by '|' and list items
 * by ',', every field URL-encoded so names and keywords may contain any character.
 */
public final class EssentialRule {
    /** What the matcher sees of a notification; text fields may be empty, never null. */
    public static final class Notice {
        public final String pkg;
        public final String title;
        public final String text;
        /** Sender / person names attached to the notification (messaging style, people list). */
        public final List<String> people;

        public Notice(String pkg, String title, String text, List<String> people) {
            this.pkg = pkg;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.people = people == null ? List.of() : people;
        }
    }

    public static final String ICON_APP = "app"; // the app's own icon, drawn by the service

    public String name = "";
    public boolean enabled = true;
    public final Set<String> apps = new LinkedHashSet<>();
    public final List<String> people = new ArrayList<>();
    public final List<String> keywords = new ArrayList<>();
    public String icon = "bell";

    public boolean matches(Notice n) {
        if (!enabled) return false;
        if (!apps.isEmpty() && !apps.contains(n.pkg)) return false;
        String title = lower(n.title);
        String all = title + "\n" + lower(n.text);
        if (!people.isEmpty()) {
            boolean found = false;
            for (String p : people) {
                String want = lower(p).trim();
                if (want.isEmpty()) continue;
                if (title.contains(want)) found = true;
                for (String who : n.people) if (lower(who).contains(want)) found = true;
                if (found) break;
            }
            if (!found) return false;
        }
        if (!keywords.isEmpty()) {
            boolean found = false;
            for (String k : keywords) {
                String want = lower(k).trim();
                if (!want.isEmpty() && all.contains(want)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** The first enabled rule that matches, or null. */
    public static EssentialRule firstMatch(List<EssentialRule> rules, Notice n) {
        for (EssentialRule r : rules) if (r.matches(n)) return r;
        return null;
    }

    public EssentialRule copy() {
        return parseOne(format());
    }

    public String format() {
        return enc(name) + '|' + (enabled ? '1' : '0') + '|' + list(apps) + '|' + list(people)
                + '|' + list(keywords) + '|' + enc(icon);
    }

    public static String format(List<EssentialRule> rules) {
        StringBuilder sb = new StringBuilder();
        for (EssentialRule r : rules) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(r.format());
        }
        return sb.toString();
    }

    public static List<EssentialRule> parse(String value) {
        List<EssentialRule> out = new ArrayList<>();
        if (value == null || value.isEmpty()) return out;
        for (String line : value.split("\n")) {
            EssentialRule r = parseOne(line);
            if (r != null) out.add(r);
        }
        return out;
    }

    static EssentialRule parseOne(String line) {
        String[] f = line.split("\\|", -1);
        if (f.length < 6) return null;
        EssentialRule r = new EssentialRule();
        r.name = dec(f[0]);
        r.enabled = !"0".equals(f[1]);
        r.apps.addAll(unlist(f[2]));
        r.people.addAll(unlist(f[3]));
        r.keywords.addAll(unlist(f[4]));
        r.icon = f[5].isEmpty() ? "bell" : dec(f[5]);
        return r;
    }

    private static String list(Iterable<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (s == null || s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(enc(s.trim()));
        }
        return sb.toString();
    }

    private static List<String> unlist(String field) {
        List<String> out = new ArrayList<>();
        if (field.isEmpty()) return out;
        for (String s : field.split(",")) if (!s.isEmpty()) out.add(dec(s));
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
