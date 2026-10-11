# ESR report — PL36

Start with [Stage 3 results](stage3.md). Requirements are in the
[assignment](ESR-TP1-26-27-EN.pdf), pp. 16–19; the working rules are in [AGENTS.md](AGENTS.md).

```text
report/
├── AGENTS.md
├── README.md
├── ESR-TP1-26-27-EN.pdf
├── stage3.md
├── evidence/
│   └── stage3/
│       ├── README.md        # inventory and provenance
│       ├── figures/         # figures used in the report
│       ├── diagnostics/     # supporting screenshots
│       ├── captures/        # original PCAP/PCAPNG files
│       ├── logs/            # settings, terminal output, SDP files
│       └── measurements/    # derived tables and graph data
└── todo/                    # numbered tasks, including completed tasks
```

## Work queue

Start with the [Stage 3 requirement map](todo/00-stage3-requirements-map.md).

1. [Confirm existing run context and settings](todo/01-recover-evidence-and-settings.md).
2. [Verify existing U1/U2 evidence](todo/02-collect-unicast-evidence.md).
3. [Resolve multicast reception and receiver-join evidence](todo/03-collect-multicast-evidence.md).
4. [Compare measured throughput](todo/04-verify-and-integrate-results.md).
5. [Verify RTP and observed RTCP](todo/05-verify-rtp-and-rtcp.md).
6. [Complete scalability answers](todo/06-answer-scalability-questions.md).
7. [Close report completeness baseline](todo/07-close-baseline-and-integrate.md).
8. [Optional refinement after baseline completion](todo/90-optional-refinement.md).

All three captures are available; no repeat unicast experiment is currently
requested. Tasks 01, 02, 04, 05 and 06 are integrated into the report. The remaining
baseline evidence is task 03: multicast shared-link traffic before and after
PC4 joins, with identifiable join timing (assignment p. 19 Q3).

Keep original evidence filenames containing the group, stage, and scenario.
Do not overwrite earlier runs; add a run suffix for new recordings. Record each
capture's node, interface, settings, duration, and filter alongside it in `logs/`.
Store derived data in `measurements/`; link only useful figures from the report.
Preserve original captures and completed task files for traceability.
The experiment resource package (`$TP1_ROOT`) keeps its assignment-defined layout;
this directory only organizes report material.
