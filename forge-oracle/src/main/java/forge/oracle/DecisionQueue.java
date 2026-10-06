// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * gorge's decision log (OracleResult.Decisions, rules/oracle_snapshot.go
 * OracleDecision) as the answer source for Forge's questions. Answers are
 * consumed in log order within a step (step -1 is setup). Answers are inputs,
 * not expectations: both engines answer alike, and a question only one engine
 * poses surfaces as a strict miss (Forge asked, gorge did not) or a leftover
 * (gorge asked, Forge did not), exactly as for XMage (DESIGN 6.5).
 */
public final class DecisionQueue {
    private static final Pattern X_LABEL = Pattern.compile("^\\s*X\\s*=\\s*(-?\\d+)\\s*$");

    /** One OracleDecision. */
    public static final class Decision {
        public final int step;
        public final int seat;
        public final String kind;
        public final String gorgeKind;
        public final String via;
        public final String resume;
        public final List<String> picks;
        public final List<String> pickRefs;
        public final List<String> objectPicks;
        public final List<String> pickKinds;
        public final int min;
        public final int max;
        public final int divided;
        boolean consumed;

        Decision(JsonObject o) {
            step = intOf(o, "step", 0);
            seat = intOf(o, "seat", 0);
            kind = Request.str(o, "kind");
            gorgeKind = Request.str(o, "gorge_kind");
            via = Request.str(o, "via");
            resume = Request.str(o, "resume");
            picks = Request.strings(o, "picks");
            pickRefs = Request.strings(o, "pick_refs");
            objectPicks = Request.strings(o, "object_picks");
            pickKinds = Request.strings(o, "pick_kinds");
            min = intOf(o, "min", 0);
            max = intOf(o, "max", 0);
            divided = intOf(o, "divided", 0);
        }

        public boolean isConsumed() {
            return consumed;
        }

        /** The object refs of a pick: object_picks when present, else the
         * pick_refs that look like refs. */
        public List<String> refs() {
            // pick_refs carries players too ("p1"); object_picks only objects.
            // A charm's one target ask mixes both (Brigid's Command).
            List<String> out = new ArrayList<>();
            for (String r : pickRefs) {
                if (RefTable.parseSeat(r.contains(":") ? r.substring(0, r.indexOf(':')) : r) >= 0) {
                    out.add(r);
                }
            }
            if (out.size() >= objectPicks.size() && out.containsAll(objectPicks)) {
                return out;
            }
            return objectPicks;
        }

        public String describe() {
            String k = kind + (pickKinds.isEmpty() ? "" : pickKinds.toString());
            return "step " + step + " p" + seat + " " + k + " " + picks;
        }
    }

    private final List<Decision> all = new ArrayList<>();

    public DecisionQueue(List<JsonObject> raw) {
        for (JsonObject o : raw) {
            all.add(new Decision(o));
        }
    }

    public List<Decision> all() {
        return Collections.unmodifiableList(all);
    }

    /** The first unconsumed decision at step that matches, consumed; or null. */
    public Decision take(int step, Predicate<Decision> match) {
        Decision d = peek(step, match);
        if (d != null) {
            d.consumed = true;
        }
        return d;
    }

    public Decision peek(int step, Predicate<Decision> match) {
        for (Decision d : all) {
            if (!d.consumed && d.step == step && match.test(d)) {
                return d;
            }
        }
        return null;
    }

    /** gorge's payment-window asks (contract, gorge cmd/oraclediff/forge.go):
     * the hybrid/Phyrexian pip choice (pick kinds pay_&lt;C&gt;, pay_generic,
     * pay_life) and a mana ability activated while paying (pick kind
     * "activate"). Forge pays from the pool and poses neither, so they are
     * never leftover. */
    public static boolean isPaymentWindow(Decision d) {
        if (d.pickKinds.isEmpty()) {
            return false;
        }
        for (String k : d.pickKinds) {
            if (!k.startsWith("pay_") && !k.equals("activate")) {
                return false;
            }
        }
        return true;
    }

