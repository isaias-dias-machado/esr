# Test scenarios

The assignment suggests three scenarios of increasing size. This file maps them
to the underlay topology and to the YAML files in `resorces/`.

## Underlay topology

`volume/tp2/tp2-underlay.xml` (mounted at `/volume/tp2/` in the CORE container)
has 24 nodes:

- `n1`–`n10`: routers (OSPFv2/v3, forwarding);
- `n11`–`n15`: switches;
- `n16`–`n24`: PCs / end systems.

Underlay addresses used by the scenarios:

| Node | Type | Address |
|------|------|---------|
| `n16` | PC | `10.1.15.16/24` |
| `n17` | PC | `10.1.11.17/24` |
| `n18` | PC | `10.1.11.18/24` |
| `n19` | PC | `10.1.12.19/24` |
| `n20` | PC | `10.1.12.20/24` |
| `n21` | PC | `10.1.13.21/24` |
| `n22` | PC | `10.1.13.22/24` |
| `n23` | PC | `10.1.15.23/24` |
| `n24` | PC | `10.1.14.24/24` |
| `n2`, `n3`, `n6` | router | `10.0.1.2/30`, `10.0.7.1/30`, `10.0.9.2/30` |

**Place overlay nodes only on end systems (`n16`–`n24`).** CORE lets an overlay
node run on a router or switch, and Figure 1 of the assignment does so, but in
reality those are dedicated devices. The assignment explicitly recommends end
systems.

## Node roles (`role` in the scenario YAML)

Every node entry in a scenario YAML declares its role explicitly; the tables
below and the YAML always agree (the tables say "intermediate", the YAML
spelling for that is `relay`):

```yaml
- id: 0
  ip_addr: 10.1.15.16/24
  description: "n16"
  role: server
```

- `server` — owns flows and serves them (at least 2 servers in scenario 3);
- `client` — names and consumes flows;
- `relay` — forwards flows; **omitting the key also means relay** (old files
  keep parsing).

The parser accepts exactly the three lowercase spellings — anything else is a
parse error. `ott roles <scenario.yaml>` prints one `id role ip_addr` line per
node; `scripts/localhost-demo.sh` consumes that output, so the YAML is the
single source of truth and no script embeds a role table.

## Scenario 1 — 4 overlay nodes

One server, one intermediate node, two clients.

`resorces/cenario1.yaml`:

| id | Underlay | Role |
|----|----------|------|
| 0 | `n16` | server |
| 1 | `n6` | intermediate |
| 2 | `n20` | client |
| 3 | `n17` | client |

Links: `0–1`, `1–2`, `1–3`. The only path from the server to either client goes
through node 1, so this scenario cannot demonstrate an alternative route.

## Scenario 2 — 6 overlay nodes

One server, three intermediate nodes, two clients.

`resorces/cenario2.yaml`:

| id | Underlay | Role |
|----|----------|------|
| 0 | `n16` | server |
| 1 | `n6` | intermediate |
| 2 | `n3` | intermediate |
| 3 | `n2` | intermediate |
| 4 | `n20` | client |
| 5 | `n17` | client |

Links: `0–1`, `1–2`, `1–3`, `2–4`, `3–5`. The two clients take disjoint paths
after node 1, which makes it a good base for testing replication and for
killing one branch to observe failover.

## Scenario 3 — complex overlay

Start from Figure 1 of the assignment: 11 overlay nodes, 2 servers and 5
clients. `S1` is also overlay node `O1`, running on `n16`. Figure 1 shows the
intended shape, not a fixed address plan; `resorces/cenario3.yaml` is the
fixed plan:

| id | Underlay | Role |
|----|----------|------|
| 1 | `n16` | server (first server) |
| 2 | `n10` | relay |
| 3 | `n8` | relay |
| 4 | `n22` | client (C1) |
| 5 | `n3` | relay |
| 6 | `n2` | server (second server — group decision, keeps the 2-servers requirement) |
| 7 | `n5` | relay |
| 8 | `n24` | client (C2) |
| 9 | `n18` | client (C3) |
| 10 | `n19` | client (C4) |
| 11 | `n20` | client (C5) |

