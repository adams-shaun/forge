// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The driver contract without Forge or a card database, ported from the
 * XMage driver's ScenarioReplayHarnessRowTest (one malformed scenario fails
 * its own row and the batch goes on) and the turn/steps parts of
 * ScenarioReplayDriverContractTest, plus the strict-then-loose retry and the
 * row shape gorge's oraclediff.XResult decodes.
 */
public class DriverContractTest {

    /** A stub engine: records calls, throws for chosen ids, strict-misses for others. */
    static final class Stub implements ScenarioReplay.Engine {
        final List<String> calls = new ArrayList<>();

        @Override
        public ResultRow replayOnce(Request req, boolean strict) {
            calls.add(req.id + (strict ? "/strict" : "/loose"));
            if (req.id.equals("boom")) {
                throw new ClassCastException("JsonNull cannot be cast to JsonArray");
            }
            ResultRow r = new ResultRow();
            r.id = req.id;
            r.name = req.name;
            r.strict = strict;
            if (req.id.equals("miss") && strict) {
                r.harness = new StrictMiss("target", 0, "Shock").getMessage();
            }
            if (req.id.equals("missboom") && !strict) {
                throw new IllegalStateException("loose crashed");
            }
            if (req.id.equals("missboom") && strict) {
                r.harness = new StrictMiss("mode", 1, "").getMessage();
            }
            return r;
        }
    }

    private static List<JsonObject> run(ScenarioReplay r, String in) throws Exception {
        StringWriter out = new StringWriter();
        int n = ScenarioReplay.replayLines(r, new BufferedReader(new StringReader(in)), new PrintWriter(out, true));
        List<JsonObject> rows = new ArrayList<>();
        for (String l : out.toString().split("\\R")) {
            if (!l.isBlank()) {
                rows.add(JsonParser.parseString(l).getAsJsonObject());
            }
        }
        assertEquals(rows.size(), n, "row count");
        return rows;
    }

    @Test
    public void malformedLinesFailTheirOwnRowAndTheBatchContinues() throws Exception {
        Stub stub = new Stub();
        ScenarioReplay r = new ScenarioReplay(stub, "abc123");
        String in = "{\"id\":\"bad-json\",\"name\":\"x\",\n"
                + "[1,2,3]\n"
                + "\n"
                + "{\"id\":\"nullsteps\",\"name\":\"n\",\"steps\":null}\n"
                + "{\"id\":\"nosteps\",\"name\":\"m\"}\n"
                + "{\"id\":\"boom\",\"name\":\"b\",\"steps\":[]}\n"
                + "{\"id\":\"good\",\"name\":\"ordinary\",\"steps\":[]}\n";
        List<JsonObject> rows = run(r, in);
        assertEquals(rows.size(), 6, "blank lines are skipped, every other line gets a row");
        assertEquals(rows.get(0).get("id").getAsString(), "bad-json", "the id is recovered from a truncated line");
        assertTrue(rows.get(0).get("harness").getAsString().contains("malformed request line"));
        assertFalse(rows.get(1).has("id"), "a non-object line has no id to report");
        assertTrue(rows.get(1).get("harness").getAsString().contains("malformed request line"));
        assertEquals(rows.get(2).get("id").getAsString(), "nullsteps");
        assertTrue(rows.get(2).get("harness").getAsString().contains("no steps array"));
        assertEquals(rows.get(3).get("id").getAsString(), "nosteps");
        assertTrue(rows.get(3).get("harness").getAsString().contains("no steps array"));
        assertEquals(rows.get(4).get("id").getAsString(), "boom");
        assertTrue(rows.get(4).get("harness").getAsString().contains("ClassCastException"));
        assertEquals(rows.get(5).get("id").getAsString(), "good");
        assertFalse(rows.get(5).has("harness"));
        // Neither steps-less scenario reached Forge.
        assertEquals(stub.calls, List.of("boom/strict", "good/strict"));
    }

