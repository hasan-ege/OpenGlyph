package org.openglyph.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Glyph automations: an ordered list of "if these conditions hold, show this toy" rules. While no
 * toy was picked with the Glyph Button, the first enabled rule whose conditions all hold puts its
 * toy on the matrix, for as long as they keep holding. No Android here, so the host tests cover it.
 *
 * Setting format (open_glyph_automations): rules separated by ';', each
 * {@code on|toy|cond,cond|pkg,pkg|start-end} (minutes of the day; "-" for no time range).
 */
public final class Automation {
    public enum Cond {
        /** Sound is playing (from one of {@link Rule#apps}, if any are listed). */
        SOUND,
        /** A Clock timer exists. */
        TIMER,
        /** A Live Update with progress is showing. */
        PROGRESS,
        /** A call is connected. */
        CALL,
        CHARGING,
        /** Lying screen down (the matrix faces up). */
        FACE_DOWN,
        SCREEN_OFF,
        SCREEN_ON,
        HEADPHONES,
    }

    /** What the phone is doing right now. */
    public static final class State {
        public boolean timer, progress, call, charging, faceDown, screenOff, headphones;
        /** Packages playing sound now (empty: silence). */
        public Set<String> soundApps = new LinkedHashSet<>();
        /** 0..1439. */
        public int minuteOfDay;

        boolean holds(Cond c, Set<String> apps) {
            switch (c) {
                case SOUND:
                    if (soundApps.isEmpty()) return false;
                    if (apps.isEmpty()) return true;
                    for (String p : soundApps) if (apps.contains(p)) return true;
                    return false;
                case TIMER: return timer;
                case PROGRESS: return progress;
                case CALL: return call;
                case CHARGING: return charging;
                case FACE_DOWN: return faceDown;
                case SCREEN_OFF: return screenOff;
                case SCREEN_ON: return !screenOff;
                case HEADPHONES: return headphones;
                default: return false;
            }
        }
    }

    public static final class Rule {
        public boolean enabled = true;
        public String toy;
        public final EnumSet<Cond> conds = EnumSet.noneOf(Cond.class);
        /** For {@link Cond#SOUND}: only these apps count (empty: any app). */
        public final Set<String> apps = new LinkedHashSet<>();
        /** Minutes of the day, start inclusive, end exclusive; -1 for no time range. */
        public int start = -1, end = -1;

        public Rule() {}

        public Rule(String toy, Cond... conds) {
            this.toy = toy;
            for (Cond c : conds) this.conds.add(c);
        }

        public boolean hasTime() {
            return start >= 0 && end >= 0 && start != end;
        }

        boolean inTime(int minute) {
            if (!hasTime()) return true;
            return start < end ? minute >= start && minute < end : minute >= start || minute < end;
        }

        /** All conditions (and the time range) hold. A rule with no condition never fires. */
        public boolean matches(State s) {
            if (!enabled || toy == null || toy.isEmpty()) return false;
            if (conds.isEmpty() && !hasTime()) return false;
            for (Cond c : conds) if (!s.holds(c, apps)) return false;
            return inTime(s.minuteOfDay);
        }

        public Rule copy() {
            Rule r = parseOne(formatOne(this));
            return r == null ? new Rule() : r;
        }
    }

    private Automation() {}

    /** The toy of the first rule that matches, or null. */
    public static String pick(List<Rule> rules, State s) {
        for (Rule r : rules) if (r.matches(s)) return r.toy;
        return null;
    }

    /** True if any enabled rule needs this condition (so its sensor must run). */
    public static boolean uses(List<Rule> rules, Cond c) {
        for (Rule r : rules) if (r.enabled && r.conds.contains(c)) return true;
        return false;
    }

    public static boolean usesTime(List<Rule> rules) {
        for (Rule r : rules) if (r.enabled && r.hasTime()) return true;
        return false;
    }

    /** First run: the call voice, the hourglass, the visualiser, Glyph Progress, in that order. */
    public static List<Rule> defaults() {
        List<Rule> out = new ArrayList<>();
        out.add(new Rule("callvoice", Cond.CALL));
        out.add(new Rule("hourglass", Cond.TIMER));
        out.add(new Rule("visualizer", Cond.SOUND));
        out.add(new Rule("progress", Cond.PROGRESS));
        return out;
    }

    public static String format(List<Rule> rules) {
        StringBuilder b = new StringBuilder();
        for (Rule r : rules) {
            if (b.length() > 0) b.append(';');
            b.append(formatOne(r));
        }
        return b.toString();
    }

    static String formatOne(Rule r) {
        StringBuilder conds = new StringBuilder();
        for (Cond c : r.conds) {
            if (conds.length() > 0) conds.append(',');
            conds.append(c.name().toLowerCase(Locale.ROOT));
        }
        return (r.enabled ? "1" : "0") + "|" + clean(r.toy) + "|" + conds + "|"
                + String.join(",", r.apps) + "|" + (r.hasTime() ? r.start + "-" + r.end : "-");
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[|;,]", "");
    }

    /** Parses the setting; null or empty gives the defaults, junk rules are skipped. */
    public static List<Rule> parse(String s) {
        if (s == null) return defaults();
        List<Rule> out = new ArrayList<>();
        for (String part : s.split(";")) {
            Rule r = parseOne(part);
            if (r != null) out.add(r);
        }
        return out;
    }

    static Rule parseOne(String part) {
        String[] f = part.split("\\|", -1);
        if (f.length < 5 || f[1].isEmpty()) return null;
        Rule r = new Rule();
        r.enabled = !"0".equals(f[0]);
        r.toy = f[1];
        for (String c : f[2].split(",")) {
            if (c.isEmpty()) continue;
            try {
                r.conds.add(Cond.valueOf(c.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // a condition from a newer version: drop it
            }
        }
        for (String p : f[3].split(",")) if (!p.isEmpty()) r.apps.add(p);
        if (f[4].contains("-") && !"-".equals(f[4])) {
            try {
                String[] t = f[4].split("-");
                r.start = Math.max(0, Math.min(1439, Integer.parseInt(t[0])));
                r.end = Math.max(0, Math.min(1439, Integer.parseInt(t[1])));
            } catch (RuntimeException e) {
                r.start = r.end = -1;
            }
        }
        return r;
    }
}
