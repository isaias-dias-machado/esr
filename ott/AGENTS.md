# ott — ESR TP2 OTT streaming overlay

Source of truth for agents working in `ott/`. Read `context/assignment.md`
before any mission-relevant change.

## Git Workflow
When making changes/edits, ALWAYS follow this process:
0. Ensure that we are under a git directory. If not, do not continue.
1. Ensure the current branch is committed. If not, do not continue
   until the user has committed and pushed the changes.
2. Create a new git workspace before named: agent/<short-task-name>
3. Never commit directly to main or master.
4. Never leave the working tree dirty between tasks.


## Project

- Course: Engenharia de Serviços em Rede (ESR), MEI, Universidade do Minho, 2026/27.
- Assignment: TP2 — OTT multimedia delivery over an application-level overlay.
- Group: PL36.
- Stack: Java 21 + Maven, Jackson YAML. Testbed: CORE emulator.
- Deliverables: working prototype plus an LNCS report (see `context/rubric.md`).

## Mission

Build one program (`ott`) that:

- is both client and server on a predefined port, full-duplex;
- joins the overlay through a bootstrapper (or a known peer);
- keeps at least X neighbour connections alive (X is a design choice, e.g. 3);
- keeps a per-flow routing entry `(Flow, Cost, Origin, Destinations, State)`;
- forwards and replicates stream data according to that entry;
- supports at least 2 servers, each serving at least 2 distinct streams;
- chooses routes from network metrics (hop count, delay, bandwidth, ...).

## Non-negotiable grading requirements

The report and the live demo must show:

- dynamic node join and leave;
- alternative routes when a node or link fails;
- multiple simultaneous clients;
- routes that are monitored and updated dynamically as conditions change.

## Project map

