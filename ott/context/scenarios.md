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

Start from Figure 1 of the assignment: 11 overlay nodes and 5 clients.

- `S1` is also overlay node `O1`, running on `n16`.
- Clients are overlay nodes:
  - `C1` = `O4` on `n23`;
  - `C2` = `O8` on `n22`;
  - `C3` = `O9` on `n19`;
  - `C4` = `O10` on `n18`;
  - `C5` = `O11` on `n17`.
- The remaining overlay nodes (`O2`, `O3`, `O5`, `O6`, `O7`) should be placed
  on the remaining end systems (`n20`, `n21`, `n24`, and any reused PC), not on
  routers or switches.

Figure 1 shows the intended shape, not a fixed address plan. There is no YAML
for this scenario yet; create one when the implementation can use it.

## Known issue: `n17` address in the YAML files

Both `cenario1.yaml` and `cenario2.yaml` declare node `n17` as
`10.1.11.12/24`. The underlay gives `n17` `10.1.11.17/24`, and no node owns
`10.1.11.12`. This looks like a typo. Verify and fix it before running, but do
not change it silently: the address is part of the scenario contract.

## Running a scenario

Each overlay process needs a node id and the scenario file:

```sh
java -cp "target/classes:$(cat /tmp/ott-cp.txt)" dev.pl36.Main \
     <node_id> resorces/cenario1.yaml
```

Every node must see a consistent scenario. Today the YAML file is the shared
configuration; there is no bootstrapper yet. When the bootstrapper exists, only
the bootstrapper needs the full file and the other nodes need its address.

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
