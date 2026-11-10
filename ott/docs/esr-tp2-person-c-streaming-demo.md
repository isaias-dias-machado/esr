Plan: esr-tp2-person-c-streaming-demo
Branch: feat/streaming (branch AFTER agent/shared-net-interfaces merges)
Role: Person C — streaming, scenarios, demo & report ("the product owner")
Rubric mapping: streaming protocol (10%), plus ownership of the integrated
solution (30%) — including report quality and defence — which is where
groups most often bleed points. You are NOT the underloaded one.

Status (checked against commit 3adc797, 2026-11-10; mvn test: 25 pass, BUILD SUCCESS)
Only fully delivered items are marked Done; everything else is Not done.

M1 — streaming over UDP, in isolation
- Done — ProgEx Mjpeg pair adapted into dev.pl36.streaming (MjpegVideoStream,
  StreamSource, StreamSink, FrameHandler/ConsoleFrameHandler,
  StreamingLoopbackDemo); resorces/movie.Mjpeg added.
- Done — server side: frames numbered via DataPacketHeader (flowId + sequence)
  and sent over a DataChannel at constant frame rate (`ott server`,
  timer-driven; default 10 fps, the plan's 24 fps is overridable with --fps).
- Done — client side: receives, reassembles, tolerates loss and logs gaps by
  sequence (`ott client` + StreamSink); proven in-JVM over the loopback demo
  (all 500 frames) and cross-process over real UDP on localhost
  (net/UdpDataChannel).
- Not done — play in a window: playback is console-only (ConsoleFrameHandler),
  no GUI player yet.
- Not done — two servers x two distinct streams: --video/--flow flags exist but
  only one movie file is in resorces/.

M2 — scenarios & testbed
- Not done — n17 typo fix: resorces/cenario1.yaml:13 and resorces/cenario2.yaml:19
  still declare n17 as 10.1.11.12/24 (should be 10.1.11.17/24 per
  context/scenarios.md).
- Not done — localhost variant of cenario1.yaml.
- Not done — resorces/cenario3.yaml (11 overlay nodes, 5 clients).
- Not done — java -version inside a running CORE node (openjdk-21-jdk-headless
  is in packages.txt but unverified in a node).

M3 — first end-to-end over the real overlay
- Not done — UdpDataChannel is a placeholder with a fixed peer set; no overlay
  forwarding yet (depends on A/B).

M4 — test matrix & demo runbook
- Not done — no runbook or evidence runs yet for the 8-case matrix in
  context/scenarios.md.

M5 — report
- Not done — no TP2 LNCS skeleton/draft yet (the root report/ directory holds
  TP1 material only).

Goal
Own everything the graders actually see: the streams, the scenarios, the
demo runbook, and the LNCS report. Work continuously from week 1 — the
integrated solution is your deliverable, not an afterthought.

M1 (weeks 1-2): streaming over UDP, in isolation
- Fetch the book examples: wget https://www.di.uminho.pt/~flavio/ESR/ProgEx.zip
  — adapt the Mjpeg server/client (Java version) into dev.pl36.streaming.
- Server side: read movie.Mjpeg, split frames, number them via
  DataPacketHeader (flowId + sequence), send through DataChannel at
  constant frame rate (timer-driven, e.g. 24 fps).
- Client side: receive UDP datagrams, reassemble, play in a window;
  tolerate lost frames (log gaps by sequence number, play what arrives —
  honest report material about UDP trade-offs).
- Test entirely over loopback sockets first; no overlay needed yet.
- Two servers x two distinct streams (minimum requirement 9): each server
  process loads a different movie, flowIds like "server1/movie.mjpeg".

M2 (weeks 1-3, parallel): scenarios & testbed — start immediately
- Fix the known n17 address typo in cenario1.yaml and cenario2.yaml
  (10.1.11.12 -> 10.1.11.17, per context/scenarios.md) — flag it to the
  group first, it is part of the scenario contract.
- Create a localhost variant of cenario1.yaml (127.0.0.1 addresses) for
  fast three-terminal integration without CORE.
- Create resorces/cenario3.yaml for scenario 3 (11 overlay nodes, 5
  clients, mapping in context/scenarios.md; overlay nodes only on
  end systems n16-n24). Keep the `resorces` typo.
- Verify the CORE image runs Java inside nodes (docker compose build core
  after any packages.txt change; java -version inside a node).

M3 (week 4 checkpoint, joint): first end-to-end
- One client, one stream over the real overlay (localhost first, then CORE
  scenario 1): server sends, overlay forwards, client plays. This is the
  moment the group knows the architecture works.

M4 (weeks 5-6): test matrix & demo runbook
- Own the 8-case matrix in context/scenarios.md; schedule group runs,
  record evidence (logs, Wireshark captures, photos of playback).
- Write the demo runbook: exact commands per case, expected observation,
  which node gets killed and when. Non-negotiables to cover: dynamic
  join/leave, alternative routes on failure, multiple simultaneous
  clients, routes updated as conditions change.

M5 (weeks 1-9, continuous): report
- Week 1: LNCS skeleton with the 6 recommended sections
  (context/rubric.md); keep a running decisions log (strategy choices,
  trade-offs — B's route-strategy note goes in section 3).
- Week 4-6: draft architecture and protocol sections while fresh.
- Week 7-8: tests/results section from the matrix runs; conclusions with
  honest limitations (static neighbours, hop-count metric, UDP loss).
- Week 9: defence prep — every member must explain every part, so run
  cross-walkthroughs; submission names ESR-TP2-PL3-G6-Rel.pdf / -Codigo.zip.

Out of scope
- Routing logic (B), sockets (A); but you own the glue that proves both.

Risks
- Playback timing is fiddly; the book code handles it — adapt, don't
  reinvent (simplicity bias).
- If your code work finishes early, pour time into the report and demo
  evidence — that is the 30% item, not slack.
- UDP loss inside CORE is realistic; capture frame-gap logs as evidence
  rather than treating them as a bug to hide.

Acceptance
- 4 streams from 2 servers, playable by multiple simultaneous clients.
- A complete runbook that a group member can execute cold.
- Report draft exists from week 1, not week 8.
