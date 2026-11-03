# Failure Modes of the OTT Overlay Service

## Context

This document catalogues the failure modes of the OTT media delivery prototype
described in *Trabalho Prático Nº.2 — Serviço Over the Top para entrega de
multimédia*.

Assumptions taken from the current design:

- One process is one overlay node.
- There is no bootstrapper. Every node loads the same shared scenario file
  (the YAML under `resorces/`), which declares the nodes, their addresses and
  the transport links between them. A node connects out to the neighbours the
  file lists for it.
- A node keeps its own routing table. Each entry is one flow, with a cost, an
  origin (the connection the data arrives from) and a set of destinations (the
  connections the data must be replicated to).
- Configuration problems (malformed YAML, bad node id, port in use, mismatched
  copies of the scenario file) are out of scope here. They are rejected at
  startup by `ScenarioParser` and `RoutingTable.from`, and are not dynamic
  events. This document only covers failures that arise **while the overlay is
  running**: crashes, latency and bandwidth changes, packet loss, partitions,
  joins and leaves.

The ranking below follows the weights of the evaluation in the assignment:
flow management (25%), control protocol (15%), integrated solution (30%), and
the explicit requirement that *"a rede deve ser monitorizada e as rotas devem
ser mantidas e atualizadas dinamicamente, em função da entrada e saída de nós,
bem como de alterações nas condições da rede"*. The failures that this sentence
asks us to handle come first.

## Ranked summary

| Rank | ID | Failure mode | Tier |
|-----:|----|--------------|------|
| 1 | FM-01 | Intermediate node on an active route crashes | 1 |
| 2 | FM-02 | Abrupt neighbour crash leaves a half-open connection | 1 |
| 3 | FM-03 | Streaming server crashes | 1 |
| 4 | FM-04 | Latency or bandwidth changes in the underlay | 1 |
| 5 | FM-05 | Overlay partition | 1 |
| 6 | FM-06 | Branching node fails to replicate to all destinations | 2 |
| 7 | FM-07 | Route activation request is lost | 2 |
| 8 | FM-08 | Stale route entry points at a departed node | 2 |
| 9 | FM-09 | Announcement is lost | 2 |
| 10 | FM-10 | Flooding loop or duplicate announcements | 2 |
| 11 | FM-11 | Connection stalled but still open | 2 |
| 12 | FM-12 | Packet loss in the underlay | 3 |
| 13 | FM-13 | Bandwidth overload on a node or link | 3 |
| 14 | FM-14 | Out-of-order or duplicated packets | 3 |
| 15 | FM-15 | Client joins after the stream has started | 3 |
| 16 | FM-16 | Client subscribes to a flow with no active source | 4 |
| 17 | FM-17 | Neighbour count drops below X | 4 |
| 18 | FM-18 | Graceful neighbour exit | 4 |
| 19 | FM-19 | Client crashes | 4 |

---

## Tier 1 — Core dynamic events

These are the events the assignment explicitly requires the overlay to detect
and recover from. They belong in the tests and in the critical appraisal of the
report.

### FM-01 — Intermediate node on an active route crashes

- **Event.** A node that is forwarding an active flow stops without warning
  (process killed, container destroyed).
- **Observable symptom.** Clients downstream of that node stop receiving data.
  Upstream nodes keep sending into a connection that no longer has a reader.
- **Detection question.** Who notices first, the neighbours of the crashed node
  or the clients that stopped playing? A client that stops receiving has no way
  to tell a broken route from a stalled one.
- **Effect.** The whole subtree below the crashed node loses the stream. The
  routes in the affected nodes still point through it.
- **Hits.** Flow management (25%), alternative routes, dynamic route updating.

### FM-02 — Abrupt neighbour crash leaves a half-open connection

- **Event.** A neighbour dies without closing its socket. The kernel of the
  peer does not send a FIN or RST, so the local side keeps a connection that
  looks open.
- **Observable symptom.** Writes to the socket succeed locally and are silently
  discarded. Nothing is logged as an error.
- **Detection question.** How does a node distinguish a silent peer from a dead
  one without application-level traffic? The assignment says abandonment is
  detected "quando a conexão de transporte deixa de existir", which is not the
  same thing as the transport reporting an error.
- **Effect.** Every route that uses this connection keeps forwarding into a
  black hole. This is the mechanism behind FM-01 and FM-08.
