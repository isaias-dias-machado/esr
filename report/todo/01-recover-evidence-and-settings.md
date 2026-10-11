# 01 — Establish run context from existing evidence

Status: complete
Priority: necessary prerequisite — shared-link attribution and comparable conditions for p. 18 evidence/Q1 and p. 19 Q3; experimental settings under p. 19 §7.
Purpose: attribute the three captures to the required scenarios and routerA–routerB link.
Prerequisites: task 00 source inventory (established); captures already recovered and hash-matched.

- [x] User confirms the intended capture interface in `user_task.md`: routerA toward routerB (eth1 in the supplied topology).
- [x] User reports unchanged content/settings and both receivers playing before capture; integrated into `stage3.md` as a user observation. Receiver-join analysis belongs to task 03.
- [x] Assistant verifies capture durations and source-video bitrate; local SDP copies match originals after newline normalization. See the run-context record below.
- [x] User confirms configured routerA–routerB settings: **10 Mbit/s, 10 ms one-way delay, 0% loss**, unchanged across scenarios. Recorded in `user_task.md`, run context and `stage3.md`.

User: no further evidence required for task 01. Exact capture filters remain uncertain. Assignment filters differ: unicast `udp and host $STREAMER_IP`; multicast `udp and dst host 239.10.10.10`. These are prescribed filters, not independently verified run metadata.

Assistant: retain uncertainty where records cannot recover metadata; assess whether it prevents a required answer before requesting new evidence. Do not infer actual settings from assignment defaults. Link-rate comparison is task 04, not a new experiment for task 01.

Evidence: task 00 source paths, `user_task.md`, and `evidence/stage3/logs/PL36_E3_run_context.md` (chosen documentation format). Verified durations: U1 65.474684 s, U2 93.006928 s, multicast 36.756238 s. Approximate video bitrate: 1.028 Mbit/s; full-file bitrate includes audio/container overhead and is not RTP link throughput.

Completion: essential provenance/conditions and approximate video bitrate verified and integrated into `stage3.md`; remaining metadata classified by whether it blocks an answer, and unresolved receiver-join evidence routed to task 03. Completed on 2026-10-10. Exact capture-filter recall is nonblocking for run-context closure: subsequent analysis will explicitly select the required flows from the existing PCAPs; it must not infer absence of excluded traffic. Receiver-join analysis remains task 03.
