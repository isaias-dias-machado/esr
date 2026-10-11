# 06 — Complete scalability and deployment analysis

Status: complete
Priority: baseline requirement — p. 18 measured-R estimates for N = 10, 100, 1000; p. 19 Q5.
Purpose: connect measured traffic to distribution cost and realistic deployment limits.
Prerequisites: task 04 verified single-stream R; task 03 replication interpretation.

Assistant: use measured U1 R with its stated byte basis to calculate approximate unicast server output N × R and multicast output R for 10, 100 and 1000 clients (Mbit/s; optionally Gbit/s). Label these as extrapolations, not capacity tests. Explain how shared-link placement and downstream replication differ from server output. Review the existing Q5 discussion of multicast reachability/support and application-layer servers/CDNs.

Evidence/output: task 04 `evidence/stage3/measurements/PL36_E3_throughput.csv`; numerical table and concise discussion in `stage3.md`. No new experiment or large-client emulation is required. User collects nothing for this task.

Completion: numerical estimates traceable to measured R and a clear Q5 answer integrated into the report, with assumptions and practical limits distinguished from observations.

Verified and integrated on 2026-10-10: see `stage3.md`, `evidence/stage3/measurements/PL36_E3_verified_analysis.json`, `PL36_E3_throughput.csv` and `PL36_E3_throughput_method.md` in that measurements directory. These verified outputs supersede preliminary findings and proposed output formats above. Task 04 closes the three-scenario comparison; the missing receiver-join comparison remains exclusively in task 03.
