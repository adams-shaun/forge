// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.zone.ZoneType;

/**
 * Three xproof Forge decision_shape clusters where gorge's answer is right and
 * the driver could not turn it into Forge's ask. Each fixture is the
 * forge-export request of the real find's item, with gorge's decisions:
 *
 * <ul>
 * <li>Mana Seism, "Sacrifice any number of lands" ({@code Optional$ True}):
 *     gorge logs the whole choice as ONE 0..N choose_n with pick kind
 *     "sacrifice"; Forge asks a yes/no ("Do you want to sacrifice?") before
 *     choosePermanentsToSacrifice. The confirm must read the pick without
 *     consuming it, so the pick reaches the following sacrifice ask.</li>
 * <li>Toils of Night and Day, two "you may tap or untap target permanent":
 *     gorge logs one choose_n per target with pick kind "tap" or "untap";
 *     Forge asks chooseBinary(TapOrUntap), where true means tap.</li>
 * <li>Browbeat: each payer is offered "have Browbeat deal 5 damage to them".
 *     p0 pays (takes the damage) and gorge logs no answer for p1, because it
 *     asks the payers in turn only until one pays; Forge asks every payer and
 *     passes alreadyPaid to the later ones. A later payer with no decision
 *     declines without a miss.</li>
 * </ul>
 */
public class OptionalAskTest {

    /** A replayed fixture: the machine (for board refs) and its result row
     * (for notes and checkpoints). */
    private static final class Replay {
        final StepMachine machine;
        final ResultRow row;

        Replay(StepMachine machine, ResultRow row) {
            this.machine = machine;
            this.row = row;
        }
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(OptionalAskTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("optional").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("optional").resolve(name);
    }

    private static Replay replay(String name) throws Exception {
        Bootstrap.init(TestRes.res());
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "strict replay failed: " + row.harness);
        assertNull(row.strictMiss, "strict miss: " + row.strictMiss);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        return new Replay(m, row);
    }

    private static JsonObject checkpoint(ResultRow row, String checkpoint) {
        for (JsonObject snap : row.snapshots) {
            JsonElement cp = snap.get("checkpoint");
            if (cp != null && checkpoint.equals(cp.getAsString())) {
                return snap;
            }
        }
        return null;
    }

    @Test
    public void optionalSacrificeConfirmReadsThePickWithoutConsumingIt() throws Exception {
        Replay r = replay("mana-seism-optional-sacrifice.jsonl");

        // The recorded pick is one Mountain; Forge's confirm is yes and the
        // following choosePermanentsToSacrifice must consume the same pick.
        assertTrue(r.machine.card("p0:Mountain").isInZone(ZoneType.Graveyard),
                "the picked Mountain is sacrificed");
        CardCollectionView battlefield = r.machine.seats[0].getCardsIn(ZoneType.Battlefield);
        assertEquals(battlefield.size(), 3, "three Mountains remain");
        assertTrue(r.row.notes.stream().anyMatch(n -> n.equals("sacrifice-confirm step 4: yes [Mountain]")),
                "the sacrifice confirm answers yes from the pick, notes: " + r.row.notes);
        assertTrue(r.row.notes.stream().anyMatch(n -> n.equals("pick step 4 sacrifice: [p0:Mountain]")),
                "the pick reaches choosePermanentsToSacrifice, notes: " + r.row.notes);
        assertFalse(r.row.notes.stream().anyMatch(n -> n.startsWith("loose: AI answered yesno")),
                "the confirm is not a loose miss, notes: " + r.row.notes);
    }

