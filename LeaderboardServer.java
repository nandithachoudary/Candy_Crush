import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.*;

public class LeaderboardServer {
    static final int PORT = 7000;
    static final Map<String, Integer> board = new HashMap<>(); // plain, NOT thread-safe
    static final Lock lock = new ReentrantLock();              // our mutual exclusion lock

    public static void main(String[] args) throws IOException {
        ServerSocket ss = new ServerSocket(PORT);
        System.out.println("[Leaderboard] coordinator started on port " + PORT);
        while (true) {
            Socket s = ss.accept();
            new Thread(() -> handle(s)).start();
        }
    }

    static void handle(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            String line = in.readLine();
            if (line == null) return;
            String[] p = line.trim().split(" ");
            String reply = "ERR";

            lock.lock();                       // ENTER CRITICAL SECTION
            try {
                switch (p[0]) {
                    case "SET":
                        board.put(p[1], Integer.parseInt(p[2]));
                        System.out.println("[Leaderboard][LOCK HELD] SET " + p[1] + " = " + p[2]);
                        reply = "OK";
                        break;
                    case "GET":
                        reply = String.valueOf(board.getOrDefault(p[1], -1));
                        break;
                    case "REMOVE":
                        board.remove(p[1]);
                        System.out.println("[Leaderboard][LOCK HELD] REMOVE " + p[1]);
                        reply = "OK";
                        break;
                    case "ALL":
                        List<Map.Entry<String, Integer>> list = new ArrayList<>(board.entrySet());
                        list.sort((a, b) -> b.getValue() - a.getValue());
                        StringBuilder sb = new StringBuilder("[");
                        for (int i = 0; i < list.size(); i++) {
                            if (i > 0) sb.append(",");
                            sb.append("{\"name\":\"").append(list.get(i).getKey())
                              .append("\",\"score\":").append(list.get(i).getValue()).append("}");
                        }
                        reply = sb.append("]").toString();
                        break;
                }
            } finally {
                lock.unlock();                 // EXIT CRITICAL SECTION
            }
            out.println(reply);
        } catch (Exception e) { /* ignore bad client */ }
    }
}