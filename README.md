# 🍬 Distributed Candy Crush

A browser-based match-3 game built as a distributed system using **Java socket programming**. Players are spread across multiple game servers by a load balancer, and a shared leaderboard server keeps scores consistent using mutual exclusion.

Mini project for Distributed Computing, School of Computer and Information Sciences, University of Hyderabad.
Based on **Candy Crush Saga** (King).

## Features
- Playable 6x6 candy board in the browser with animated cascades
- Live leaderboard and live game-server status panel (UP/DOWN, player count)
- Multiple game servers behind a **least-connections load balancer**
- **Heartbeat failure detection** and **failover** that keeps the player's score
- **Centralized mutual exclusion** (explicit lock) on the shared leaderboard
- Inactive players are removed automatically
- No external libraries, Java standard library only

## Architecture

```
Browser --HTTP--> Load Balancer (:8080)
                       | TCP sockets
        +--------------+--------------+
   GameServer 9001  GameServer 9002  GameServer 9003
        \              |              /
         +-- TCP --> Leaderboard Server (:7000)
```

| Component | File | Port | Role |
|---|---|---|---|
| Load Balancer + website | `LoadBalancer.java`, `index.html` | 8080 | Serves the site, routes players, sends heartbeats |
| Game Server (any number) | `GameServer.java` | 9001, 9002, 9003 | Holds each player's board and runs match and cascade logic |
| Leaderboard Server | `LeaderboardServer.java` | 7000 | Shared scores guarded by a lock |

## Distributed concepts demonstrated
| Concept | Where |
|---|---|
| Least-connections load balancing | `LoadBalancer.pickServer` |
| Heartbeat failure detection (PING/PONG) | `LoadBalancer.heartbeatLoop` |
| Failover with state recovery | Score kept in the Leaderboard Server |
| Centralized mutual exclusion (`ReentrantLock`) | `LeaderboardServer` |
| Session affinity | Player stays on one game server |
| Timeout-based cleanup | Inactive players removed |
| Concurrent server (thread per request) | All servers |

## Design note
The original Candy Crush keeps game logic on the client so it works offline. This project deliberately keeps **all game logic on the server** so that every move produces visible socket communication, which makes the distributed algorithms observable. The trade-off is that there is no offline play.

## Requirements
- Java JDK 11 or newer
- A modern browser
- Free ports: 7000, 8080, 9001, 9002, 9003

## Run it
Open 5 terminals in the project folder:

```bash
javac *.java

java LeaderboardServer              # terminal 1
java GameServer 9001                # terminal 2
java GameServer 9002                # terminal 3
java GameServer 9003                # terminal 4
java LoadBalancer 8080 9001 9002 9003   # terminal 5
```

Then open **http://localhost:8080**.

To play from another device on the same Wi-Fi, run `ipconfig` (Windows) or `ip a` (Linux/Mac), take the Wi-Fi IPv4 address, and open `http://<that-ip>:8080`. You may need to allow port 8080 through the firewall.

## How to play
1. Enter a name (1-12 letters or digits) and click **Play**.
2. Click a candy, then click a neighbouring candy (up, down, left or right) to swap.
3. A swap that makes 3 or more in a row or column clears them. New candies fall in, and chain reactions are animated. You earn 10 points per candy cleared.
4. Invalid swaps are rejected and the board stays unchanged.

## Try the fault tolerance
1. Join with 3 or more players. They spread across the servers.
2. Press `Ctrl+C` in one game server terminal. It turns **DOWN** in the status panel.
3. That server's players move to another server on their next move and keep their scores.
4. Restart the server with `java GameServer <port>`. It turns **UP** and takes new players.

## Limitations and future work
- The load balancer and leaderboard server are single points of failure (future work: replicate them, for example with leader election or Raft).
- No persistence. All data is in memory.
- A player on a crashed server loses their current board, though not their score.
- Future work: shard the leaderboard, add a cache.

## Screenshots
Provided in output screenshots document.

## Author
Nanditha Choudary Doddapuneni (23MCCE36)