| Path | Role |
|------|------|
| `src/main/java/dev/pl36/Main.java` | Single deployable program. `ott <node_id> <scenario.yaml>` builds and prints the local routing table; `ott server [--video file]... [--flow id]... [--fps n] [--control port] [--dest host:port]` owns flows (id = video file name) and answers WANT_FLOW on the TCP control port, `--dest` being a test-only push override; `ott client [--listen port] [--server host:port] [--want flow]... [--flow flow]...` requests flows by name and logs every frame/gap (`--want` aliases `--flow`). Streaming defaults (control 127.0.0.1:25001, push dest 127.0.0.1:25000, `resorces/movie.Mjpeg`, flow `movie.Mjpeg`, 10 fps, listen 25000) are hardcoded and overridable by flags. |
| `src/main/java/dev/pl36/FlowDispatcher.java` | Placeholder per-flow dispatcher of a server node (until the routing workstream's real FlowTable lands): keeps one `FlowEntry` per flow, resolves WANT_FLOW to `Origin = LOCAL` with plural Destinations, dedups by messageId, and runs one `StreamSource` per flow over the shared `DataChannel`. |
| `src/main/java/dev/pl36/scenario/` | `Scenario`, `ScenarioParser`, `Node`, `Neighbor`, `InvalidScenarioException`, `ParseScenarioDemo`. Strict YAML parsing and validation. |
| `src/main/java/dev/pl36/routing/RoutingTable.java` | Per-process view: the local node plus its direct neighbours. |
| `src/main/java/dev/pl36/routing/FlowEntry.java` | One per-flow routing entry `{ Flow, Cost, Origin, Destinations, State }`; `Origin = LOCAL` on the owning node. |
| `src/main/java/dev/pl36/net/` | Shared seams of the overlay: `ControlMessage`/`MessageType`/`WireCodec` (TCP control wire format, incl. WANT_FLOW/LEAVE_FLOW/ANNOUNCE), `DataPacketHeader` (UDP packet layout), `LoopbackMessenger` in-JVM fake, `UdpDataChannel` minimal real UDP `DataChannel` (peers addable at runtime; placeholder for the full transport), `TcpControlLink` minimal real TCP `ControlMessenger` (one-shot, WireCodec-framed). |
| `src/main/java/dev/pl36/net/api/` | Transport contracts consumed by the routing and streaming layers: `ControlMessenger`/`DataChannel` and their listener interfaces. Implementations live in `net/`. |
| `src/main/java/dev/pl36/streaming/` | ProgEx extraction (see `docs/ProgEx/`): `MjpegVideoStream` (movie.Mjpeg frame reader), `StreamSource`/`StreamSink` (numbered frames over `DataChannel` via `DataPacketHeader`, gap-tolerant; source destinations may be a live supplier), `FrameHandler`/`ConsoleFrameHandler`, `StreamingLoopbackDemo` in-JVM demo. |
| `resorces/cenario1.yaml`, `resorces/cenario2.yaml` | Test scenarios (see `context/scenarios.md`). |
| `resorces/movie.Mjpeg`, `resorces/movie2.Mjpeg` … `movie4.Mjpeg` | Test videos copied from `docs/ProgEx/` (500 MJPEG frames each); the 2-servers x 2-streams demo uses 4 distinct flow ids. |
| `pom.xml` | Java 21, Jackson YAML, no wrapper and no shade/exec plugin. |

`resorces/` is misspelled on purpose (historical). Do not rename it silently:
the typo appears in code, docs and run commands.

## Build and run

There is no Maven wrapper (`mvnw`) and `.mvn/` is empty. Build on the host:

```sh
cd ott
mvn package
```

The produced JAR has no dependencies bundled. Run one process per overlay node
with an explicit classpath:

```sh
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/ott-cp.txt
java -cp "target/classes:$(cat /tmp/ott-cp.txt)" dev.pl36.Main \
     <node_id> resorces/cenario1.yaml
```

Streaming demo (localhost). Request/pull mode — start the server first, then
the clients; each client sends WANT_FLOW on the server's control port
(default 127.0.0.1:25001) and receives its flows on its UDP listen port:

```sh
java -cp "target/classes:$(cat /tmp/ott-cp.txt)" dev.pl36.streaming.StreamingLoopbackDemo   # in-JVM, no sockets
java -cp "target/classes:$(cat /tmp/ott-cp.txt)" dev.pl36.Main server \
     --video resorces/movie.Mjpeg --video resorces/movie2.Mjpeg --control 25001              # terminal 1
java -cp "target/classes:$(cat /tmp/ott-cp.txt)" dev.pl36.Main client --listen 25010 \
     --server 127.0.0.1:25001 --want movie.Mjpeg --want movie2.Mjpeg                          # terminal 2
```

Test-only push override (old one-shot behaviour; start the client first —
the server fires UDP immediately):

```sh
java -cp "target/classes:$(cat /tmp/ott-cp.txt)" dev.pl36.Main client --listen 25000          # terminal 1
java -cp "target/classes:$(cat /tmp/ott-cp.txt)" dev.pl36.Main server --dest 127.0.0.1:25000 # terminal 2
```

## CORE workflow

Run these from the repository root (the parent of `ott/`):

```sh
docker compose build core   # build the emulator image
./xubuncore                 # start container + core-gui (default)
./xubuncore -s              # start container + shell
./xubuncore -w              # start container + Wireshark
./xubuncore -d              # stop and remove the container
```

- `./volume` on the host is mounted at `/volume` in the container. The TP2
  space is `volume/tp2/`, which contains `tp2-underlay.xml`.
- `./topologies` is mounted at `/root/.coregui/xmls/topologies`.

## Coding conventions

- Prefer wide, descriptive names over abbreviations.
- Every public type and method gets a comment stating purpose, invariants and
  failure modes.
- Prefer the simplest design that satisfies the assignment; discuss
  alternatives in the report instead of building them.
- Keep changes consistent with the existing package layout and record style.

## Context files

- `context/assignment.md` — full assignment breakdown, requirements, stages.
- `context/scenarios.md` — test scenarios and their YAML mappings.
- `context/rubric.md` — grading weights, report structure, submission naming.
