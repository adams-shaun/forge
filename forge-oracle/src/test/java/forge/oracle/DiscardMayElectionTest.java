// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.game.card.Card;
import forge.game.zone.ZoneType;

/**
 * An xproof Forge decision_shape cluster (Evie Frye, Glacial Dragonhunt). Both
 * scripts end in DB$ Discard | Mode$ TgtChoose | Optional$ True | NumCards$ 1:
 * "you may discard a card" is one 0..1 pick in Forge (DiscardEffect min = 0).
 * gorge poses it as two asks (effDiscardTgtChoose, resume discard_may): a
 * yes/no election, then the card pick, which it skips when the options are
 * forced. The driver used to look only for the card pick, so the election was
 * left unconsumed and the loose AI picked a card (strict_miss "Missing discard
 * answer ... 0..1 to pick" with a leftover choose_n[yes]).
 *
 * <p>The fixture is the exported xs:XP:evie-frye:f0a0:s1 request: Island and
 * Evie Frye in play, an empty hand, {1},{T}: draw a card, then discard a card
 * (Optional$ True in the Forge script), with the "yes" election at step 3.
 */
public class DiscardMayElectionTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(DiscardMayElectionTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("discard-may").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("discard-may").resolve(name);
    }

    private static JsonObject fixtureJson(String name) throws Exception {
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            return JsonParser.parseString(in.readLine()).getAsJsonObject();
        }
    }

    private static JsonObject election(JsonObject raw) {
        return raw.getAsJsonArray("gorge_decisions").get(0).getAsJsonObject();
    }

    private static StepMachine[] replay(JsonObject raw, ResultRow[] row) {
        StepMachine m = new StepMachine(Request.parse(raw.toString()), true);
        row[0] = m.run();
        return new StepMachine[] {m};
    }

    private static List<String> names(StepMachine m, ZoneType z) {
        return m.seats[0].getCardsIn(z).stream().map(Card::getName).sorted().collect(Collectors.toList());
    }

    private static void assertClean(ResultRow row) {
        assertNull(row.harness, "replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
    }

    /** gorge's yes election with one card in hand: the driver takes the
     * election and the 1..1 pick over the lone card is forced, so the drawn
     * Island is discarded with no strict miss and no leftover. */
    @Test
    public void yesElectionDiscardsTheLoneCard() throws Exception {
        ResultRow[] row = new ResultRow[1];
        StepMachine m = replay(fixtureJson("evie-frye-yes.jsonl"), row)[0];

        assertClean(row[0]);
        assertTrue(row[0].notes.stream().anyMatch(n -> n.equals("discard election step 3: yes")),
                "the election is consumed, notes: " + row[0].notes);
        assertEquals(names(m, ZoneType.Hand), List.of(), "the drawn card was discarded");
        assertEquals(names(m, ZoneType.Graveyard), List.of("Island"));
    }

    /** gorge's no election: nothing is discarded, and the 0..1 ask is
     * answered empty rather than by the AI. */
    @Test
    public void noElectionDiscardsNothing() throws Exception {
        JsonObject raw = fixtureJson("evie-frye-yes.jsonl");
        JsonObject d = election(raw);
        JsonArray label = new JsonArray();
        label.add("No — don't discard");
        JsonArray idx = new JsonArray();
        idx.add(1);
        JsonArray kind = new JsonArray();
        kind.add("no");
        d.add("picks", label);
        d.add("pick_refs", label.deepCopy());
        d.add("pick_idx", idx);
        d.add("pick_kinds", kind);
        d.addProperty("first", "No — don't discard");
        ResultRow[] row = new ResultRow[1];
        StepMachine m = replay(raw, row)[0];

        assertClean(row[0]);
        assertTrue(row[0].notes.stream().anyMatch(n -> n.equals("discard election step 3: no")),
                "the election is consumed, notes: " + row[0].notes);
        assertEquals(names(m, ZoneType.Hand), List.of("Island"), "the drawn card was kept");
        assertEquals(names(m, ZoneType.Graveyard), List.of());
    }

    /** A yes with two cards in hand is not forced: gorge's card pick follows
     * the election and names the Mountain, so the drawn Island stays. */
    @Test
    public void yesElectionThenGorgesCardPick() throws Exception {
        JsonObject raw = fixtureJson("evie-frye-yes.jsonl");
        JsonArray hand = new JsonArray();
        hand.add("Mountain");
        raw.getAsJsonObject("item").getAsJsonObject("setup").getAsJsonObject("p0").add("hand", hand);
        JsonObject pick = new JsonObject();
        pick.addProperty("step", 3);
        pick.addProperty("seat", 0);
        pick.addProperty("kind", "choose_n");
        pick.addProperty("resume", "discard");
        pick.addProperty("gorge_kind", "choose");
        JsonArray mountain = new JsonArray();
        mountain.add("Mountain");
        JsonArray ref = new JsonArray();
        ref.add("p0:Mountain");
        JsonArray zero = new JsonArray();
        zero.add(0);
        JsonArray kind = new JsonArray();
        kind.add("discard");
        pick.add("picks", mountain);
        pick.add("pick_idx", zero);
        pick.add("pick_refs", ref);
        pick.add("pick_kinds", kind);
        pick.addProperty("min", 1);
        pick.addProperty("max", 1);
        raw.getAsJsonArray("gorge_decisions").add(pick);
        ResultRow[] row = new ResultRow[1];
        StepMachine m = replay(raw, row)[0];

        assertClean(row[0]);
        assertEquals(names(m, ZoneType.Hand), List.of("Island"), "gorge's pick discarded the Mountain");
        assertEquals(names(m, ZoneType.Graveyard), List.of("Mountain"));
    }
}
