# 04 — Compare measured link throughput

Status: complete
Priority: baseline requirement — p. 18 throughput table and Q1; p. 19 Q3 rate comparison.
Purpose: quantify U1, U2 and multicast traffic using consistent accounting and assess duplication.
Prerequisites: task 01 for link attribution/conditions; tasks 02–03 for scenario and receiver interpretation. Existing-data calculations can proceed independently.

Assistant: analyze original captures from task 00. Existing `PL36_E3_initial_audit.json` gives preliminary whole-capture rates of 1.0729, 2.1315 and 1.0716 Mbit/s across unequal durations of 65.475, 93.007 and 36.756 s. These are useful leads, not the final comparable table.

Use representative, comparable intervals available in each capture (for example 30 s, if supported). Record start/end (s), exact traffic selection, bytes and byte basis, RTP/RTCP inclusion and formula `8 × bytes / seconds / 10^6`. Captured Ethernet frame bytes exclude uncaptured physical-link overhead. Explain any remaining content-phase or interval limitation; do not repeat measurements merely to equalize capture duration.

Expected derived evidence: `evidence/stage3/measurements/PL36_E3_throughput.csv` and a short method record `PL36_E3_throughput_method.md` in the same directory, with reproducible source references. Compute U2/U1 and multicast/U1 ratios; use task 03 intervals for receiver-join comparison if available.

User: no new throughput experiment requested unless existing data and task 03 evidence cannot answer the required comparison.

Completion: measured table in Mbit/s and critical interpretation of scaling/observed discrepancies integrated into `stage3.md`; distinguish measured shared-link load from server-output estimates.

Verified and integrated on 2026-10-10: see `stage3.md`, `evidence/stage3/measurements/PL36_E3_verified_analysis.json`, `PL36_E3_throughput.csv` and `PL36_E3_throughput_method.md` in that measurements directory. These verified outputs supersede preliminary findings and proposed output formats above. Task 04 closes the three-scenario comparison; the missing receiver-join comparison remains exclusively in task 03.
