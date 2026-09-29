# TP1 Topology — CORE 9.2.1

The TP1 reference environment uses **CORE 9.2.1**.

The `tp1.xml` file contains the topology used throughout the TP1 experiments.
Open this file in the CORE GUI and start the emulation before performing the
experiments described in the lab guide.

## Topology

- `Streamer` (PC) — `sw1`
- `PC1` (PC) — `sw1`
- `PC2` (PC) — `sw1`
- `sw1` — `routerA` (Router)
- `routerA` — `routerB` (Router): **test link**
- `routerB` — `sw2`
- `sw2` — `PC3` (PC)
- `sw2` — `PC4` (PC)

## IPv4 Addressing

Left network `10.0.1.0/24`:
- `routerA`: `10.0.1.1/24`
- `Streamer`: `10.0.1.10/24`
- `PC1`: `10.0.1.11/24`
- `PC2`: `10.0.1.12/24`

Transit network `routerA`—`routerB` (`10.0.12.0/30`):
- `routerA`: `10.0.12.1/30`
- `routerB`: `10.0.12.2/30`

Right network `10.0.2.0/24`:
- `routerB`: `10.0.2.1/24`
- `PC3`: `10.0.2.13/24`
- `PC4`: `10.0.2.14/24`

The `router` nodes provide IP forwarding and OSPF. The `PC` nodes use the
CORE DefaultRoute service.

## `routerA`—`routerB` Test Link

The initial configuration of the test link is:

- bandwidth: **10 Mbit/s**;
- delay: **10 ms**;
- loss: **0%**.

These parameters are modified during the experiments according to the
instructions in the lab guide.

## Multicast

Stage 3 uses multicast group `239.10.10.10`.

The package provides `multicast/enable-multicast.sh` to configure the required
static multicast route `(S,G)` using SMCRoute. Run the script inside both
`routerA` and `routerB` as instructed in the lab guide.
