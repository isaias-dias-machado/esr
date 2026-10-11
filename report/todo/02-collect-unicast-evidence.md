# 02 — Verify existing U1 and U2 captures

Status: complete
Priority: baseline requirement — p. 18 U1/U2 evidence bullets and Q2; pp. 16–17 unicast experiment.
Purpose: establish one U1 stream and two independent U2 streams targeting PC3 and PC4.
Prerequisites: task 00 sources; task 01 capture location required for shared-link conclusions. Offline analysis can begin now.

Assistant: inspect the existing U1/U2 PCAPs before requesting anything from the user. Verify flow counts and representative packet numbers, endpoints/ports and SSRC. Preliminary audit reports U1 `10.0.1.10:49250 → 10.0.2.13:5004`, SSRC `0xdda6aa31`; U2 retains that flow and adds `10.0.1.10:40514 → 10.0.2.14:5004`, SSRC `0x38172c9f`. These remain audit findings to verify, not final conclusions.

Evidence: exact PCAP and SDP source paths in task 00. Expected derived output `evidence/stage3/measurements/PL36_E3_streams.csv`: scenario, source packet references, addresses/ports, SSRC, packet count and interval (s). A concise source-linked table is sufficient; screenshots are a chosen presentation method, not an extra experiment requirement.

User: no new unicast capture currently requested. Supply missing scenario identity via task 01; collect replacement evidence only if inspection finds a material gap.

Completion: verified U1/U2 evidence and explanation of stream independence integrated into `stage3.md`; rates handled by task 04 and header interpretation by task 05.

Verified and integrated on 2026-10-10: see `stage3.md`, `evidence/stage3/measurements/PL36_E3_verified_analysis.json`, `PL36_E3_throughput.csv` and `PL36_E3_throughput_method.md` in that measurements directory. These verified outputs supersede preliminary findings and proposed output formats above. Task 04 closes the three-scenario comparison; the missing receiver-join comparison remains exclusively in task 03.
