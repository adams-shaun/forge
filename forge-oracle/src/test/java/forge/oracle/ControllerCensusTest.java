// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.testng.annotations.Test;

import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.ICostVisitor;
import forge.game.player.PlayerController;

/**
 * The reflection census (DESIGN 6.5): every abstract PlayerController method
 * is overridden by ScriptedController, or is a notification on the explicit
 * allowlist below. A Forge bump that adds a decision method fails here
 * instead of silently answering through the AI. The same census holds the
 * cost visitor: every card-choosing cost part is routed by
 * ScriptedCostDecision (a cost with nothing to choose keeps the AI's
 * deterministic answer and is listed as such).
 */
public class ControllerCensusTest {
    /** Notification-only methods: they inform, they decide nothing. */
    static final Map<String, String> NOTIFY = new TreeMap<>(Map.of(
            "reveal", "shows cards to the player",
            "notifyOfValue", "announces a chosen value",
            "revealAnte", "ante (never in a scenario)",
            "revealAISkipCards", "AI deck warnings",
            "revealUnsupported", "unsupported-card warnings",
            "autoPassCancel", "UI auto-pass state",
            "awaitNextInput", "UI input pacing",
            "cancelAwaitNextInput", "UI input pacing"));

    /** Cost parts with nothing to choose: the AI parent's answer is the only answer. */
    static final List<String> COST_NO_CHOICE = List.of("CostAddMana", "CostDamage", "CostDraw", "CostExileFromStack",
            "CostFlipCoin", "CostGainLife", "CostMill", "CostPartMana", "CostPayEnergy", "CostPayLife", "CostPayShards",
            "CostPutCounter", "CostPutCounterYou", "CostRemoveCounter", "CostRollDice", "CostTap", "CostUnattach", "CostUntap");

    @Test
    public void everyAbstractDecisionMethodIsOverridden() {
        List<String> missing = new ArrayList<>();
        List<String> overriddenNotify = new ArrayList<>();
        int abstracts = 0;
        for (Method m : PlayerController.class.getDeclaredMethods()) {
            if (!Modifier.isAbstract(m.getModifiers())) {
                continue;
            }
            abstracts++;
            boolean overridden;
            try {
                ScriptedController.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
                overridden = true;
            } catch (NoSuchMethodException e) {
                overridden = false;
            }
            if (NOTIFY.containsKey(m.getName())) {
                if (overridden) {
                    overriddenNotify.add(m.getName());
                }
                continue;
            }
            if (!overridden) {
                missing.add(m.getName() + sig(m));
            }
        }
        assertTrue(abstracts > 100, "PlayerController has " + abstracts + " abstract methods; the census read nothing?");
        assertEquals(missing, List.of(), "decision methods answered silently by the AI");
        assertEquals(overriddenNotify, List.of(), "an allowlisted notification is overridden: drop it from NOTIFY");
    }

    @Test
    public void allowlistNamesRealAbstractMethods() {
        for (String name : NOTIFY.keySet()) {
            boolean found = false;
            for (Method m : PlayerController.class.getDeclaredMethods()) {
                found |= m.getName().equals(name) && Modifier.isAbstract(m.getModifiers());
            }
            assertTrue(found, "stale allowlist entry " + name);
        }
    }

    @Test
    public void everyChoosingCostIsRouted() {
        List<String> missing = new ArrayList<>();
        for (Method m : ICostVisitor.class.getDeclaredMethods()) {
            if (!m.getName().equals("visit") || m.getParameterCount() != 1) {
                continue;
            }
            String part = m.getParameterTypes()[0].getSimpleName();
            if (COST_NO_CHOICE.contains(part)) {
                continue;
            }
            try {
                ScriptedCostDecision.class.getDeclaredMethod("visit", m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(part);
            }
        }
        assertEquals(missing, List.of(), "card-choosing cost parts answered silently by AiCostDecision");
        assertTrue(CostDecisionMakerBase.class.isAssignableFrom(ScriptedCostDecision.class));
    }

    private static String sig(Method m) {
        StringBuilder b = new StringBuilder("(");
        for (Class<?> c : m.getParameterTypes()) {
            if (b.length() > 1) {
                b.append(", ");
            }
            b.append(c.getSimpleName());
        }
        return b.append(")").toString();
    }
}
