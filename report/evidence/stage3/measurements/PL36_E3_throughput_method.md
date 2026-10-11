# Verified offline analysis — 2026-10-10

Sources: `../captures/PL36_E3_U1_PC3.pcap`, `../captures/PL36_E3_U2_PC3_PC4.pcap`, and `../captures/PL36_E3_multicast_PC3_PC4.pcap`; original source paths are preserved in `../../../todo/00-stage3-requirements-map.md`.

Classic Ethernet PCAP records were parsed offline using Python struct. IPv4/UDP selection: source 10.0.1.10, destination port 5004 or 5005. RTP version, PT, sequence, timestamp and SSRC were decoded from UDP payloads; RTCP compound packet types/lengths and sender-report fields were decoded separately. No experiment was executed.

Throughput interval: [0,30) s relative to each capture's first packet. Sum original frame lengths of selected packets; multiply by 8/30/1,000,000 for Mbit/s. Includes captured RTP/RTCP Ethernet frame bytes, excludes uncaptured physical overhead. Selected bytes: U1 4,025,970; U2 7,993,290; multicast 4,025,970. Equal-duration windows need not represent identical content phases. Actual capture filters remain uncertain, so conclusions about absent traffic are limited to available captures.

`PL36_E3_throughput.csv` records interval rates; `PL36_E3_verified_analysis.json` records full-capture stream identities, counts, packet references and RTCP examples. Full-capture packet counts, durations, stream headers and RTCP counts independently reproduce the initial audit. Source-video metadata and SDP provenance were verified in task 01.

Replication interpretation: assignment p. 4 places PC3/PC4 on sw2. The supplied `../xubuncore/Dockerized-Coreemu-Template/volume/TP1/multicast/enable-multicast.sh` (relative to report root), inspected without execution, configures one outgoing interface on each router. Thus receiver-facing replication is on the shared LAN/switch, not two outgoing routerB routes. This is architectural inference, not downstream capture evidence.
