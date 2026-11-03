# ott — ESR TP2 OTT streaming overlay

Source of truth for agents working in `ott/`. Read `context/assignment.md`
before any mission-relevant change.

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
| `src/main/java/dev/pl36/Main.java` | Entry point `ott <node_id> <scenario.yaml>`; builds and prints the local routing table. TCP client/server sketches are commented out. |
| `src/main/java/dev/pl36/scenario/` | `Scenario`, `ScenarioParser`, `Node`, `Neighbor`, `InvalidScenarioException`, `ParseScenarioDemo`. Strict YAML parsing and validation. |
| `src/main/java/dev/pl36/routing/RoutingTable.java` | Per-process view: the local node plus its direct neighbours. Not yet a per-flow table. |
| `resorces/cenario1.yaml`, `resorces/cenario2.yaml` | Test scenarios (see `context/scenarios.md`). |
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
- The emulator image has **no JDK** (verified: `java` and `javac` are absent).
  Build the JAR on the host and copy it through `/volume`. To run Java inside
  the emulated nodes, add `openjdk-21-jdk-headless` to `container/packages.txt`
  and rebuild with `docker compose build core`. If that package is unavailable
  in the base image, fall back to `default-jdk-headless`.

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
