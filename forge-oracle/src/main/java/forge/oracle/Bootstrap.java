// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.card.CardType;
import forge.game.card.CardUtil;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;

/**
 * Headless Forge bootstrap with no forge-gui on the classpath (DESIGN 6.2,
 * measured in P0). It replays by hand the initialisation forge-gui's FModel
 * normally does, each of which P0 found as a crash or a silent wrong answer:
 *
 * <ol>
 * <li>Localizer and Lang (NPEs in prompts and stack descriptions);</li>
 * <li>the type lists and the non-stacking keyword list. Without TypeLists.txt
 *     Forge SILENTLY drops every subtype (Grizzly Bears becomes a bare
 *     creature), so {@link #checkTypeLists()} fails loudly instead;</li>
 * <li>the AI profiles (the loose-mode fallback answers through the AI);</li>
 * <li>ImageKeys pointed at an empty scratch dir (no image cache, no profile);</li>
 * <li>StaticData with LAZY card loading and an eager token reader.</li>
 * </ol>
 */
public final class Bootstrap {
    private static boolean done;

    private Bootstrap() {
    }

    public static synchronized void init(String res) {
        if (done) {
            return;
        }
        System.setProperty("java.awt.headless", "true");
        Localizer.getInstance().initialize("en-US", res + "/languages/");
        Lang.createInstance("en-US");
        loadTypeLists(res + "/lists/TypeLists.txt");
        for (String kw : FileUtil.readFile(res + "/lists/NonStackingKWList.txt")) {
            if (kw.length() > 1) {
                CardUtil.NON_STACKING_LIST.add(kw);
            }
        }
        checkTypeLists();
        AiProfileUtil.loadAllProfiles(res + "/ai");
        File empty = new File(System.getProperty("java.io.tmpdir"), "forge-oracle-empty");
        empty.mkdirs();
        String pics = empty.getPath() + "/pics/";
        ImageKeys.initializeDirs(pics, new HashMap<>(), pics, pics, pics, pics, pics, pics, pics);
        CardStorageReader reader = new CardStorageReader(res + "/cardsfolder", null, true);
        CardStorageReader tokenReader = new CardStorageReader(res + "/tokenscripts", null, false);
        new StaticData(reader, tokenReader, null, null, res + "/editions", empty.getPath(), res + "/blockdata", "",
                "Latest Art All Editions", true, false, false, false);
        done = true;
    }

    /** FModel.loadDynamicGamedata's type-list half. */
    public static void loadTypeLists(String path) {
        Map<String, List<String>> types = FileSection.parseSections(FileUtil.readFile(path));
        for (Map.Entry<String, List<String>> e : types.entrySet()) {
            CardType.Helper.parseTypes(e.getKey(), e.getValue());
        }
        CardType.Constant.LOADED.set();
    }

    /** Fails if the type lists did not load: the silent subtype-drop failure
     * mode P0 found. Bear and Goblin are creature types; Equipment an
     * artifact type; Forest a land type. */
    public static void checkTypeLists() {
        String[][] want = {{"Bear", "creature"}, {"Goblin", "creature"}, {"Equipment", "artifact"}, {"Forest", "land"}};
        for (String[] w : want) {
            boolean ok;
            switch (w[1]) {
                case "creature": ok = CardType.isACreatureType(w[0]); break;
                case "artifact": ok = CardType.isAnArtifactType(w[0]); break;
                default: ok = CardType.isALandType(w[0]); break;
            }
            if (!ok) {
                throw new IllegalStateException("type lists not loaded: " + w[0] + " is not a known " + w[1]
                        + " type (Forge would silently drop every subtype)");
            }
        }
    }
}
