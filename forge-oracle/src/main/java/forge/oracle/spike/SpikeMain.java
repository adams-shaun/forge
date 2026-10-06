package forge.oracle.spike;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.CardStorageReader;
import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.util.Localizer;
import forge.util.MyRandom;

/**
 * P0 spike: ScenarioReplay-shaped driver.
 *
 *   SpikeMain <in.jsonl> <out.jsonl> [--repeat N] [--no-mana] [--res DIR]
 *
 * Input lines are forge-req rows ({id, item, abilities, ...}) or bare Items.
 * --repeat N replays the whole input N times in this JVM and writes timing to stderr.
 */
public class SpikeMain {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static void main(String[] args) throws Exception {
        String in = args[0], out = args[1];
        int repeat = 1;
        boolean noMana = false;
        String res = System.getenv().getOrDefault("FORGE_RES", "forge-gui/res");
        String ref = System.getenv().getOrDefault("FORGE_ORACLE_REF", "");
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--repeat": repeat = Integer.parseInt(args[++i]); break;
                case "--no-mana": noMana = true; break;
                case "--res": res = args[++i]; break;
                default: throw new IllegalArgumentException(args[i]);
            }
        }
        long t0 = System.nanoTime();
        bootstrap(res);
        long tBoot = System.nanoTime();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(Paths.get(in), StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.isBlank()) lines.add(l);
            }
        }
        List<Long> perScenarioNs = new ArrayList<>();
        long firstScenarioReadyNs = -1;
        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(out), StandardCharsets.UTF_8)) {
            for (int rep = 0; rep < repeat; rep++) {
                for (String l : lines) {
                    long s0 = System.nanoTime();
                    JsonObject row = replay(l, noMana, ref);
                    long s1 = System.nanoTime();
                    if (firstScenarioReadyNs < 0) firstScenarioReadyNs = s1 - t0;
                    perScenarioNs.add(s1 - s0);
                    row.addProperty("ms", (s1 - s0) / 1_000_000);
                    if (rep == 0 || rep == repeat - 1) {
                        w.write(GSON.toJson(row));
                        w.newLine();
                    }
                }
            }
        }
        long tEnd = System.nanoTime();
        Runtime rt = Runtime.getRuntime();
        System.err.printf("bootstrap_ms=%d first_scenario_done_ms=%d total_ms=%d scenarios=%d heap_used_mb=%d%n",
                (tBoot - t0) / 1_000_000, firstScenarioReadyNs / 1_000_000, (tEnd - t0) / 1_000_000,
                perScenarioNs.size(), (rt.totalMemory() - rt.freeMemory()) >> 20);
        if (perScenarioNs.size() > lines.size()) {
            List<Long> warm = new ArrayList<>(perScenarioNs.subList(lines.size(), perScenarioNs.size()));
            Collections.sort(warm);
            System.err.printf("warm n=%d median_ms=%.2f p90_ms=%.2f max_ms=%.2f%n", warm.size(),
                    warm.get(warm.size() / 2) / 1e6, warm.get((int) (warm.size() * 0.9)) / 1e6, warm.get(warm.size() - 1) / 1e6);
        }
        try {
            String status = new String(Files.readAllBytes(Paths.get("/proc/self/status")));
            for (String s : status.split("\n")) {
                if (s.startsWith("VmRSS") || s.startsWith("VmHWM")) System.err.println(s.replaceAll("\\s+", " "));
            }
        } catch (Exception e) { /* not linux */ }
        System.exit(0);
    }

    static void bootstrap(String res) {
        System.setProperty("java.awt.headless", "true");
        Localizer.getInstance().initialize("en-US", res + "/languages/");
        forge.util.Lang.createInstance("en-US");
        // FModel.loadDynamicGamedata without forge-gui: type lists (subtype sanitising) and non-stacking keywords.
        java.util.Map<String, List<String>> types = forge.util.FileSection.parseSections(forge.util.FileUtil.readFile(res + "/lists/TypeLists.txt"));
        for (String section : types.keySet()) {
            forge.card.CardType.Helper.parseTypes(section, types.get(section));
        }
        forge.card.CardType.Constant.LOADED.set();
        for (String kw : forge.util.FileUtil.readFile(res + "/lists/NonStackingKWList.txt")) {
            if (kw.length() > 1) {
                forge.game.card.CardUtil.NON_STACKING_LIST.add(kw);
            }
        }
        AiProfileUtil.loadAllProfiles(res + "/ai");
        File empty = new File(System.getProperty("java.io.tmpdir"), "forge-oracle-empty");
        empty.mkdirs();
        // No image cache: point every pics dir at an empty scratch dir (headless, no user profile).
        String pics = empty.getPath() + "/pics/";
        forge.ImageKeys.initializeDirs(pics, new java.util.HashMap<>(), pics, pics, pics, pics, pics, pics, pics);
        CardStorageReader reader = new CardStorageReader(res + "/cardsfolder", null, true);
        CardStorageReader tokenReader = new CardStorageReader(res + "/tokenscripts", null, false);
        new StaticData(reader, tokenReader, null, null, res + "/editions", empty.getPath(), res + "/blockdata", "",
                "Latest Art All Editions", true, false, false, false);
    }

    static JsonObject replay(String line, boolean noMana, String ref) {
        JsonObject row = new JsonObject();
        JsonObject req;
        try {
            req = JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException e) {
            row.addProperty("strict", true);
            row.addProperty("name", "");
            row.addProperty("harness", "malformed line: " + e.getMessage());
            row.add("snapshots", new JsonArray());
            return row;
        }
        JsonObject item = req.has("item") ? req.getAsJsonObject("item") : req;
        String id = item.has("id") ? item.get("id").getAsString() : "";
        row.addProperty("strict", true);
        row.addProperty("name", item.has("name") ? item.get("name").getAsString() : "");
        row.addProperty("id", id);
        CRC32 crc = new CRC32();
        crc.update(id.getBytes(StandardCharsets.UTF_8));
        MyRandom.setRandom(new Random(crc.getValue()));
        Run run = new Run(req, noMana);
        try {
            run.build();
            run.drive();
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.toString();
            row.addProperty("harness", msg);
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String[] tr = sw.toString().split("\n");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(8, tr.length); i++) b.append(tr[i].trim()).append(" | ");
            row.addProperty("trace", b.toString());
        }
        JsonArray snaps = new JsonArray();
        run.snaps.forEach(snaps::add);
        row.add("snapshots", snaps);
        row.addProperty("engine", "forge");
        row.addProperty("forge_ref", ref);
        JsonArray notes = new JsonArray();
        run.notes.forEach(notes::add);
        notes.add("played=" + run.played + " pay_calls=" + run.payCalls);
        row.add("notes", notes);
        return row;
    }
}
