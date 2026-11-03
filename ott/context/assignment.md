# Assignment — OTT multimedia delivery over an application-level overlay

Structured summary of *Trabalho Prático Nº.2 — Serviço Over the Top para entrega
de multimédia*, ESR 2026/27 (8 classes, 9 weeks). This is a paraphrase, not a
transcript. The PDF (`ESR-TP2-26-27.pdf`, repository root) is authoritative.

## 1. Motivation

Internet usage shifted from end-to-end communication to continuous, often
real-time, content consumption. The IP underlay was not designed for it, so
delivery is handled by application-layer services — CDNs and Over-the-Top
(OTT) services. An OTT multimedia service can build its own application-level
overlay (for example application multicast) to route around congestion and
resource limits, delivering media in real time and with adequate quality.
Netflix and Hulu are the familiar examples: they stream over the public
Internet while forming their own overlay on top of TCP/UDP or HTTP.

The goal is to design and prototype such a service, optimising resource usage
and the user's quality of experience.

## 2. Core concepts

### Underlay and overlay

- The **underlay** is the IP network of routers, switches and end systems.
- The **overlay** is a set of application processes (`O1, O2, ...`) running on
  selected underlay machines. Overlay nodes are ordinary programs started
  manually on a host.
- Overlay links are transport connections (TCP or UDP) that traverse one or
  more underlay links. The overlay is much smaller than the underlay; a
  one-to-one mapping is neither imaginable nor desirable.
- A single overlay link may cross several underlay hops. Example in the
  assignment: `O3–O5` may be carried by `n9–n5–n2` if that is the best IP path.
- Overlay placement can be computed or chosen by hand. CORE can run an overlay
  node on any container, including routers and switches, but in reality those
  are dedicated devices. **Prefer end systems (PCs) for overlay nodes.**

### Server and clients

- There is one streaming server, which is also an overlay node.
- Clients receive and play the content, and are also overlay nodes.
- The naive approach (one connection per client, one copy of the stream per
  client) does not scale: beyond some N the server or the network cannot
  sustain N flows without quality loss.
- The overlay approach forwards and replicates one stream through intermediate
  nodes, saving server and underlay resources.

### Bootstrapping and neighbour maintenance

- On startup a node must know at least one other node to contact.
- A well-known, predetermined **bootstrapper** can serve this purpose. It helps
  build the network: the first contact both announces the new node and returns
  a list of peers to connect to.
- The streaming server can be a permanent bootstrapper, since it knows every
  overlay node.
- A simple maintenance strategy is to guarantee each node at least N
  neighbours (for example N = 3).
- Neighbour connections stay active, and may be monitored, for the whole run.

### Routing table semantics

Each node keeps a table of routes, one entry per flow. An entry is:

```
{ Flow: F, Cost: <metric>, Origin: <incoming connection>, Destinations: <outgoing connections>, State: active|inactive }
```

- **Origin** is the connection the flow arrives from.
- **Destinations** are the connections the flow must be replicated to.
- **Cost** is the route metric (hop count, delay, bandwidth, ...).

Worked example from the assignment (Figure 1):

- Best path `S1 → C1` is `O1–O2–O3–O4`.
- Best path `S1 → C2` is `O1–O2–O7–O8`.
- Both share the `O1–O2` link. Sending two copies over it wastes bandwidth.
- Therefore the entry at `O2` must say: flow F arrives from `O1` on the
  `O1–O2` connection with a cost of 1 hop, and must be replicated to `O7` and
  `O3` on the `O2–O7` and `O2–O3` connections.
- Compact form: `{ Flow: F, Origin: O1, Metric: 1, Destinations: O7, O3, State: active }`.

## 3. Minimum requirements

1. An overlay node is a program executed manually on an underlay machine.
2. Overlay nodes can be created and destroyed dynamically (process start/stop).
3. On joining, a node establishes and maintains transport connections (TCP or
   UDP) with X nodes, its neighbours.
4. On startup a node must know at least one other node — possibly the content
   server — that helps it obtain its list of X neighbours.
5. Nodes build and maintain a routing table, one route per flow. Since there is
   one flow, that is one route `(Flow, Cost, Origin, Destinations, State)`.
