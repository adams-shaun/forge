// SPDX-License-Identifier: GPL-3.0-or-later
package forge.oracle;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A warm oracle worker: one bootstrapped JVM that replays many request files.
 *
 * <pre>
 *   ScenarioReplay --serve SOCK IDLE_S MAX_ITEMS
 * </pre>
 *
 * It listens on the Unix socket SOCK and handles one connection at a time.
 * A client sends one line, {@code IN<TAB>OUT}; the worker answers
 * {@code start} at once (so a client can tell a live worker from one still
 * busy with an abandoned file), replays IN into OUT exactly as the one-shot
 * {@code ScenarioReplay IN OUT} does, with stdout and stderr sent to OUT.log
 * for that file, and answers {@code ok N} (N rows) or {@code err MESSAGE}.
 *
 * Determinism: the one-shot run already replays a whole batch in one JVM, and
 * every request seeds its own random (ForgeEngine, GameReplay), so serving file
 * B after file A is the one-shot run of A then B. It exits after IDLE_S seconds
 * without a client, or once it has replayed MAX_ITEMS rows (the one-shot
 * runner's chunking, FORGE_ORACLE_CHUNK, bounds a JVM's work the same way).
 * scripts/oracle-warm.py in gorge-compliance is the client.
 */
public final class WarmServer {
    /** Replays IN into OUT and returns the row count. */
    public interface Handler {
        int replay(BufferedReader in, PrintWriter out) throws Exception;
    }

    private WarmServer() {
    }

    public static void serve(String sock, long idleS, long maxItems, Handler h) throws IOException {
        Path p = Paths.get(sock);
        Files.deleteIfExists(p);
        PrintStream out0 = System.out;
        PrintStream err0 = System.err;
        long served = 0;
        try (ServerSocketChannel ss = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
             Selector sel = Selector.open()) {
            ss.bind(UnixDomainSocketAddress.of(p));
            ss.configureBlocking(false);
            ss.register(sel, SelectionKey.OP_ACCEPT);
            err0.println("WarmServer: listening on " + sock);
            while (served < maxItems) {
                if (sel.select(Math.max(1, idleS) * 1000L) == 0) {
                    err0.println("WarmServer: idle " + idleS + "s; exiting");
                    break;
                }
                sel.selectedKeys().clear();
                SocketChannel c = ss.accept();
                if (c == null) {
                    continue;
                }
                try (c) {
                    c.configureBlocking(true);
                    BufferedReader rd = new BufferedReader(new InputStreamReader(Channels.newInputStream(c), StandardCharsets.UTF_8));
                    Writer wr = new OutputStreamWriter(Channels.newOutputStream(c), StandardCharsets.UTF_8);
                    String line = rd.readLine();
                    if (line == null) {
                        continue;
                    }
                    wr.write("start\n");
                    wr.flush();
                    String[] f = line.split("\t");
                    if (f.length != 2) {
                        wr.write("err bad request\n");
                        wr.flush();
                        continue;
                    }
                    String reply;
                    long t0 = System.nanoTime();
                    try (PrintStream log = new PrintStream(new FileOutputStream(f[1] + ".log"), true, StandardCharsets.UTF_8)) {
                        System.setOut(log);
                        System.setErr(log);
                        int n;
                        try (BufferedReader in = Files.newBufferedReader(Paths.get(f[0]), StandardCharsets.UTF_8);
                             PrintWriter out = new PrintWriter(new BufferedWriter(Files.newBufferedWriter(Paths.get(f[1]), StandardCharsets.UTF_8)))) {
                            n = h.replay(in, out);
                        }
                        served += n;
                        log.printf("ScenarioReplay: %d scenarios warm total_ms=%d served=%d%n", n,
                                (System.nanoTime() - t0) / 1_000_000, served);
                        reply = "ok " + n;
                    } catch (Throwable t) {
                        String m = String.valueOf(t).replace('\n', ' ');
                        reply = "err " + (m.length() > 500 ? m.substring(0, 500) : m);
                    } finally {
                        System.setOut(out0);
                        System.setErr(err0);
                    }
                    wr.write(reply + "\n");
                    wr.flush();
                } catch (IOException e) {
                    err0.println("WarmServer: client: " + e);
                }
            }
        } finally {
            Files.deleteIfExists(p);
        }
    }
}
