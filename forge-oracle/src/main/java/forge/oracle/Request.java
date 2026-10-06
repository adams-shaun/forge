// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One input line of the Forge oracle driver: gorge's sidecar request
 * (DESIGN section 5.2) or, for hand-written fixtures, a bare scenario Item.
 *
 * <pre>
 * {"id": "...", "scenario_sha": "...", "item": {...the Item as gen wrote it...},
 *  "gorge_decisions": [...rules.OracleDecision...],
 *  "abilities": {"&lt;step index&gt;": "&lt;Face.Abilities[ability_index].Line&gt;"}}
 * </pre>
 *
 * The Item is read verbatim; the XMage-only fields (xmage_name,
 * xmage_answers, xmage_ability, xmage_target_skips) are ignored. Every
 * accessor is null-safe, so a malformed request fails its own scenario row
 * (ScenarioReplay) rather than the batch.
 */
public final class Request {
    public final JsonObject raw;
    public final JsonObject item;
    public final String id;
    public final String name;
    public final String scenarioSha;
    public final List<JsonObject> decisions;
    public final Map<Integer, String> abilities;

    private Request(JsonObject raw) {
        this.raw = raw;
        JsonElement it = raw.get("item");
        this.item = it != null && it.isJsonObject() ? it.getAsJsonObject() : raw;
        String rid = str(raw, "id");
        this.id = rid.isEmpty() ? str(item, "id") : rid;
        this.name = str(item, "name");
        this.scenarioSha = str(raw, "scenario_sha");
        List<JsonObject> ds = new ArrayList<>();
        JsonElement de = raw.get("gorge_decisions");
        if (de != null && de.isJsonArray()) {
            for (JsonElement e : de.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    ds.add(e.getAsJsonObject());
                }
            }
        }
        this.decisions = Collections.unmodifiableList(ds);
        Map<Integer, String> ab = new TreeMap<>();
        JsonElement ae = raw.get("abilities");
        if (ae != null && ae.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : ae.getAsJsonObject().entrySet()) {
                try {
                    ab.put(Integer.parseInt(e.getKey()), e.getValue().getAsString());
                } catch (RuntimeException ignored) {
                    // A non-integer key names no step; the activate step that
                    // needed it reports the missing line itself.
                }
            }
        }
        this.abilities = Collections.unmodifiableMap(ab);
    }

    /** Parses one line. Throws on JSON that is not an object. */
    public static Request parse(String line) {
        JsonElement e = JsonParser.parseString(line);
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("request line is not a JSON object");
        }
        return new Request(e.getAsJsonObject());
    }

    /** The scenario's steps: a missing key, JSON null or non-array is empty
     * (ScenarioReplay.steps in the XMage driver). */
    public JsonArray steps() {
        return steps(item);
    }

    public static JsonArray steps(JsonObject sc) {
        JsonElement e = sc == null ? null : sc.get("steps");
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /** The scenario turn, exactly as the XMage driver's scenarioTurn reads it
     * (ScenarioReplay.java:566-584): absent or null is 1; otherwise an
     * integer JSON number in 1..100, never a truncated fraction or a wrapped
     * overflow. */
    public int turn() {
        return scenarioTurn(item);
    }

    public static int scenarioTurn(JsonObject sc) {
        JsonElement value = sc == null ? null : sc.get("turn");
        if (value == null || value.isJsonNull()) {
            return 1;
        }
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.getAsString().matches("-?[0-9]+")) {
                int turn = Integer.parseInt(value.getAsString());
                if (turn >= 1 && turn <= 100) {
                    return turn;
                }
            }
        } catch (NumberFormatException ignored) {
            // Overflow is invalid, not an invitation to wrap to another turn.
        }
        throw new IllegalArgumentException("invalid scenario turn " + value + " (want integer 1..100)");
    }

    /** The setup object of seat i ("p0"/"p1"), or an empty object. */
    public JsonObject seat(int i) {
        JsonElement s = item.get("setup");
        if (s != null && s.isJsonObject()) {
            JsonElement p = s.getAsJsonObject().get("p" + i);
            if (p != null && p.isJsonObject()) {
                return p.getAsJsonObject();
            }
        }
        return new JsonObject();
    }

    public static String str(JsonObject o, String k) {
        if (o == null) {
            return "";
        }
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    public static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        JsonElement e = o == null ? null : o.get(k);
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x.isJsonPrimitive()) {
                    out.add(x.getAsString());
                }
            }
        }
        return out;
    }
}