Links (`cenario3.yaml`): `1–2`, `2–3`, `2–7`, `3–4`, `3–5`, `5–10`, `5–11`,
`5–6`, `6–9`, `7–5`, `7–6`, `7–8`. The `5–6–7` triangle gives real
alternative paths between the two server sides — it is the topology where
"alternative routes when a node fails" can actually be shown.

Honest placement note (for the report): relays 2, 5, 6 and 7 currently sit on
Figure-1 router positions (`n10`, `n3`, `n2`, `n5`). That is fine for
localhost and proves the shape; before the CORE demo the group may remap
those overlay nodes onto end systems (`n21`, `n23`, ...) per the guidance
above — only `ip_addr`/`description` change, never ids or links.

## Known issue: `n17` address in the YAML files — resolved

Both `cenario1.yaml` and `cenario2.yaml` used to declare node `n17` as
`10.1.11.12/24`; the underlay gives `n17` `10.1.11.17/24`, and no node owns
`10.1.11.12`. It was a typo. Both files now declare `10.1.11.17/24`
(`ScenarioParserTest.cenario2DeclaresTheRealN17Address` keeps it fixed).

## Running a scenario

Each overlay process needs a node id and the scenario file:

```sh
java -cp "target/classes:$(cat /tmp/ott-cp.txt)" dev.pl36.Main \
     <node_id> resorces/cenario1.yaml
```

Every node must see a consistent scenario. Today the YAML file is the shared
configuration; there is no bootstrapper yet. When the bootstrapper exists, only
the bootstrapper needs the full file and the other nodes need its address.

On one laptop, without CORE, use the localhost variants and the deploy script
(next section) instead of starting processes by hand.

## Localhost variants and the deploy script

`resorces/cenarioN-localhost.yaml` mirrors scenario N for a single host: same
node ids, same links, same roles; only the addresses (and the overlay port)
change, so a runbook written for CORE transfers 1:1.

