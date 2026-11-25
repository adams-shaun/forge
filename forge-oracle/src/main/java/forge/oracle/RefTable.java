// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scenario refs, exactly as gorge's runner reads them (rules/oracle_run.go
 * splitRef/resolve, DESIGN 6.6):
 *
 * <ul>
 * <li>"pN" is seat N;</li>
 * <li>"pN:Name" and "pN:Name#k" name a card. Setup binds each placement in
 *     gorge's order (per seat: battlefield, hand, graveyard, library, exile,
 *     command, library_top), the first of a name as "pN:Name" and the k-th as
 *     "pN:Name#k". A bound ref follows its card through every zone;</li>
 * <li>an unbound card ref is the k-th live object (creation order, which is
 *     Forge's card-id order, P0 S1) owned by seat N with that name;</li>
 * <li>"pN:token:Sub#k" is the k-th battlefield token CONTROLLED by seat N whose
 *     name contains Sub, case-insensitively.</li>
 * </ul>
 *
 * The table holds no Forge type, so it is unit-tested without a Game.
 */
public final class RefTable {
    /** A parsed ref. seat is -1 for a malformed one (parse throws instead). */
    public static final class Ref {
        public final int seat;
        public final String name;
        public final boolean token;
        public final int nth;
        public final boolean player;
        /** "pN:ability:Name[#k]": the k-th pending ability object on the
         * stack, controlled by seat N, whose source is the named card (gorge's
         * grammar for a stack ability, rules/oracle_run.go splitRef). name is
         * the source's name, not "ability:Name". */
        public final boolean ability;

        Ref(int seat, String name, boolean token, int nth, boolean player) {
            this(seat, name, token, nth, player, false);
        }

        Ref(int seat, String name, boolean token, int nth, boolean player, boolean ability) {
            this.seat = seat;
            this.name = name;
            this.token = token;
            this.nth = nth;
            this.player = player;
            this.ability = ability;
        }
    }

    /** What resolution needs to know about one game object. */
    public static final class Obj {
        public final int id;
        public final int owner;
        public final int controller;
        public final List<String> names;
        public final boolean token;
        public final boolean onBattlefield;

        public Obj(int id, int owner, int controller, List<String> names, boolean token, boolean onBattlefield) {
            this.id = id;
            this.owner = owner;
            this.controller = controller;
            this.names = names;
            this.token = token;
            this.onBattlefield = onBattlefield;
        }
    }

    private final Map<String, Integer> bound = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();

    /** Parses "pN", "pN:Name", "pN:Name#k", "pN:token:Sub#k". */
    public static Ref parse(String ref) {
        if (ref == null) {
            throw new HarnessError("null ref");
        }
        int colon = ref.indexOf(':');
        String seatPart = colon < 0 ? ref : ref.substring(0, colon);
        int seat = parseSeat(seatPart);
        if (seat < 0) {
            throw new HarnessError(colon < 0 ? "bad card ref " + quote(ref) + " (want pN:Name)" : "bad seat in ref " + quote(ref));
        }
        if (colon < 0) {
            return new Ref(seat, "", false, 1, true);
        }
        String name = ref.substring(colon + 1);
        boolean token = false;
        boolean ability = false;
        if (name.startsWith("token:")) {
            token = true;
            name = name.substring("token:".length());
        } else if (name.startsWith("ability:")) {
            ability = true;
            name = name.substring("ability:".length());
        }
        int nth = 1;
        int hash = name.lastIndexOf('#');
        if (hash >= 0) {
            int n;
            try {
                n = Integer.parseInt(name.substring(hash + 1));
            } catch (NumberFormatException e) {
                n = 0;
            }
            if (n < 1) {
                throw new HarnessError("bad ordinal in ref " + quote(ref));
            }
            name = name.substring(0, hash);
            nth = n;
        }
        return new Ref(seat, name, token, nth, false, ability);
    }

    /** True for an "ability:" ref ("p1:ability:Dark Sphere#2"). Malformed refs
     * are not ability refs; parse reports them. */
    public static boolean isAbilityRef(String ref) {
        try {
            return parse(ref).ability;
        } catch (HarnessError e) {
            return false;
        }
    }

    /** The nth (1-based) match of a stack listed top-first, counted from the
     * bottom: gorge ranks pending abilities in creation order, the oldest
     * first, and Forge's stack iterates top-first, so the same ability gets
     * the same k. Null when fewer than nth match. */
    public static <T> T nthPending(List<T> topFirst, java.util.function.Predicate<T> matches, int nth) {
        int seen = 0;
        for (int i = topFirst.size() - 1; i >= 0; i--) {
            if (matches.test(topFirst.get(i)) && ++seen == nth) {
                return topFirst.get(i);
            }
        }
        return null;
    }

    /** A seat ref "pN", or -1. */
    public static int parseSeat(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != 'p') {
            return -1;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(s.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Binds the next setup placement of name on seat to id and returns its
     * ref, numbering per seat and name across zones, as gorge's build does. */
    public String bind(int seat, String name, int id) {
        int k = counts.merge(seat + "|" + name, 1, Integer::sum);
        String ref = "p" + seat + ":" + name + (k > 1 ? "#" + k : "");
        bound.put(ref, id);
        return ref;
    }

    public Map<String, Integer> bound() {
        return Collections.unmodifiableMap(bound);
    }

    /** The bound id of ref, if setup bound it. */
    public Integer boundId(String ref) {
        return bound.get(ref);
    }

    /** Resolves a card ref against the live objects (any order; sorted by id
     * here). Returns the object id. Player refs are not card refs. */
    public int resolveCard(String ref, List<Obj> live) {
        Integer id = bound.get(ref);
        if (id != null) {
            return id;
        }
        Ref r = parse(ref);
        if (r.player) {
            throw new HarnessError("ref " + quote(ref) + " names a player, not an object");
        }
        List<Obj> objs = new ArrayList<>(live);
        objs.sort((a, b) -> Integer.compare(a.id, b.id));
        String lower = r.name.toLowerCase(Locale.ROOT);
        int seen = 0;
        for (Obj o : objs) {
            if (r.token) {
                if (!o.token || o.controller != r.seat || !o.onBattlefield || !anyContains(o.names, lower)) {
                    continue;
                }
            } else if (o.owner != r.seat || !o.names.contains(r.name)) {
                continue;
            }
            if (++seen == r.nth) {
                return o.id;
            }
        }
        throw new HarnessError("ref " + quote(ref) + " names no object");
    }

    /** The ref gorge's snapshot gives object {@code o} (oracle_snapshot.go
     * objRef): the smallest setup-bound ref that follows it, else positional --
     * "pN:Name" by its owner (a token: "pN:token:Name" by its controller), the
     * k-th live object of that name in creation order, "#k" from the second.
     * {@code name} is the object's name where it is now. When a setup ref of
     * that spelling is bound to another object, the first of a name is spelled
     * "#1" (which resolves positionally) so it does not resolve to the other. */
    public String refOf(Obj o, String name, List<Obj> live) {
        String best = null;
        for (Map.Entry<String, Integer> e : bound.entrySet()) {
            if (e.getValue() == o.id && (best == null || e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
            }
        }
        if (best != null) {
            return best;
        }
        int seat = o.token ? o.controller : o.owner;
        String lower = name.toLowerCase(Locale.ROOT);
        List<Obj> objs = new ArrayList<>(live);
        objs.sort((a, b) -> Integer.compare(a.id, b.id));
        int k = 0;
        for (Obj c : objs) {
            if (o.token) {
                if (!c.token || c.controller != seat || !c.onBattlefield || !anyContains(c.names, lower)) {
                    continue;
                }
            } else if (c.owner != seat || !c.names.contains(name)) {
                continue;
            }
            k++;
            if (c.id == o.id) {
                break;
            }
        }
        String base = "p" + seat + ":" + (o.token ? "token:" : "") + name;
        String ref = k > 1 ? base + "#" + k : base;
        Integer shadow = bound.get(ref);
        if (shadow != null && shadow != o.id && k <= 1) {
            ref = base + "#1";
        }
        return ref;
    }

    private static boolean anyContains(List<String> names, String lowerSub) {
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).contains(lowerSub)) {
                return true;
            }
        }
        return false;
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