    public List<Decision> unconsumed() {
        List<Decision> out = new ArrayList<>();
        for (Decision d : all) {
            if (!d.consumed && !isPaymentWindow(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** The row's leftover text, or null when every decision was used. */
    public String leftover() {
        List<Decision> u = unconsumed();
        if (u.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder("unconsumed gorge decision(s): ");
        for (int i = 0; i < u.size(); i++) {
            if (i > 0) {
                b.append("; ");
            }
            b.append(u.get(i).describe());
        }
        return b.toString();
    }

    // ---- classification ----------------------------------------------------

    public static boolean isTarget(Decision d) {
        return d.kind.equals("target");
    }

    /** An {X} announcement: gorge's "choose" with pick kind "x" ("X = 2"). */
    public static boolean isX(Decision d) {
        return d.kind.equals("choose_n") && (d.pickKinds.contains("x") || (!d.picks.isEmpty() && X_LABEL.matcher(d.picks.get(0)).matches()));
    }

    public static Integer xValue(Decision d) {
        if (d.picks.isEmpty()) {
            return null;
        }
        Matcher m = X_LABEL.matcher(d.picks.get(0));
        return m.matches() ? Integer.valueOf(m.group(1)) : null;
    }

    /** A yes/no answer: a trigger_optional "yesno" or a choose whose single
     * pick is a yes/no label. */
    public static boolean isYesNo(Decision d) {
        if (d.kind.equals("yesno") || isPlayChoice(d)) {
            return true;
        }
        if (!d.kind.equals("choose_n") || d.picks.size() != 1) {
            return false;
        }
        return yesNoOf(d.picks.get(0), d.pickKinds.isEmpty() ? "" : d.pickKinds.get(0)) != null;
    }

    /** true for yes, false for no, null when the label is neither. */
    public static Boolean yesNoOf(String label, String pickKind) {
        String k = pickKind.toLowerCase(Locale.ROOT);
        if (k.equals("yes") || k.equals("opening_yes") || k.equals("trigger_cost_pay") || k.equals("trigger_cost_tap")) {
            return Boolean.TRUE;
        }
        if (k.equals("no") || k.equals("opening_no") || k.equals("trigger_cost_decline")) {
            return Boolean.FALSE;
        }
        String l = label.trim().toLowerCase(Locale.ROOT);
        if (l.equals("yes") || l.startsWith("yes ") || l.startsWith("yes—") || l.startsWith("yes —")) {
            return Boolean.TRUE;
        }
        if (l.equals("no") || l.startsWith("no ") || l.startsWith("no—") || l.startsWith("no —")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** gorge's "you may play/cast it" ask (resume "play"): an empty answer
     * declines, a pick plays. */
    public static boolean isPlayChoice(Decision d) {
        return d.resume.equals("play") && (d.kind.equals("mode") || d.kind.equals("choose_n"));
    }

    public static boolean yes(Decision d) {
        if (isPlayChoice(d)) {
            return !d.picks.isEmpty();
        }
        if (d.picks.isEmpty()) {
            return false; // an empty answer to an optional ask declines it
        }
        Boolean b = yesNoOf(d.picks.get(0), d.pickKinds.isEmpty() ? "" : d.pickKinds.get(0));
        return b != null && b;
    }

    public static boolean isMode(Decision d) {
        return d.kind.equals("mode") && (d.pickKinds.isEmpty() || d.pickKinds.contains("mode"));
    }

    /** A choose over game objects (cards, permanents) whose picks resolve by
     * ref. A yes/no, payment, gift, X or label pick that merely names its
     * source object is not one. */
    public static boolean isObjectChoice(Decision d) {
        if (!(d.kind.equals("choose_n") || d.kind.equals("mode")) || d.refs().isEmpty()) {
            return false;
        }
        for (String k : d.pickKinds) {
            if (NON_OBJECT_PICKS.contains(k) || k.startsWith("pay_")) {
                return false;
            }
        }
        return true;
    }

    /** gorge's empty answer to an object choice ("choose nothing", a
     * fallback that picked no card). */
    public static boolean isEmptyChoice(Decision d) {
        return d.kind.equals("choose_n") && d.picks.isEmpty() && d.pickKinds.isEmpty();
    }

    static final java.util.Set<String> NON_OBJECT_PICKS = java.util.Set.of("yes", "no", "opening_yes", "opening_no",
            "trigger_cost_decline", "trigger_cost_pay", "gift_decline", "activate", "x", "altaddcost", "primary", "type", "mode", "color", "name");

    /** A trigger or optional-cost payment answer: true pays, false declines. */
    public static Boolean payOf(Decision d) {
        if (d.pickKinds.contains("trigger_cost_pay")) {
            return Boolean.TRUE;
        }
        if (d.pickKinds.contains("trigger_cost_decline")) {
            return Boolean.FALSE;
        }
        return null;
    }

    static int intOf(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return def;
        }
        return e.getAsInt();
    }
}
