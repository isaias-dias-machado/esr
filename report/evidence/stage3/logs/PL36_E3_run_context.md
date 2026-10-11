# Stage 3 run context — reviewed 2026-10-10

Source of execution observations: `../../../user_task.md` (relative to this file). Original user notes preserved there.

- User confirms the correct interface was used for the captures: interpreted against task 01 as routerA toward routerB, eth1 in the supplied topology. Classic PCAP files do not independently establish the node/interface name.
- User reports no content or settings changes between scenarios and simultaneous reception, with both playbacks already running when capture began. This supports the two-receiver execution record, but supplies no before/after multicast join measurement.
- User clarification on 2026-10-10 confirms configured routerA–routerB bandwidth **10 Mbit/s**, one-way delay **10 ms**, and loss **0%**, unchanged across scenarios. These are operator-confirmed settings, not inferred from measured traffic or assignment defaults.
- Earlier notes report average 200 packets/s and RTT 22.04 ms without an identified scenario/measurement source. Retained for traceability, but not used as configured parameters or as verified comparison results. Configured 0% loss does not establish measured end-to-end packet loss.
- Exact capture filters are not confidently recalled. Assignment pp. 17–18 prescribe `udp and host $STREAMER_IP` for unicast and `udp and dst host 239.10.10.10` for multicast. Do not label these as verified actual filters.

Assistant read-only verification:

- `capinfos` on `../captures/PL36_E3_U1_PC3.pcap`: 6,609 packets, 65.474684 s between first/last packets.
- `capinfos` on `../captures/PL36_E3_U2_PC3_PC4.pcap`: 18,632 packets, 93.006928 s.
- `capinfos` on `../captures/PL36_E3_multicast_PC3_PC4.pcap`: 3,705 packets, 36.756238 s.
- Fresh file analysis with `ffprobe` of `../xubuncore/Dockerized-Coreemu-Template/volume/TP1/media/video_rtp.mp4` (path relative to report root) reproduces `PL36_E3_video_probe.json`: H.264 video 1,027,962 bit/s, AAC audio 23,986 bit/s, total file 1,056,762 bit/s. Assignment streaming commands omit audio. Approximate video bitrate is 1.028 Mbit/s; observed RTP/link rates require separate capture analysis.
- Local `{pc3,pc4,multicast}.sdp` match files under `../xubuncore/Dockerized-Coreemu-Template/volume/TP1/rtp/` (relative to report root) after newline normalization. Destinations respectively `10.0.2.13`, `10.0.2.14`, `239.10.10.10`; port 5004, PT 96, H264/90000 in each. SDP describes intended sessions; packet-level mapping remains task 05.

Capture original paths and hash records: `../../../todo/00-stage3-requirements-map.md` and `PL36_E3_capture_inventory.txt`. No experiments were run or source evidence altered in this review.
