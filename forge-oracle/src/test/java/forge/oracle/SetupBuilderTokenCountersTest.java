// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.Map;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.card.CardEdition;
import forge.game.Game;
import forge.game.card.CardFactory;
import forge.item.PaperToken;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.zone.ZoneType;

/** A setup token's keyword counter grants its keyword (CR 122.1b). The
 * GameEntity setter the builder used wrote the counter table only, so a token
 * set up with {"LIFELINK": 1} held the counter but not lifelink. The real token
 * script (b_2_2_zombie_druid, the repro's token) is built as setup does. */
public class SetupBuilderTokenCountersTest {
    @BeforeClass
    public void boot() {
        Bootstrap.init(TestRes.res());
    }

    private static Card token() {
        StepMachine m = new StepMachine(Request.parse("{\"id\":\"t\",\"item\":{\"name\":\"t\"}}"), true);
        m.build();
        Game g = m.game;
        PaperToken pt = StaticData.instance().getAllTokens().getToken("b_2_2_zombie_druid", CardEdition.UNKNOWN_CODE);
        Card c = CardFactory.getCard(pt, m.seats[0], g);
        c.setGameTimestamp(g.getNextTimestamp());
        m.seats[0].getZone(ZoneType.Battlefield).add(c);
        return c;
    }

    @Test
    public void keywordCounterGrantsItsKeyword() {
        Card t = token();
        assertTrue(!t.hasKeyword(Keyword.LIFELINK));
        SetupBuilder.setTokenCounters(t, Map.of("LIFELINK", 1));
        t.getGame().getAction().checkStaticAbilities();
        assertEquals(t.getCounters(SetupBuilder.counterType("LIFELINK")), 1);
        assertTrue(t.hasKeyword(Keyword.LIFELINK), "a lifelink counter must grant lifelink");
    }

    @Test
    public void gorgeKindsResolveToForgeKeywordCounters() {
        assertTrue(SetupBuilder.counterType("LIFELINK").isKeywordCounter());
        assertEquals(SetupBuilder.counterType("FIRSTSTRIKE").toString(), "First Strike");
        assertEquals(SetupBuilder.counterType("DOUBLE_STRIKE").toString(), "Double Strike");
        assertEquals(SetupBuilder.counterType("P1P1"), CounterEnumType.P1P1);
    }
}