- Address scheme: node id `x` owns `127.0.0.(x+1)/8` — all of `127.0.0.0/8` is
  loopback on Linux, so every node binds a distinct address on one host. Node
  0 (or scenario 3's node 1) is `127.0.0.1`, matching the streaming defaults.
- Overlay port per scenario, so scenarios can run one after another without
  recompiling anything: `cenario1-localhost` 5000, `cenario2-localhost` 5001,
  `cenario3-localhost` 5002.
- Free validation: `ott <id> resorces/cenarioN-localhost.yaml` for every node id
  parses and prints that node's routing table (`ott roles` for the roles).

`scripts/localhost-demo.sh` starts, stops and kills the processes of one
scenario, with roles read from the YAML (details and examples: header of the
script itself):

```sh
scripts/localhost-demo.sh up <1|2|3> [id ...]   # all nodes except the listed ids
scripts/localhost-demo.sh start <1|2|3> <id>    # one node (mid-stream join)
scripts/localhost-demo.sh down <1|2|3>          # stop the scenario (idempotent)
scripts/localhost-demo.sh kill <1|2|3> <id>     # kill one node abruptly (EOF)
scripts/localhost-demo.sh stop <1|2|3> <id>    # stop one node gracefully (GOODBYE)
scripts/localhost-demo.sh status <1|2|3>       # per-node role + process state
scripts/localhost-demo.sh roles <1|2|3>        # the YAML's id role ip_addr table
scripts/localhost-demo.sh logs <1|2|3> [id]     # tail one log, or all of them
```

Port allocation inside the script (all on `127.0.0.1`; run scenarios one at a
time — they share these ranges): server `s` gets control port `25001+s`,
client `c` listens on `25010+c` and attaches to the server with index
`c % server-count`, so scenario 3's five clients split 3/2 across its two
servers. Server 0 serves `movie.Mjpeg` + `movie2.Mjpeg`, the second server
serves `movie3.Mjpeg` + `movie4.Mjpeg`. Relay nodes run the real overlay
process (`ott <id> <scenario>`: neighbour mesh with redial, HELLO/GOODBYE);
data-plane forwarding (M3) is not wired yet, so relays carry no frames —
their logs show the control-plane mesh. The matrix's showcase moments of the
transport are also scripted as a paced tour, `scripts/demo.sh`: single stream
by default (curated log excerpts per stage), `--panes kitty` for kitty
splits, `--panes tmux` for a tmux session.


## Suggested test matrix

Cover at least:

1. one client, one stream — baseline path and replication;
2. two clients on the same branch — shared prefix, no duplicate traffic;
3. two clients on different branches — replication at the branch point;
4. a client joining mid-stream;
5. a client leaving mid-stream;
6. an intermediate node killed mid-stream — alternative route;
7. a server killed mid-stream — behaviour and recovery;
8. two servers with two streams each — independent flow entries.

## Localhost runbook — the 8-case matrix

Every case runs cold from here (`S` = `scripts/localhost-demo.sh`; run from
`ott/`). Observations marked "needs M3" depend on overlay forwarding, which is
not merged yet — the commands already work, the data-plane observation will
change.

| # | Case | Scenario | Commands | Observation to capture |
|---|------|----------|----------|-------------------------|
| 1 | one client, one stream | 1 | `S up 1 3`; `S logs 1 2` | node 2 logs `frame flow=movie.Mjpeg ...`; baseline |
| 2 | two clients, same branch | 1 | `S up 1`; `S logs 1` | nodes 2 and 3 both receive behind relay 1; "no duplicate traffic over the shared prefix" needs M3 |
| 3 | two clients, different branches | 2 | `S up 2`; `S logs 2 4`; `S logs 2 5` | nodes 4 and 5 receive over disjoint branches (0-1-2-4 vs 0-1-3-5); replication at the branch point needs M3 |
| 4 | client joins mid-stream | 1 | `S up 1 3`; while node 2 logs frames: `S start 1 3`; `S logs 1 3` | node 3 logs its first frame already mid-stream (high sequence number, gap-tolerant), the stream does not restart |
| 5 | client leaves mid-stream | 1 | `S up 1`; `S kill 1 3`; `S logs 1 2`; `S status 1` | node 3 dies abruptly; node 2 keeps receiving; server drops the destination from the flow entry |
| 6 | intermediate node killed | 2 | `S up 2`; `S kill 2 1`; `S logs 2 4` | relay 1 is a real overlay node now: `kill` works, and its neighbours log `neighbour 1 lost: connection closed by the peer` (`stop` instead of `kill` logs `left: GOODBYE received` — `scripts/demo.sh` act 6 shows the contrast). Data-plane failover still needs M3; note the topology: killing relay 1 isolates the server (node 0's only link), i.e. scenario 2 demonstrates *no* alternative route; use scenario 3's `5–6–7` triangle for a real failover (e.g. kill relay 5) |
| 7 | server killed | 2 | `S up 2`; `S kill 2 0`; `S logs 2 4`; `S status 2` | clients stay up, log `client: control link to node 0 down`, and the stream freezes; recovery today means restarting server and clients (`S start` per node) — automatic recovery is future work |
| 8 | two servers, two streams each | 3 | `S up 3`; `S logs 3 4`; `S logs 3 8`; `S status 3` | server 1 owns `movie`+`movie2`, server 6 owns `movie3`+`movie4`; clients 4/9/11 watch server 1's flows, clients 8/10 watch server 6's — four independent flow entries at once |

Always `S down <n>` between cases — scenarios share the client/server port
ranges and logs would blur.

### CORE-only cases

Two non-negotiables cannot be demonstrated on localhost (all nodes share
`127.0.0.1`, links cannot be severed or re-weighted individually):

- **link failure** — CORE only: start scenario 1 or 2 on CORE, then
  right-click one link → *disconnect* mid-stream and capture the client frame
  logs around the event (needs M3 routing to survive it);
- **routes updated as conditions change (FM-04)** — CORE only: change a
  link's delay/bandwidth mid-run (right-click → configure) and observe the
  flow rerouting; needs the metric-driven routing workstream.
