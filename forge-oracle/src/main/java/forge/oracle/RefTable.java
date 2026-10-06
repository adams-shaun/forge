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

        Ref(int seat, String name, boolean token, int nth, boolean player) {
            this.seat = seat;
            this.name = name;
            this.token = token;
            this.nth = nth;
            this.player = player;
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
        if (name.startsWith("token:")) {
            token = true;
            name = name.substring("token:".length());
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
        return new Ref(seat, name, token, nth, false);
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
