# Stage 3 evidence inventory

Three initial screenshots and two later RTP screenshots are preserved; the later files were renamed by scenario and content. Three Stage 3 captures, SDP files, a video probe and verified analysis are available. Local captures match the originals in
`../xubuncore/Dockerized-Coreemu-Template/volume/TP1/results` (path relative to the
report directory), verified by SHA-256 on 2026-10-10. See the
[requirement map and source inventory](../../todo/00-stage3-requirements-map.md).
The operator confirms the intended interface, unchanged settings/content and both
receivers playing before capture, with configured 10 Mbit/s bandwidth, 10 ms
one-way delay and 0% loss. Exact capture filters remain uncertain. See [run context](logs/PL36_E3_run_context.md);
verified offline analysis is integrated into the report; the receiver-join comparison remains missing (task 03).

| File | Review | Use |
| --- | --- | --- |
| [U2 RTP to PC3 and PC4](figures/PL36_E3_U2_two_RTP_destinations.png) | Shows decoded RTP addressed to both receivers; U2 frame 1 matches source PCAP. | Report Figure 1. |
| [Multicast RTP packet](figures/PL36_E3_multicast_RTP_packet.png) | Shows decoded RTP to group 239.10.10.10; frame 1 sequence 32059, timestamp 977120236 and SSRC 0x3717b922 match source PCAP. | Report Figure 2. |
| [Multicast exclusion filter](diagnostics/PL36_E3_multicast_exclusion_filter.png) | Zero displayed of 3,705 packets for `ip.dst != 239.10.10.10`; offline PCAP inspection confirms all captured packets target the group. | Report Figure 3; limited to captured traffic. |
| [Earlier multicast UDP packet](figures/PL36_E3_multicast_udp_packet.png) | Shows addresses and UDP; RTP was not decoded. | Retained, superseded by Figure 2. |
| [Earlier RTP header crop](figures/PL36_E3_rtp_header_fields.png) | Matched to multicast PCAP frame 9 by sequence 32067, timestamp 977116636 and SSRC 0x3717b922. | Retained, superseded by Figure 2. |

## Storage

- `captures/`: U1, U2 and multicast PCAP copies matching their originals.
- `logs/`: capture inventory, three SDP files and video probe; run context recorded with remaining uncertainties.
- `measurements/`: preliminary `PL36_E3_initial_audit.json`; verified analysis and equal-interval throughput CSV/method now available.
- `figures/`: report figures.
- `diagnostics/`: supporting screenshot used as Figure 3.

For each run, record original filename, scenario, date, capture node/interface,
link bandwidth (Mbit/s), one-way delay (ms), loss (%), capture duration (s), exact
capture/display filters, and receiver timing. Document unknowns explicitly.
Derived measurements must identify the source capture, interval start/end (s),
byte basis, filter, and calculation. Keep this inventory current as evidence arrives.

See [task 01](../../todo/01-recover-evidence-and-settings.md) for recovery and
[task 04](../../todo/04-verify-and-integrate-results.md) for verification.
