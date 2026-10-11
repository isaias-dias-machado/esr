# 03 — Verify multicast reception and receiver-join effect

Status: pending
Priority: baseline requirement — pp. 17–18 multicast experiment, p. 18 multicast evidence bullet, p. 19 Q3.
Purpose: show a single shared-link stream for two receivers and explain observed traffic changes when PC4 joined, including where replication occurs.
Prerequisites: task 01 receiver history and capture location; task 00 existing sources. Reuse existing evidence first.

Assistant: verify group `239.10.10.10`, RTP flow count and SSRC in the existing multicast PCAP/SDP. Preliminary audit reports one SSRC `0x3717b922`; upstream packets alone do not prove delivery to both clients or identify the join event. Inspect available timing before deciding whether a before/after rate comparison can be recovered.

- [x] User confirms in `user_task.md` that PC3/PC4 received simultaneously and both playbacks were running before capture. Integrated into `stage3.md` with attribution. The capture therefore contains no receiver-join transition.
- [ ] If no existing evidence supports Q3, user collects the minimum missing evidence: shared-link traffic with PC3 alone and after PC4 joins the same ongoing multicast stream, identifiable join timing, and a concise record of simultaneous playback. No sender restart between phases. A separate second experiment is unnecessary if existing data suffice.

Conditional new filenames: `PL36_E3_multicast_join_run2.pcap` and `PL36_E3_multicast_join_run2.md` under `evidence/stage3/captures/` and `logs/`; preserve the original capture. Record node/interface, actual settings, filter, join time (s relative to capture), receiver state and usable intervals (s). The before/after capture is a chosen method to answer Q3, not an extra mandatory assignment scenario or screenshot.

Assistant: analyze provided data with task 04; relate replication to the actual topology, distinguishing architectural inference from directly observed packets.

Completion: group/one-stream evidence, two-receiver execution, and a supported Q3 answer integrated into `stage3.md`. An unmeasured expectation alone does not close this task.

Review 2026-10-10: existing multicast capture verified and integrated: one RTP flow, SSRC `0x3717b922`, 1.073592 Mbit/s over [0,30) s. Simultaneous reception remains operator-confirmed. No join transition is available. The next actionable baseline work is the before/after evidence described above (p. 19 Q3); no repeated U1/U2 evidence is needed. Supplied topology places both clients behind sw2: routerB forwards to one LAN, and sw2 distributes frames to receiver ports (architectural inference).

Screenshot integration: packet and exclusion-filter figures included in stage3.md at user request. Offline verification confirms all 3,705 packets are IPv4 with destination 239.10.10.10; caption limits the conclusion to captured traffic. Originals preserved at the figure/diagnostic paths in the evidence inventory. This does not supply a join transition.
