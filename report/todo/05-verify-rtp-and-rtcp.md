# 05 — Explain RTP headers and observed RTCP

Status: complete
Priority: baseline requirement — p. 18 U1 evidence and p. 19 Q4; RTCP analysis is conditional on observation.
Purpose: interpret identified packet fields and control traffic without unsupported quality claims.
Prerequisites: task 00 PCAPs and SDP; task 02 flow identity for scenario-labelled examples.

Assistant: verify payload type, sequence number, timestamp and SSRC in representative capture packets; record packet numbers and endpoints. Existing SDP maps PT 96 to H264/90000. Explain ordering, media-clock timing and source identity; distinguish these from guaranteed delivery or measured loss/jitter.

Inspect UDP control traffic in the existing captures. The preliminary audit reports RTCP type 200 sender reports in all three scenarios, so RTCP is not currently missing evidence: verify decoding and explain actual observed sender-report fields and role. Do not assert receiver reports or quality feedback unless observed; capture filters may limit visibility.

Evidence: task 00 exact PCAP/SDP paths and `evidence/stage3/figures/PL36_E3_rtp_header_fields.png` (currently lacks scenario identity). Expected concise source/packet-linked notes `evidence/stage3/measurements/PL36_E3_protocol_notes.md`; reuse task 02 flow table. No mandatory replacement screenshot or new RTCP capture.

User: only provide additional existing context if packet identity cannot be resolved. Detailed jitter/loss diagnostics are optional unless needed to correct a material claim.

Completion: required RTP fields and verified RTCP observations explained in `stage3.md`, replacing obsolete statements that SDP is unavailable or RTCP is known only from screenshots.

Verified and integrated on 2026-10-10: see `stage3.md`, `evidence/stage3/measurements/PL36_E3_verified_analysis.json`, `PL36_E3_throughput.csv` and `PL36_E3_throughput_method.md` in that measurements directory. These verified outputs supersede preliminary findings and proposed output formats above. Task 04 closes the three-scenario comparison; the missing receiver-join comparison remains exclusively in task 03.

Screenshot integration: `evidence/stage3/figures/PL36_E3_rtp_header_fields.png` matched to multicast frame 9 (sequence 32067, timestamp 977116636, SSRC 0x3717b922) and included in stage3.md. Earlier scenario-identity uncertainty is resolved.

Figure update: two operator-supplied screenshots from report root were renamed to `PL36_E3_U2_two_RTP_destinations.png` and `PL36_E3_multicast_RTP_packet.png` under `evidence/stage3/figures/`. They replace the old UDP-only and cropped RTP screenshots as report figures. Original earlier files remain for traceability; see evidence inventory.