6. The topology must support alternative routes and scenarios with multiple
   simultaneous clients.
7. The network must be monitored and routes maintained and updated
   dynamically, following node joins/leaves and changes in network conditions.
8. Overlay nodes forward all data according to that table.
9. At least two servers, each offering at least two distinct video streams.
10. Network metrics are used to build the routes.

## 4. Suggested stages

The stages are not strictly sequential.

### Stage 0 — Preparation

- Pick a language that suits every group member.
- Prepare a test topology and test scenarios on it.
- Choose the overlay transport: TCP or UDP (HTTP is possible but harder).
- Implement a simple client/server for that transport, ideally one program
  acting as both.

### Stage 1 — Building the overlay

- Build an application (`ott`) that is simultaneously client and server,
  listens on a predefined port, and exchanges messages full-duplex. Test with
  a simple `HELLO`.
- Choose a construction strategy.

**Strategy 1 — manual.** Pass the X neighbours on the command line or in
configuration, e.g. `$ ott <neighbour1> <neighbour2> <neighbour3>`. Departure
is detected when the transport connection disappears. The neighbour table and
its connections never change during the run. No control node is needed.

**Strategy 2 — controller/bootstrapper.** Pass only one contact node, e.g.
`$ ott <bootstrapper>`. The new node registers, identifying itself, and
receives a list of X neighbours. The bootstrapper decides who connects to whom
from its knowledge of the overlay; a manually built configuration file is an
acceptable source. Define the departure policy: explicit goodbye, periodic
re-registration, or both.

**Other strategies** are allowed if discussed with the lecturer.

In all cases keep neighbour connections active and optionally monitor them.

### Stage 2 — Building routes for the flows

- Define the metric for the best route (hop count, delay, ...).

**Strategy 1 — server-initiated periodic announcements.**

- The server periodically sends a control announcement identifying the server,
  the flow and the metric value.
- On receipt, each node updates its route table with
  `(Server/Flow, Origin, Metric, Destinations, State)`. Entries stay inactive
  until needed.
- Each node re-forwards the message to all neighbours, avoiding cycles and not
  sending it back on the connection it arrived from (controlled flooding, for
  example forward each message id only once).
- Before forwarding, the metric is updated (for example plus one hop).
- Entries start inactive. A client activates a route by sending an activation
  message along the reverse path.
- Each activation is forwarded following the `Origin` field of the route.
- A client activates only when it wants the data. With no clients, routes exist
  but are inactive and no traffic flows.

**Strategy 2 — client-initiated request to the server.**

- The client that wants a stream sends an explicit route request for it.
- This assumes the server has registration information and knows the overlay,
  so it can choose the path and teach it to the client, which then installs it.
- If the server does not know the topology, the request is flooded (controlled)
  until it reaches a node that already has information about the stream.

**Other strategies** are allowed if discussed with the lecturer.

### Stage 3 — Testing the overlay paths

- Define a test message (for example `PING`), generated by the streaming server
  and forwarded by overlay nodes using the routes.
- Each forward logs debug information and/or accumulates the path taken until
  the destination(s).

### Stage 4 — Streaming

**Strategy 1 — based on the course book code.**

- Start from the book's code (Python and Java versions available).
- Adapt it if the chosen language is neither.
- Using the teaching team's adjusted example, build a server that reads video
  from a file and sends it as numbered packets into the overlay.
- Build a client that receives numbered packets from the overlay and plays the
  video in a window.
- Use `movie.Mjpeg` as the test video: constant frame rate, a sequence of
  independent images sent at fixed intervals.

**Strategy 2 — propose or find alternatives**, discussing them with the
lecturer.

## 5. References

1. Kurose, J. (2025). *Computer Networking: A Top-Down Approach* (9th ed.).
   Pearson. Programming exercises: <https://gaia.cs.umass.edu/kurose_ross/programming.php>
2. Adapted book examples: <https://www.di.uminho.pt/~flavio/ESR/ProgEx.zip>
   (`wget https://www.di.uminho.pt/~flavio/ESR/ProgEx.zip`)
