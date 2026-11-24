// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.game.card.Card;
import forge.game.card.CardFactory;

/**
 * xproof Forge decision_shape cluster "unconsumed gorge decision(s): order[bottom]
 * [Plains]" (Dewdrop Spy, Merfolk Observer, Saheeli's Silverwing). Each looks
 * at the top card of target player's library (DB$ RearrangeTopOfLibrary |
 * NumCards$ 1); gorge logs the put-back as an "order" decision with one pick.
 * Forge's RearrangeTopOfLibraryEffect calls orderMoveToZoneList with one card,
 * which ScriptedController used to return at once without taking the pin. A
 * one-card order is no choice and both engines leave the card where it is, so
 * the driver consumes the pin and changes nothing.
 */
public class LoneCardOrderTest {

    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Path fixture(String name) {
        try {
            Path p = Path.of(LoneCardOrderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (p != null && !Files.isDirectory(p.resolve("fixtures"))) {
                p = p.getParent();
            }
            if (p != null) {
                return p.resolve("fixtures").resolve("unmapped").resolve(name);
            }
        } catch (Exception ignored) {
            // Fall through to the res-relative path (a jar or a stripped tree).
        }
        Path res = Path.of(TestRes.res()).toAbsolutePath().normalize();
        return res.getParent().getParent().resolve("forge-oracle").resolve("fixtures").resolve("unmapped").resolve(name);
    }

    private static StepMachine replay(String name) throws Exception {
        Request req;
        try (BufferedReader in = Files.newBufferedReader(fixture(name), StandardCharsets.UTF_8)) {
            req = Request.parse(in.readLine());
        }
        StepMachine m = new StepMachine(req, true);
        ResultRow row = m.run();
        assertNull(row.harness, "harness row: " + row.harness);
        assertNull(row.leftover, "unconsumed gorge decision: " + row.leftover);
        return m;
    }

    @Test
    public void merfolkObserverLookAtOneCardConsumesTheOrder() throws Exception {
        StepMachine m = replay("lone-card-order-merfolk-observer.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("order step ") && n.contains("order to Library (one card")
                        && n.endsWith("[Plains]")),
                "the one-pick order is consumed and noted, notes: " + m.notes);
    }

    @Test
    public void saheelisSilverwingLookAtOneCardConsumesTheOrder() throws Exception {
        StepMachine m = replay("lone-card-order-saheelis-silverwing.jsonl");
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("order step ") && n.contains("order to Library (one card")),
                "the one-pick order is consumed and noted, notes: " + m.notes);
    }

    private static StepMachine machine(String decisions) {
        Request req = Request.parse("{\"id\":\"order\",\"item\":{\"steps\":[{\"op\":\"pass\"}]},"
                + "\"gorge_decisions\":" + decisions + "}");
        StepMachine m = new StepMachine(req, false);
        m.stepIdx = 0;
        m.build();
        return m;
    }

    private static Card card(StepMachine m, String name) {
        Card c = CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), m.seats[0], m.game);
        c.setGameTimestamp(m.game.getNextTimestamp());
        return c;
    }

    private static final String LONE = "[{\"step\":0,\"seat\":0,\"kind\":\"order\",\"picks\":[\"Plains\"],"
            + "\"pick_kinds\":[\"bottom\"],\"resume\":\"arrange\",\"gorge_kind\":\"arrange\",\"options\":1,\"min\":1,\"max\":1}]";

    @Test
    public void aOneCardOrderThatNamesAnotherCardIsLeftAlone() {
        StepMachine m = machine(LONE);
        List<Card> one = List.of(card(m, "Island"));
        assertEquals(m.orderCards(0, one, "order to Library"), one, "the card stays where it is");
        assertNotNull(m.dq.leftover(), "a pick that names another card belongs to another ask");
    }

    @Test
    public void aOneCardOrderThatNamesTheCardIsConsumed() {
        StepMachine m = machine(LONE);
        List<Card> one = List.of(card(m, "Plains"));
        assertEquals(m.orderCards(0, one, "order to Library"), one);
        assertNull(m.dq.leftover());
    }
}
