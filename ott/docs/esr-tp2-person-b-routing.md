Plan: esr-tp2-person-b-routing
Branch: feat/routing (branch AFTER agent/shared-net-interfaces merges)
Role: Person B — routing & flow management ("the brain")
Rubric mapping: flow management (25%) — the single biggest code item —
plus the other half of control protocol (15%). The non-negotiable demo
requirements (alternative routes, dynamic route updates) live here.

Goal
Build and maintain the per-flow routing table
(Flow, Cost, Origin, Destinations, State) from control messages, forward
UDP stream data according to it, and repair routes when nodes die.
Develop against LoopbackMessenger first; real TCP only at the week-4
checkpoint.

Milestone 0 (week 1-2): route strategy decision — RESEARCH TASK
The group chose not to pre-decide. Your job:
- Read Stage 2 of context/assignment.md: Strategy 1 (server announcements,
  controlled flooding) vs Strategy 2 (client-initiated requests).
- Write a 1-page decision note: trade-offs for our static-neighbour
  overlay, and how each strategy handles (a) failover and (b) multiple
  simultaneous clients. Present to the group by end of week 2.
- Constraint: the decision must not require changes outside dev.pl36/net
  and your own routing package (the envelope and interfaces were built to
  survive this decision — adding MessageType values is expected; changing
  the wire frame is not).
- Hint to evaluate, not follow: Strategy 1 repairs routes on the next
  announcement cycle almost for free, which is exactly what the failover
  demo needs. But decide for yourself and defend it in the report.

M1 (weeks 2-3): flow table + route learning (with LoopbackMessenger)
- New FlowTable in dev.pl36.routing (evolve or sit beside RoutingTable):
  one entry per (flow, source) with Cost, Origin, Destinations, State.
- Implement the chosen strategy's control-message handling: dedup by
  messageId (never forward the same id twice), metric update before
  forwarding, never send back on the arrival connection (use
  sendToNeighboursExcept).
- Entries start inactive; client activation flips State; with no clients,
  routes exist but no data flows (assignment requirement).
- Unit tests purely on LoopbackMessenger: 3+ fake nodes in one JVM,
  announcements propagate to all, no duplicates, no loops.

M2 (weeks 5-6): forwarding + failover — the demo-critical part
- DATA path: onDatagram -> parse DataPacketHeader -> look up flow ->
  replicate to Destinations, never to Origin; inactive state -> drop and
  log. One copy per shared link even with multiple downstream clients
  (the O2 replication example in context/assignment.md).
- onNeighborDown: mark entries through that neighbour; per your strategy,
  routes must switch to an alternative within one repair cycle. Log
  before/after path so the demo shows the switch.
- Multiple flows: >=2 servers x 2 streams, so the table is keyed by flow
  and each is independent (test matrix case 8).
- Metric: hop count is the accepted minimum; stretch goal (only if time):
  RTT probes over the control channel as Cost, which strengthens the
  "monitored and updated dynamically" requirement.

M3 (week 4 checkpoint, joint): integration
- On real TCP over localhost scenario 1: announce -> activate -> see DATA
  forwarded. Then kill the intermediate node and watch the repair.
- Scenario 2 is your failover base (disjoint branches after node 1).
- Scenario 3 exercises replication at branch points.

Testing
- Fake-topology failover test (LoopbackMessenger): break a link mid-flow,
  assert the table converges to the alternative and data resumes.
- Full test matrix cases 1-3, 6-8 (context/scenarios.md) with the group.

Out of scope
- Sockets (A), video/pacing/playback (C), scenario YAML files (C).

Risks
- This is the hardest workstream — start the research note immediately,
  do not wait for A's TCP.
- If LoopbackMessenger cannot express something you need (e.g. one-way
  link breakage), ask the interface agent to extend it — do not fork it.
- Static neighbours mean the neighbour SET never self-heals; failover must
  reroute flows among REMAINING neighbours. State this limitation honestly
  in the report (rubric rewards critical appreciation, not hidden gaps).

Acceptance
- All 8 test-matrix routing cases pass first on the fake, then on CORE.
- Failover demo: kill a node mid-stream, data resumes on the alternative
  route within one repair cycle, with logs proving it.
- Strategy decision note archived for the report's protocol section.
