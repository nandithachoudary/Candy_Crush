import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class GameServer {
    static final int SIZE = 6;
    static final char[] CANDIES = {'A', 'B', 'C', 'D', 'E'};
    static final String LS_HOST = "localhost";
    static final int LS_PORT = 7000;
    static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    static int port;

    public static void main(String[] args) throws IOException {
        port = Integer.parseInt(args[0]);
        ServerSocket ss = new ServerSocket(port);
        System.out.println("[GameServer " + port + "] started");
        while (true) {
            Socket s = ss.accept();
            new Thread(() -> handle(s)).start();   // concurrent server: thread per request
        }
    }

    // ---- talk to the leaderboard coordinator over a socket ----
    static String ls(String cmd) {
        try (Socket s = new Socket(LS_HOST, LS_PORT);
             PrintWriter out = new PrintWriter(s.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println(cmd);
            return in.readLine();
        } catch (IOException e) { return null; }
    }

    static String err(String m) { return "{\"ok\":false,\"error\":\"" + m + "\"}"; }

    static void handle(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            String line = in.readLine();
            if (line == null) return;
            String[] p = line.trim().split(" ");
            String reply;
            try {
                switch (p[0]) {
                    case "PING":  reply = "PONG"; break;
                    case "JOIN":  reply = join(p[1]); break;
                    case "LEAVE": sessions.remove(p[1]); reply = "{\"ok\":true}";
                                  System.out.println("[GameServer " + port + "] " + p[1] + " left"); break;
                    case "MOVE": {
                        Session se = sessions.get(p[1]);
                        if (se == null) reply = err("no session");
                        else reply = se.move(Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                                             Integer.parseInt(p[4]), Integer.parseInt(p[5]));
                        break;
                    }
                    default: reply = err("unknown command");
                }
            } catch (Exception e) { reply = err("bad request"); }
            out.println(reply);
        } catch (IOException ignored) {}
    }

    static String join(String name) {
        // If this player already had a score (e.g. failover from a crashed server), keep it
        int prior = 0;
        try { String r = ls("GET " + name); if (r != null) prior = Math.max(0, Integer.parseInt(r.trim())); }
        catch (Exception ignored) {}
        Session se = new Session(name);
        se.score = prior;
        ls("SET " + name + " " + se.score);
        sessions.put(name, se);
        System.out.println("[GameServer " + port + "] " + name + " joined (score " + prior + ")");
        return "{\"ok\":true,\"board\":" + se.boardJson() + ",\"score\":" + se.score + "}";
    }

    // ================= one player's game =================
    static class Session {
        final String name;
        final char[][] board;
        int score = 0;
        final Random rand = new Random();

        Session(String name) { this.name = name; this.board = generateBoard(); }

        char[][] generateBoard() {
            char[][] b = new char[SIZE][SIZE];
            for (int i = 0; i < SIZE; i++)
                for (int j = 0; j < SIZE; j++) {
                    List<Character> allowed = new ArrayList<>();
                    for (char c : CANDIES) allowed.add(c);
                    if (j >= 2 && b[i][j-1] == b[i][j-2]) allowed.remove(Character.valueOf(b[i][j-1]));
                    if (i >= 2 && b[i-1][j] == b[i-2][j]) allowed.remove(Character.valueOf(b[i-1][j]));
                    b[i][j] = allowed.get(rand.nextInt(allowed.size()));
                }
            return b;
        }

        String boardJson() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < SIZE; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(new String(board[i])).append("\"");
            }
            return sb.append("]").toString();
        }

        boolean[][] findMatches() {
            boolean[][] m = new boolean[SIZE][SIZE];
            for (int i = 0; i < SIZE; i++) {
                int count = 1;
                for (int j = 1; j <= SIZE; j++) {
                    if (j < SIZE && board[i][j] == board[i][j-1]) count++;
                    else { if (count >= 3) for (int k = j - count; k < j; k++) m[i][k] = true; count = 1; }
                }
            }
            for (int j = 0; j < SIZE; j++) {
                int count = 1;
                for (int i = 1; i <= SIZE; i++) {
                    if (i < SIZE && board[i][j] == board[i-1][j]) count++;
                    else { if (count >= 3) for (int k = i - count; k < i; k++) m[k][j] = true; count = 1; }
                }
            }
            return m;
        }

        void clearAndDrop(boolean[][] m) {
            for (int j = 0; j < SIZE; j++) {
                List<Character> remaining = new ArrayList<>();
                for (int i = SIZE - 1; i >= 0; i--) if (!m[i][j]) remaining.add(board[i][j]);
                int idx = 0;
                for (int i = SIZE - 1; i >= 0; i--)
                    board[i][j] = idx < remaining.size() ? remaining.get(idx++)
                                                          : CANDIES[rand.nextInt(CANDIES.length)];
            }
        }

        synchronized String move(int r1, int c1, int r2, int c2) {
            if (r1 < 0 || r1 >= SIZE || c1 < 0 || c1 >= SIZE || r2 < 0 || r2 >= SIZE || c2 < 0 || c2 >= SIZE)
                return err("out of bounds");
            boolean adj = (Math.abs(r1 - r2) == 1 && c1 == c2) || (Math.abs(c1 - c2) == 1 && r1 == r2);
            if (!adj) return err("cells must be adjacent");

            swap(r1, c1, r2, c2);
            if (!any(findMatches())) { swap(r1, c1, r2, c2); return err("that swap makes no match"); }

            StringBuilder sb = new StringBuilder("{\"ok\":true,\"swapped\":" + boardJson() + ",\"steps\":[");
            boolean first = true;
            while (true) {
                boolean[][] m = findMatches();
                if (!any(m)) break;
                StringBuilder cells = new StringBuilder("[");
                int count = 0;
                for (int i = 0; i < SIZE; i++)
                    for (int j = 0; j < SIZE; j++)
                        if (m[i][j]) { if (count++ > 0) cells.append(","); cells.append("[").append(i).append(",").append(j).append("]"); }
                cells.append("]");
                int points = count * 10;
                score += points;
                clearAndDrop(m);
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"matched\":").append(cells).append(",\"points\":").append(points)
                  .append(",\"score\":").append(score).append(",\"board\":").append(boardJson()).append("}");
            }
            sb.append("],\"score\":").append(score).append("}");
            ls("SET " + name + " " + score);   // goes through the coordinator's lock
            System.out.println("[GameServer " + port + "] " + name + " scored, total " + score);
            return sb.toString();
        }

        void swap(int r1, int c1, int r2, int c2) { char t = board[r1][c1]; board[r1][c1] = board[r2][c2]; board[r2][c2] = t; }
        boolean any(boolean[][] m) { for (boolean[] r : m) for (boolean c : r) if (c) return true; return false; }
    }
}