- **Hits.** Flow management (25%), monitoring requirement, control protocol
  (15%) if liveness must be proven by the protocol.

### FM-03 — Streaming server crashes

- **Event.** The node that sources a flow stops.
- **Observable symptom.** Every client of that flow stops receiving at the same
  moment.
- **Detection question.** Is the crash local to the server (all flows lost) or
  should another server take over? The assignment requires at least two servers
  with at least two flows each, so the overlay is expected to survive losing
  one.
- **Effect.** Total outage of the affected flow. The routes stay in the tables
  with no data.
- **Hits.** Integrated solution (30%), the two-server requirement.

### FM-04 — Latency or bandwidth changes in the underlay

- **Event.** The CORE topology changes the cost of a path: added delay on a
  link, a link rate change, or congestion that raises the effective delay.
- **Observable symptom.** Routes in the tables still carry the metric measured
  when they were built. Quality drops while the tables claim everything is
  fine.
- **Detection question.** Are metrics measured continuously, or only when an
  announcement passes? A metric that is only sampled on announcement cannot
  react to a change between announcements.
- **Effect.** Traffic keeps using a path that is no longer the best one, and
  possibly one that can no longer carry the stream.
- **Hits.** The "utilização de métricas da rede" requirement, flow management
  (25%).

### FM-05 — Overlay partition

- **Event.** The underlay splits into two parts that cannot reach each other
  (link down, or a set of nodes removed at once).
- **Observable symptom.** Some clients stop receiving; others keep playing
  normally. Nodes on each side still believe the overlay is complete.
- **Detection question.** How long does it take before a node concludes that a
  reachable set of neighbours is gone, rather than slow?
- **Effect.** Clients on the side without a path to the server cannot be
  served. No alternative route exists inside their half.
- **Hits.** Alternative routes requirement, integrated solution (30%).

---

## Tier 2 — Control protocol robustness

How the routing machinery misbehaves. These are mostly silent failures: the
overlay keeps running and looks healthy.

### FM-06 — Branching node fails to replicate to all destinations

- **Event.** A node whose route entry has more than one destination forwards
  the flow to some of them and not to others.
- **Observable symptom.** Some clients play while others starve, even though
  both are below the same branching node.
- **Detection question.** What is the expected number of copies for an entry,
  and how is a missing copy noticed? Nothing in the data path reports it.
- **Effect.** Silent partial delivery. The affected clients look like network
  problems rather than routing bugs.
- **Hits.** Flow management (25%), multi-client requirement.

### FM-07 — Route activation request is lost

- **Event.** The client sends the activation message along the reverse path and
  it is dropped or never reaches the server.
- **Observable symptom.** The client believes it asked for the stream and
  waits. The route stays inactive everywhere.
- **Detection question.** Is activation confirmed, or is it fire-and-forget? A
  client that receives nothing cannot tell an inactive route from a broken one.
- **Effect.** No data flows to that client while the rest of the overlay is
  healthy.
- **Hits.** Control protocol (15%), flow management (25%).

### FM-08 — Stale route entry points at a departed node

- **Event.** A node leaves or crashes and the entries that named it are not
  removed.
- **Observable symptom.** Forwarding to that destination fails or is silently
  discarded (see FM-02).
- **Detection question.** Which entries are invalidated by a single neighbour
  loss, and who removes them?
- **Effect.** Data is sent to a hop that cannot deliver it. This is the
  persistent residue of FM-01 and FM-02.
- **Hits.** Dynamic route maintenance requirement.

### FM-09 — Announcement is lost

- **Event.** A control announcement does not reach some part of the overlay.
- **Observable symptom.** Those nodes never learn the route, or keep an older
  metric.
- **Detection question.** Is the next announcement enough to repair the state,
  and how long is that interval compared with a stream's tolerance for a
  missing route?
- **Effect.** Some paths are missing from the tables. Clients on those paths
  cannot activate a route.
- **Hits.** Control protocol (15%).

### FM-10 — Flooding loop or duplicate announcements

- **Event.** Controlled flooding fails to suppress a repeat: a node forwards an
  announcement back along the connection it arrived on, or forwards the same
  message twice.
- **Observable symptom.** The same announcement keeps circulating. CPU and
  bandwidth grow with the overlay size.
- **Detection question.** What identifies a duplicate? A message id, a hop
  count, or the arrival connection alone?
