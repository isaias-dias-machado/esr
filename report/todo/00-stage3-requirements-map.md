# Stage 3 completeness baseline

Status: pending
Mapping/inventory subtask: complete. This file remains the overall Stage 3 completion tracker; creating the map does not complete the report baseline.
Priority: baseline requirement — coverage of Stage 3, assignment pp. 16–19.
Purpose: track supported answers, not merely collected files.
Prerequisites: none; assessed against existing files on 2026-10-10.

| Explicit requirement | Assignment | Existing evidence | Current stage3.md coverage | Essential task |
| --- | --- | --- | --- | --- |
| Experimental conditions, capture on shared link, approximate video bitrate | p. 16 preparation 4–5; p. 19 §7 | PCAPs, source video and SDP verified for task 01; user confirms interface and unchanged 10 Mbit/s, 10 ms, 0% loss settings | Run context and video bitrate integrated | 01 complete |
| U1 RTP capture: endpoints and relevant header fields | pp. 16–18 U1 and evidence list | U1 PCAP and preliminary audit | Verified endpoints and frame-1 header integrated | 02, 05 complete |
| U2: two independent streams, same content/rate, aggregate traffic | pp. 17–18 U2; p. 18 Q1–2 | U2 PCAP, pc3/pc4 SDP, preliminary audit | Two flows and measured aggregate rate integrated | 01, 02, 04 complete |
| Multicast group and one stream across shared link, two simultaneous receivers | pp. 17–18 multicast steps 1–7 and evidence list | Multicast PCAP/SDP; packet screenshot | One RTP flow verified; simultaneous reception operator-confirmed | 01, 03 |
| Compare observed U1/U2/multicast link throughput with N × R | p. 18 evidence list and Q1 | Preliminary full-capture rates, unequal durations | Equal 30 s interval table and ratios integrated | 04 complete |
| Second receiver's effect; location of replication | p. 19 Q3 | User confirms both playing before capture; no join transition captured | Simultaneous playback attributed to user; join effect unmeasured | 03, 04 |
| Explain PT, sequence, timestamp, SSRC; RTCP role if observed | p. 19 Q4 | PCAPs/SDP; preliminary audit reports RTCP sender reports | Identifiable RTP fields, SDP mapping and RTCP sender reports integrated | 05 complete |
| Measured-R estimates for 10, 100, 1000 clients | p. 18 scalability instructions | U1 PCAP | Measured-R numerical estimates integrated | 06 complete |
| Global multicast limitations and application-layer distribution | p. 19 Q5 | Conceptual answer already present | Concise deployment explanation integrated | 06 complete |
| Concise settings, execution evidence, results and interpretation | pp. 19–20 §7, §7.2 | Existing report/figures/data | Concise supported report generated; Q3 join comparison remains incomplete | 07 |

## Evidence and source paths

Paths below are relative to the report directory. Task references to U1, U2 and multicast use these exact files:

- `evidence/stage3/captures/PL36_E3_U1_PC3.pcap`
- `evidence/stage3/captures/PL36_E3_U2_PC3_PC4.pcap`
- `evidence/stage3/captures/PL36_E3_multicast_PC3_PC4.pcap`
- Original source for all three: `../xubuncore/Dockerized-Coreemu-Template/volume/TP1/results/`, same basenames. SHA-256 matches verified on 2026-10-10; hashes are in `evidence/stage3/logs/PL36_E3_capture_inventory.txt`.
- `evidence/stage3/logs/{pc3,pc4,multicast}.sdp`; candidate originals at `../xubuncore/Dockerized-Coreemu-Template/volume/TP1/rtp/` (same basenames).
- `evidence/stage3/logs/PL36_E3_video_probe.json` and `evidence/stage3/measurements/PL36_E3_initial_audit.json` are existing supporting data; verify provenance/analysis before treating conclusions as final.
- Existing figures and diagnostic: `evidence/stage3/figures/` and `evidence/stage3/diagnostics/`.
- Also inspected `../xubuncore/Dockerized-Coreemu-Template/results/`: only HTTP/Stage 1 named captures. The volume results directory also contains `5.2.6.pcapng`, `5.3.3.pcapng` and a DASH log; these are not identified as Stage 3 evidence.
- `user_task.md` now confirms the intended interface, unchanged content/settings, and simultaneous playback before capture. See `evidence/stage3/logs/PL36_E3_run_context.md` for verified metadata and remaining uncertainties.

## Work order and completion

Tasks 01, 02, 04, 05 and 06 are complete and integrated into stage3.md (2026-10-10). Task 03 remains pending: user supplies identifiable multicast traffic before/after PC4 joins the ongoing stream to answer p. 19 Q3. Existing capture starts after both receivers joined. Task 07 and overall baseline remain pending solely on that essential gap.

Expected output: verified evidence references, rates in Mbit/s, intervals in s, and supported answers integrated into `stage3.md`. Complete this baseline only when every row above is satisfied; an acknowledged gap remains incomplete. Preserve original data and completed task files. Task 90 is deferred optional refinement.