    @Test
    public void tapOrUntapBinaryAnswersFromThePickKind() throws Exception {
        Replay r = replay("toils-tap-or-untap.jsonl");

        // Both targets were tapped for mana, then both were chosen to untap;
        // the third Island still pays for the spell.
        JsonObject step5 = checkpoint(r.row, "step 5 (pass)");
        assertNotNull(step5, "step-5 snapshot");
        int tapped = 0;
        int untapped = 0;
        for (JsonElement e : step5.getAsJsonArray("permanents")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("controller").getAsInt() != 0 || !"Island".equals(p.get("name").getAsString())) {
                continue;
            }
            if (p.get("tapped").getAsBoolean()) {
                tapped++;
            } else {
                untapped++;
            }
        }
        assertEquals(tapped, 1, "p0 tapped Islands at step 5: " + step5);
        assertEquals(untapped, 2, "p0 untapped Islands at step 5: " + step5);
        assertEquals(r.row.notes.stream()
                        .filter(n -> n.equals("binary step 5 (TapOrUntap): untap [Untap Island]")).count(),
                2, "both recorded untaps answer a TapOrUntap binary, notes: " + r.row.notes);
        assertFalse(r.row.notes.stream().anyMatch(n -> n.startsWith("loose: AI answered binary")),
                "the binary asks are not loose misses, notes: " + r.row.notes);
    }

    @Test
    public void laterPayerAfterOnePaidDeclinesWithoutAMiss() throws Exception {
        Replay r = replay("browbeat-later-payer.jsonl");

        // p0 paid the unless cost by taking 5 damage, so no one draws; p1 has
        // no decision because gorge stops asking once a payer paid.
        assertEquals(r.machine.seats[0].getLife(), 15, "p0 took Browbeat's 5 damage");
        assertEquals(r.machine.seats[1].getLife(), 20, "p1 takes nothing");
        assertEquals(r.machine.seats[1].getCardsIn(ZoneType.Hand).size(), 0, "no one draws three cards");
        assertTrue(r.row.notes.stream().anyMatch(n -> n.equals("unless-pay step 5: Take 5 damage paid=true")),
                "p0's recorded pay is consumed, notes: " + r.row.notes);
        assertTrue(r.row.notes.stream()
                        .anyMatch(n -> n.equals("unless-pay step 5: p1 declines (an earlier payer paid)")),
                "p1 declines because an earlier payer paid, notes: " + r.row.notes);
        assertFalse(r.row.notes.stream().anyMatch(n -> n.startsWith("loose: AI answered unless-pay")),
                "the later payer is not a loose miss, notes: " + r.row.notes);
    }

    @Test
    public void emptyTapOrUntapAnswerReadsAsTap() throws Exception {
        Replay r = replay("toils-tap-or-untap-empty.jsonl");

        // gorge's taporuntap contract: an empty answer reads as "tap" (the
        // state-changing first option), so both already-tapped Islands stay
        // tapped and the empty decisions are consumed, not left over.
        assertEquals(r.row.notes.stream()
                        .filter(n -> n.equals("binary step 5 (TapOrUntap): tap []")).count(),
                2, "both empty answers read as tap, notes: " + r.row.notes);
        JsonObject step5 = checkpoint(r.row, "step 5 (pass)");
        assertNotNull(step5, "step-5 snapshot");
        for (JsonElement e : step5.getAsJsonArray("permanents")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("controller").getAsInt() == 0 && "Island".equals(p.get("name").getAsString())) {
                assertTrue(p.get("tapped").getAsBoolean(), "no Island untapped by an empty answer: " + step5);
            }
        }
    }

    @Test
    public void strictOptionalSacrificeYesConsumesTheModeAndLeavesThePick() throws Exception {
        Replay r = replay("strict-sacrifice-yes.jsonl");

        // Optional$ + StrictAmount$: gorge logs a sacrifice_optional mode ask
        // before the exact pick. The yes is read from it and consumed; the
        // exact pick still reaches choosePermanentsToSacrifice.
        assertTrue(r.machine.card("p0:Mountain").isInZone(ZoneType.Graveyard), "the picked Mountain is sacrificed");
        assertTrue(r.row.notes.stream()
                        .anyMatch(n -> n.equals("sacrifice-confirm step 4: yes (strict [Sacrifice 1 permanent(s)])")),
                "the strict confirm answers yes from the mode ask, notes: " + r.row.notes);
        assertTrue(r.row.notes.stream().anyMatch(n -> n.equals("pick step 4 sacrifice: [p0:Mountain]")),
                "the exact pick reaches choosePermanentsToSacrifice, notes: " + r.row.notes);
    }

    @Test
    public void strictOptionalSacrificeDeclineSacrificesNothing() throws Exception {
        Replay r = replay("strict-sacrifice-no.jsonl");

        assertEquals(r.machine.seats[0].getCardsIn(ZoneType.Battlefield).size(), 4,
                "a declined strict sacrifice keeps every Mountain");
        assertTrue(r.row.notes.stream()
                        .anyMatch(n -> n.equals("sacrifice-confirm step 4: no (strict [Don't sacrifice])")),
                "the strict confirm answers no from the mode ask, notes: " + r.row.notes);
    }
}