- **Effect.** Control traffic competes with the stream for bandwidth, and the
  tables may oscillate as entries are overwritten with worse metrics.
- **Hits.** Control protocol (15%), integrated solution (30%).

### FM-11 — Connection stalled but still open

- **Event.** The peer process is alive and the socket is open, but it no longer
  reads or writes (blocked, paused, or starved of CPU).
- **Observable symptom.** The connection is established. Data queues up in the
  socket buffer and then blocks the sender.
- **Detection question.** How does this differ from a slow link (FM-04) from
  the sender's point of view?
- **Effect.** One stalled peer can block the node that replicates to it,
  turning a local problem into a whole-subtree outage.
- **Hits.** Monitoring requirement, flow management (25%).

---

## Tier 3 — Streaming quality

Visible to the viewer. These shape the quality-of-experience discussion.

### FM-12 — Packet loss in the underlay

- **Event.** Packets are dropped on the underlay link, or on the overlay
  transport.
- **Observable symptom.** Gaps in the sequence numbers at the client, visible
  as artifacts or freezes.
- **Detection question.** Does the client detect a gap, and does it wait, skip,
  or ask for a retransmission?
- **Effect.** Degraded playback. The routing layer is unaware, since the route
  is still valid.
- **Hits.** Streaming protocol (10%), QoE discussion.

### FM-13 — Bandwidth overload on a node or link

- **Event.** More traffic than a link or a node can carry: too many clients
  below one branching node, or a link shared by several overlay connections.
- **Observable symptom.** Rising delay and loss on that segment only.
- **Detection question.** Is overload visible as a metric change (FM-04) before
  it becomes loss (FM-12)?
- **Effect.** Quality drops for every client below the congested point.
- **Hits.** Metric-driven routing, QoE discussion.

### FM-14 — Out-of-order or duplicated packets

- **Event.** The transport delivers packets out of order, or a packet reaches a
  client twice (for example after a route change).
- **Observable symptom.** The player shows frames out of sequence or repeats
  them.
- **Detection question.** Does the client rely on sequence numbers to reorder
  and discard?
- **Effect.** Visual glitches even when no data was lost.
- **Hits.** Streaming protocol (10%).

### FM-15 — Client joins after the stream has started

- **Event.** A client activates its route while the server is already sending.
- **Observable symptom.** The client receives from the middle of the stream.
- **Detection question.** Does the client start at the next frame, or does it
  need to wait for a key frame or buffer a fixed amount?
- **Effect.** Delayed start, or corrupt frames at the beginning.
- **Hits.** Multi-client requirement, streaming protocol (10%).

---

## Tier 4 — Edge cases

Worth naming in the report, not worth over-engineering.

### FM-16 — Client subscribes to a flow with no active source

- **Event.** With two servers and two flows, a client asks for a flow whose
  server is down or whose route is not active.
- **Observable symptom.** The client waits forever.
- **Detection question.** Does the client learn that the flow is unavailable,
  or does it wait indefinitely?
- **Effect.** One client is unserved while the overlay is otherwise healthy.
- **Hits.** Two-server requirement.

### FM-17 — Neighbour count drops below X

- **Event.** Enough neighbours leave that a node has fewer than the target
  number of connections.
- **Observable symptom.** The node is still connected, but with less
  redundancy.
- **Detection question.** Is a low neighbour count reported, and is there a way
  to recover it without a bootstrapper?
- **Effect.** Longer routes and fewer alternatives. The overlay is more fragile
  to the next failure.
- **Hits.** Overlay topology (10%).

### FM-18 — Graceful neighbour exit

- **Event.** A neighbour shuts down cleanly and closes its connections.
- **Observable symptom.** The connection closes and the node sees it
  immediately.
- **Detection question.** This is the easy case of leaving. Does the node treat
  it the same way as FM-02, or does it use the clean signal?
- **Effect.** Links disappear at once, unlike FM-02 where they linger.
- **Hits.** Overlay topology (10%), contrast with FM-02.

### FM-19 — Client crashes

- **Event.** A client process dies while its route is active.
- **Observable symptom.** Nothing fails for the other clients.
- **Detection question.** Does the overlay notice, or keep forwarding to a dead
  destination until the next announcement?
- **Effect.** Wasted bandwidth on the path to that client. Low impact, useful
  for the critical appraisal.
- **Hits.** Critical appraisal (item c of the evaluation).
