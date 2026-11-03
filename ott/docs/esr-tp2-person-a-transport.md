Plan: esr-tp2-person-a-transport
Branch: feat/transport (branch AFTER agent/shared-net-interfaces merges)
Role: Person A — transport & membership ("the plumbing")
Rubric mapping: overlay topology (10%), half of control protocol (15%),
and the foundation everything else runs on.

Goal
Make the interfaces in dev.pl36.net.api real: TCP control connections between
static YAML neighbours, UDP data channel, and reliable detection of
neighbour death. Every other workstream consumes only
ControlMessenger/DataChannel, so nothing above this layer ever touches
a Socket.

Locked decisions
- Control = TCP, data = UDP, both on the scenario port (see interface plan).
- Neighbour set is static from the YAML (assignment Strategy 1): join =
  process start, leave = GOODBYE or connection death.
- Java 21 virtual threads for the accept loop and per-connection readers.

Milestones

M1 (week 2): TcpControlMessenger skeleton
- ServerSocket on the scenario port; accept loop on a virtual thread; one
  virtual thread per connection reading length-framed messages via WireCodec.
- Connect-out to declared neighbours with a deterministic tie-break to avoid
  double connections: the node with the LOWER id initiates, the higher id
  only accepts. Both sides then know nodeId <-> socket from the HELLO
  handshake (HELLO carries the sender's node id).
- Connection registry: nodeId <-> open connection, built from
  RoutingTable.from(scenario, selfId) which already gives neighbour
  addresses.
- send/sendToNeighboursExcept per the interface.

M2 (week 3): UdpDataChannel + death detection
- One DatagramSocket on the same port number; sendDatagram resolves the
  neighbour address from the local scenario view; onDatagram maps the
  source address back to a node id.
- Neighbour death: reader thread hits EOF/IOException -> close connection,
  fire onNeighborDown. Process kill (SIGKILL/SIGTERM) closes sockets, so
  FIN/RST propagates naturally — verify this experiment is enough before
  building keepalives.
- Stretch (only if half-open connections appear in demos): KEEPALIVE every
  10 s, declare death after 3 misses.
- Graceful GOODBYE on shutdown hook (Ctrl-C), then close.
- Replace the commented-out TCP sketch in Main.java with the real stack:
  Main starts the messenger, prints the routing table, logs every
  onNeighborUp/onNeighborDown.

M3 (week 4 checkpoint, joint): integration
- Scenario 1 end-to-end with B and C: two processes on localhost (adapt
  cenario1.yaml to 127.0.0.1 addresses or a small local test YAML), HELLO
  on start, log to prove full-duplex, kill a process -> onNeighborDown on
  the survivor within seconds.
- Then repeat inside CORE: image now has openjdk-21-jdk-headless; build JAR
  on host, copy through ./volume, run one process per end-system node.

Testing
- Two-process localhost test: start node 1, start node 0 (connects out),
  assert HELLO handshake; kill one, assert onNeighborDown.
- Wireshark (./xubuncore -w) capture for the report: show one TCP control
  connection and one UDP data flow — report material for section 3.

Out of scope
- Routing/flow logic (B), video (C), message semantics beyond HELLO/
  GOODBYE/KEEPALIVE.

Risks
- Half-open connections: only add KEEPALIVE if demos show we need it
  (simplicity bias — discuss in report instead of building speculatively).
- Blocking reads: keep per-connection reader threads simple; no selectors
  unless a measured problem.
- If you finish early (likely — this layer is the smallest), help B with
  the failover state machine; do not pad your layer with speculative
  features.

Acceptance
- onMessage/onNeighborUp/onNeighborDown fire correctly over real TCP.
- B and C run against your classes with zero changes to their code.
- Demo evidence: HELLO exchange and death detection logs.
