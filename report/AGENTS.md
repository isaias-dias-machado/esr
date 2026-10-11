# Stage 3 report workflow

Read the assignment at ./ESR-TP1-26-27-EN.pdf.
Help structure Stage 3 results in ./stage3.md.

The professor expects concise evidence, results, and observations
demonstrating critical thinking—not a step-by-step experiment narrative.

## Responsibilities and existing evidence

- The user performs the experimental tasks. The assistant must not run
  experiments, start or configure emulation scenarios, change network settings,
  generate traffic, or collect new captures or measurements itself.
- The assistant's role is to identify required work, maintain task files, inspect
  and analyze existing evidence, verify supported conclusions, and edit the
  report. It may use tools for these analysis and documentation activities.
- When required evidence is missing, describe what the user needs to collect
  and wait for the user's results before completing the dependent analysis.
  A request for procedural help authorizes guidance, not experimental execution.
- Result files may be scattered across the report directory and these locations,
  relative to this directory:
  - `../xubuncore/Dockerized-Coreemu-Template/volume/TP1/results`
  - `../xubuncore/Dockerized-Coreemu-Template/results`
- Inspect these locations and any paths supplied in `user_task.md` before
  requesting new evidence. Preserve original result files and record the source
  paths of evidence used in task files so results remain traceable.

## Priority: completeness before refinement

- First establish a completeness baseline against the assignment's explicit
  Stage 3 requirements. Map each requirement to its assignment page or question,
  existing evidence, report coverage, and any remaining essential work in ./todo/.
- Prioritize unmet explicit requirements and the prerequisites needed to satisfy
  them. Use the minimum sufficient evidence and analysis to answer each required
  question; reuse existing material before asking for new experiments.
- The baseline is complete when every explicit Stage 3 requirement has a
  supported answer integrated into stage3.md and the essential evidence has been
  verified. An acknowledged gap remains incomplete; expected behaviour does not
  count as evidence.
- Until that baseline is complete, defer presentation polish, additional
  screenshots, repeated measurements, optional diagnostics, and deeper analysis
  unless they are necessary to satisfy an explicit requirement or correct a
  material error. Required analysis and critical observations are baseline work.
- Keep deferred improvements in ./todo/, clearly labelled as optional refinement
  with the baseline as a prerequisite. Do not let them block baseline tasks or
  turn chosen methods into assignment requirements.
- After the baseline is complete, revisit refinements in order of their value to
  the report. Do not expand experiments merely because more evidence is possible.

## Separate report content from working tasks

- Keep stage3.md focused on evidence, measured results, and analysis.
- Maintain outstanding work in ./todo/, one Markdown file per task.
- Keep concise work descriptions and missing-evidence checklists in todo/.
- Flag missing evidence covering unicast, multicast, RTP, and RTCP where required.
  Request a specific screenshot, terminal output, or measurement only when the
  assignment requires it or existing evidence cannot support a required answer.
- Never present expected behaviour as an observed result.

## Task file structure

Use numbered descriptive filenames, e.g. 02-capture-u1-unicast.md.

Keep each task short and outcome-focused. Include:

1. Status: pending, evidence collected, verified, or complete.
2. Purpose: the question this evidence should answer.
3. Prerequisites, including dependencies on other tasks.
4. A brief description of the work or evidence needed, without a detailed
   step-by-step procedure.
5. The relevant scenario, node, and interface where needed to identify evidence.
6. Expected evidence filenames and measurements, including units.
7. Completion criteria.
8. A brief justification linking the requested evidence to the assignment page,
   question, or report requirement and the conclusion it supports. Distinguish
   explicit requirements from chosen methods, optional diagnostics, and
   conditional evidence. State what the user must collect and what the assistant
   can analyze; reuse existing evidence before requesting additional work.
9. Priority: baseline requirement, necessary prerequisite, or optional refinement.
   For baseline work, identify the explicit requirement it closes; for a
   prerequisite, explain why the required answer depends on it.

Do not include command walkthroughs, terminal-by-terminal instructions, or
Wireshark menu and button sequences by default. The user will ask for procedural
help when needed. Include a specific command, filter, or setting only when it is
essential to define the evidence to collect. If detailed help is requested,
clearly distinguish commands to run from example output. Do not invent addresses,
settings, or results.

## Collaboration loop

- Choose an actionable baseline task for the user before optional refinements and explain
  which explicit requirement it addresses and the minimum evidence needed.
- The user runs experiments and supplies captures, screenshots, or output, in user_task.md.
- Inspect existing evidence before requesting repeated experiments.
- Verify that evidence is correct, identifiable by scenario, and readable enough
  to support the required answer. Defer cosmetic improvements and replacement
  captures when existing evidence is sufficient.
- Give screenshots descriptive filenames and link relevant ones in stage3.md.
- Add concise observations supported by the evidence.
- Mark tasks complete only after verification and report integration.
- Preserve completed task files and original captures for traceability.

## Essential evidence checks and later refinement

Apply these checks where needed to support a required claim. Before the baseline
is complete, missing metadata or imperfect presentation warrants additional work
only when it prevents a required answer from being supported or interpreted.

- Record actual network settings, capture location, duration, and filters.
- Compare throughput using consistent intervals, units, and byte accounting.
- Separate evidence collected from conclusions verified.
- Explain limitations when a screenshot cannot establish a claim.
- Retain only useful figures in the report; keep supporting diagnostics
  accessible through task files.
