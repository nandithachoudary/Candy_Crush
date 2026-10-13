import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class LoadBalancer {
    static int[] ports;
    static boolean[] up;
    static final int LS_PORT = 7000;
    static final Map<String, Integer> sessions = new ConcurrentHashMap<>();  // player -> server index
    static final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    static final Object assignLock = new Object();

    interface H { void run(HttpExchange ex, Map<String, String> q) throws Exception; }

    public static void main(String[] args) throws Exception {
        int httpPort = Integer.parseInt(args[0]);
        ports = new int[args.length - 1];
        up = new boolean[ports.length];
        for (int i = 1; i < args.length; i++) ports[i - 1] = Integer.parseInt(args[i]);

        new Thread(LoadBalancer::heartbeatLoop).start();
        new Thread(LoadBalancer::reaperLoop).start();

        HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
        server.setExecutor(Executors.newFixedThreadPool(32));

        route(server, "/", (ex, q) -> {
            byte[] page = Files.readAllBytes(Paths.get("index.html"));
            send(ex, 200, "text/html; charset=utf-8", page);
        });

        route(server, "/api/join", (ex, q) -> {
            String name = q.getOrDefault("name", "");
            if (!name.matches("[A-Za-z0-9_]{1,12}")) { json(ex, err("name must be 1-12 letters/digits")); return; }
            if (sessions.containsKey(name)) { json(ex, err("that name is already playing")); return; }
            String j = assign(name);
            lastSeen.put(name, System.currentTimeMillis());
            json(ex, j == null ? err("no game servers available") : j);
        });

        route(server, "/api/move", (ex, q) -> {
            String name = q.get("name");
            Integer idx = name == null ? null : sessions.get(name);
            if (idx == null) { json(ex, err("not joined")); return; }
            lastSeen.put(name, System.currentTimeMillis());
            String cmd = "MOVE " + name + " " + Integer.parseInt(q.get("r1")) + " " + Integer.parseInt(q.get("c1"))
                       + " " + Integer.parseInt(q.get("r2")) + " " + Integer.parseInt(q.get("c2"));
            try {
                String r = callServer(idx, cmd);
                if (r.contains("\"error\":\"no session\"")) throw new IOException("lost session");
                json(ex, tag(r, idx));
            } catch (IOException e) {
                // ---- FAILOVER ----
                if (!"lost session".equals(e.getMessage())) setUp(idx, false);
                sessions.remove(name);
                String j = assign(name);
                if (j == null) json(ex, err("no game servers available"));
                else json(ex, "{\"failover\":true,\"message\":\"Your game server became unavailable. "
                        + "You were moved to another server (score kept, fresh board).\"," + j.substring(1));
            }
        });

        route(server, "/api/leaderboard", (ex, q) -> {
            String name = q.get("name");
            if (name != null && sessions.containsKey(name)) lastSeen.put(name, System.currentTimeMillis());
            String all = callLS("ALL");
            json(ex, "{\"players\":" + (all == null ? "[]" : all) + "}");
        });

        route(server, "/api/status", (ex, q) -> {
            StringBuilder sb = new StringBuilder("{\"servers\":[");
            for (int i = 0; i < ports.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"port\":").append(ports[i]).append(",\"up\":").append(up[i])
                  .append(",\"players\":").append(countPlayers(i)).append("}");
            }
            json(ex, sb.append("]}").toString());
        });

        route(server, "/api/leave", (ex, q) -> {
            String name = q.get("name");
            if (name != null) removePlayer(name);
            json(ex, "{\"ok\":true}");
        });

        server.start();
        System.out.println("[LoadBalancer] website at http://localhost:" + httpPort
                + "  | game servers: " + Arrays.toString(ports));
    }

    // ---------- LOAD BALANCING: least-connections ----------
    static int pickServer() {
        int best = -1, bestCount = Integer.MAX_VALUE;
        for (int i = 0; i < ports.length; i++) {
            if (!up[i]) continue;
            int c = countPlayers(i);
            if (c < bestCount) { best = i; bestCount = c; }
        }
        return best;
    }

    static int countPlayers(int idx) {
        int c = 0;
        for (int v : sessions.values()) if (v == idx) c++;
        return c;
    }

    // Pick a server and JOIN; if that server is dead, mark it down and try the next one
    static String assign(String name) {
        synchronized (assignLock) {
            for (int attempt = 0; attempt < ports.length; attempt++) {
                int idx = pickServer();
                if (idx < 0) return null;
                try {
                    String r = callServer(idx, "JOIN " + name);
                    sessions.put(name, idx);
                    System.out.println("[LoadBalancer] " + name + " -> server " + ports[idx]);
                    return tag(r, idx);
                } catch (IOException e) { setUp(idx, false); }
            }
            return null;
        }
    }

    // ---------- FAILURE DETECTION: heartbeats ----------
    static void heartbeatLoop() {
        while (true) {
            for (int i = 0; i < ports.length; i++) {
                try { callServer(i, "PING"); setUp(i, true); }
                catch (IOException e) { setUp(i, false); }
            }
            sleep(2000);
        }
    }

    static void setUp(int i, boolean state) {
        if (up[i] != state)
            System.out.println("[LoadBalancer] game server " + ports[i] + (state ? " is UP" : " is DOWN"));
        up[i] = state;
    }

    // Remove players whose browser has stopped polling (closed tab, etc.)
    static void reaperLoop() {
        while (true) {
            sleep(5000);
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : new ArrayList<>(lastSeen.entrySet()))
                if (now - e.getValue() > 90000) removePlayer(e.getKey());
        }
    }

    static void removePlayer(String name) {
        Integer idx = sessions.remove(name);
        lastSeen.remove(name);
        if (idx != null) { try { callServer(idx, "LEAVE " + name); } catch (IOException ignored) {} }
        callLS("REMOVE " + name);
        System.out.println("[LoadBalancer] " + name + " removed");
    }

    // ---------- socket helpers ----------
    static String callServer(int i, String cmd) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", ports[i]), 1000);
            s.setSoTimeout(4000);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out.println(cmd);
            String r = in.readLine();
            if (r == null) throw new IOException("closed");
            return r;
        }
    }

    static String callLS(String cmd) {
        try (Socket s = new Socket("localhost", LS_PORT);
             PrintWriter out = new PrintWriter(s.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println(cmd);
            return in.readLine();
        } catch (IOException e) { return null; }
    }

    // ---------- HTTP helpers ----------
    static String tag(String r, int idx) { return "{\"server\":" + ports[idx] + "," + r.substring(1); }
    static String err(String m) { return "{\"ok\":false,\"error\":\"" + m + "\"}"; }

    static void route(HttpServer s, String path, H h) {
        s.createContext(path, ex -> {
            try { h.run(ex, query(ex)); }
            catch (Exception e) { try { json(ex, err("bad request")); } catch (IOException ignored) {} }
        });
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null)
            for (String kv : raw.split("&")) {
                String[] a = kv.split("=", 2);
                m.put(a[0], a.length > 1 ? URLDecoder.decode(a[1], StandardCharsets.UTF_8) : "");
            }
        return m;
    }

    static void json(HttpExchange ex, String body) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(body); }
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}