    @Test
    public void badTurnsAreHarnessRows() throws Exception {
        Stub stub = new Stub();
        ScenarioReplay r = new ScenarioReplay(stub, "");
        String[] bad = {"0", "101", "1.5", "\"2\"", "99999999999", "-1", "true", "[1]"};
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < bad.length; i++) {
            in.append("{\"id\":\"t").append(i).append("\",\"turn\":").append(bad[i]).append(",\"steps\":[]}\n");
        }
        in.append("{\"id\":\"ok\",\"turn\":2,\"steps\":[]}\n");
        in.append("{\"id\":\"null\",\"turn\":null,\"steps\":[]}\n");
        List<JsonObject> rows = run(r, in.toString());
        for (int i = 0; i < bad.length; i++) {
            assertTrue(rows.get(i).get("harness").getAsString().contains("invalid scenario turn"), "turn " + bad[i]);
        }
        assertFalse(rows.get(bad.length).has("harness"));
        assertFalse(rows.get(bad.length + 1).has("harness"));
        assertEquals(stub.calls, List.of("ok/strict", "null/strict"));
    }

    @Test
    public void scenarioTurnMatchesTheXMageDriver() {
        assertEquals(Request.scenarioTurn(JsonParser.parseString("{}").getAsJsonObject()), 1);
        assertEquals(Request.scenarioTurn(JsonParser.parseString("{\"turn\":null}").getAsJsonObject()), 1);
        assertEquals(Request.scenarioTurn(JsonParser.parseString("{\"turn\":7}").getAsJsonObject()), 7);
        assertEquals(Request.scenarioTurn(JsonParser.parseString("{\"turn\":100}").getAsJsonObject()), 100);
        for (String b : new String[] {"2.0", "1e1", "4294967297"}) {
            try {
                Request.scenarioTurn(JsonParser.parseString("{\"turn\":" + b + "}").getAsJsonObject());
                fail("turn " + b + " accepted");
            } catch (IllegalArgumentException expected) {
                // a fraction or an overflow is not truncated or wrapped
            }
        }
    }

    @Test
    public void strictMissRetriesLoose() throws Exception {
        Stub stub = new Stub();
        ScenarioReplay r = new ScenarioReplay(stub, "");
        List<JsonObject> rows = run(r, "{\"id\":\"miss\",\"steps\":[]}\n{\"id\":\"missboom\",\"steps\":[]}\n");
        JsonObject miss = rows.get(0);
        assertFalse(miss.get("strict").getAsBoolean(), "the loose replay's row is reported");
        assertTrue(miss.get("strict_miss").getAsString().startsWith("Missing target answer at step 0"));
        assertFalse(miss.has("harness"));
        JsonObject boom = rows.get(1);
        assertFalse(boom.get("strict").getAsBoolean());
        assertTrue(boom.get("harness").getAsString().contains("loose crashed"));
        assertTrue(boom.get("strict_miss").getAsString().startsWith("Missing mode answer"));
        assertEquals(stub.calls, List.of("miss/strict", "miss/loose", "missboom/strict", "missboom/loose"));
    }

    @Test
    public void rowShapeIsXResultPlusForgeFields() throws Exception {
        ScenarioReplay r = new ScenarioReplay(new Stub(), "deadbeef");
        JsonObject row = run(r, "{\"id\":\"good\",\"name\":\"g\",\"steps\":[]}\n").get(0);
        for (String k : new String[] {"name", "id", "strict", "ms", "snapshots", "engine", "forge_ref", "notes"}) {
            assertTrue(row.has(k), "row lacks " + k);
        }
        assertEquals(row.get("engine").getAsString(), "forge");
        assertEquals(row.get("forge_ref").getAsString(), "deadbeef");
        assertTrue(row.get("snapshots").isJsonArray());
        assertNull(row.get("strict_miss"));
    }

    @Test
    public void sidecarRequestIsReadVerbatim() {
        Request q = Request.parse("{\"id\":\"S/cast-resolve/v1\",\"scenario_sha\":\"ab\",\"item\":{\"id\":\"S/cast-resolve/v1\",\"name\":\"gen1\","
                + "\"xmage_answers\":[null],\"steps\":[{\"op\":\"cast\"}],\"setup\":{\"p1\":{\"battlefield\":[\"Grizzly Bears\"]}}},"
                + "\"gorge_decisions\":[{\"step\":0,\"seat\":0,\"kind\":\"target\",\"pick_refs\":[\"p1:Grizzly Bears\"]}],"
                + "\"abilities\":{\"0\":\"AB$ Pump | Cost$ T\",\"x\":\"ignored\"}}");
        assertEquals(q.id, "S/cast-resolve/v1");
        assertEquals(q.name, "gen1");
        assertEquals(q.scenarioSha, "ab");
        assertEquals(q.steps().size(), 1);
        assertEquals(q.decisions.size(), 1);
        assertEquals(q.abilities.get(0), "AB$ Pump | Cost$ T");
        assertEquals(q.abilities.size(), 1);
        assertEquals(Request.strings(q.seat(1), "battlefield"), List.of("Grizzly Bears"));
        assertEquals(q.seat(0).size(), 0);
        // A bare Item (a hand-written fixture) is its own item.
        Request bare = Request.parse("{\"id\":\"b\",\"steps\":[]}");
        assertEquals(bare.id, "b");
        assertTrue(bare.decisions.isEmpty());
    }

    @Test
    public void stepsIsNullSafe() {
        assertEquals(Request.steps(JsonParser.parseString("{\"steps\":null}").getAsJsonObject()).size(), 0);
        assertEquals(Request.steps(JsonParser.parseString("{}").getAsJsonObject()).size(), 0);
        assertEquals(Request.steps(JsonParser.parseString("{\"steps\":{}}").getAsJsonObject()).size(), 0);
        assertEquals(Request.steps(JsonParser.parseString("{\"steps\":[{},{}]}").getAsJsonObject()).size(), 2);
    }

    @Test
    public void seedIsDerivedFromTheScenarioId() {
        assertEquals(ForgeEngine.seed("Shock/cast-resolve/v1"), ForgeEngine.seed("Shock/cast-resolve/v1"));
        assertTrue(ForgeEngine.seed("Shock/cast-resolve/v1") != ForgeEngine.seed("Shock/cast-resolve/v2"));
    }
}
