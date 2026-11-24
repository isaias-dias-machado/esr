#!/usr/bin/env bash
#
# tmux-demo.sh — one-screen guided demo of the agent/transport work: a driver
# pane (narration + scripts/localhost-demo.sh commands, Enter to advance) and
# one pane per interesting node log, so the interactions can be watched as
# they happen instead of being reconstructed from log files afterwards.
#
# Usage: scripts/tmux-demo.sh [act ...]
#
#   no args          run acts 1-7 in order
#   act numbers      run only those acts, in demo order, e.g.: tmux-demo.sh 1 3 6
#   -h, --help       this help and exit
#
# Acts (all state is managed through scripts/localhost-demo.sh — up/down,
# start/stop/kill, status — and its log files under $RUN_ROOT/<n>/):
#   1  baseline          WANT_FLOW over TCP, ANNOUNCE back, flow-table update,
#                        frames over UDP (scenario 1, one client short)
#   2  mid-stream join   client 3 joins while client 2 already streams: high
#                        first sequence number, video does not restart
#   3  abrupt leave      kill -9 client 3: server sees EOF, drops the
#                        destination; client 2 keeps streaming
#   4  two servers       scenario 3: server 1 owns movie+movie2, server 6 owns
#                        movie3+movie4, clients split across them; relays run
#                        real overlay nodes and their mesh forms
#   5  server failure    kill -9 server 1: its clients log the dead control
#                        link and the flow freezes; server 6's clients never
#                        notice; manual recovery = restart server + clients
#   6  overlay churn     graceful stop sends GOODBYE ("left"), kill -9 is
#                        detected as EOF ("lost"); a restarted relay is
#                        redialled and rejoins by itself
#   7  wrap-up           status, everything down
#
# Requirements: tmux; run from a normal terminal, not inside an existing tmux
# session. Terminal of at least ~160x48 recommended. Each act restarts the
# scenario it needs, so movies never run out of frames mid-act (500 frames,
# ~50 s at 10 fps). Detaching (Ctrl-b d) keeps everything running; re-attach
# with `tmux attach -t ott-demo`; press q at a pause to tear everything down.
#
# Internal design notes (not printed by --help):
#   - the driver pane (this script in --drive mode) is the left "main" pane of
#     a main-vertical layout, and one pane is spawned per watched node log;
#   - pane log tails start with `tail -n 0 -F`, so every pane begins at its
#     creation: nothing from earlier acts, stale runs or earlier sessions of
#     the demo can leak into what is on screen;
#   - panes are (re)built once per act via sync_panes, which also re-selects
#     the driver pane, so Enter always goes to the script running the demo;
#   - the demo is honest about the placeholder: on the server's control plane
#     the clients appear with synthetic ids 1, 2, ... in the order they
#     connected — the narration points this out where it matters.

set -euo pipefail

SELF="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/$(basename -- "${BASH_SOURCE[0]}")"
OTT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO="$OTT_DIR/scripts/localhost-demo.sh"
SESSION=ott-demo
DEMO_RUN_DIR="${RUN_ROOT:-/tmp/ott-localhost}"
ACTS=(baseline join leave twoservers serverfail overlay wrapup)

usage() {  # usage — print this file's header help block (lines 2-37)
    sed -n '2,37p' "$SELF" | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------
# Output helpers (driver pane)
# ---------------------------------------------------------------------

banner() {  # banner <text> — big act header
    printf '\033[1;36m============================================================\033[0m\n'
    printf '\033[1;36m%s\033[0m\n' "$1"
    printf '\033[1;36m============================================================\033[0m\n'
}

say() {  # say <text> — narration line
    printf '\033[1m-- %s\033[0m\n' "$1"
}

hint() {  # hint <text> — dimmed explanation line
    printf '\033[2m   %s\033[0m\n' "$1"
}

run() {  # run <command...> — print the command, then run it (driver pane)
    local display="$*"
    display="${display//$OTT_DIR\//}"  # shorten repo paths to their relative form
    printf '\033[1m$\033[0m %s\n' "$display"
    "$@"
}

run_silent() {  # run_silent <command...> — run without echoing (teardown paths)
    "$@" >/dev/null 2>&1 || true
}

spinner() {  # spinner <seconds> <reason> — visible pause so logs fill first
    printf '\033[1m$\033[0m sleep %s   %s\033[0m\n' "$1" "$2"
    sleep "$1"
}

# ---------------------------------------------------------------------
# tmux plumbing
# ---------------------------------------------------------------------

DRIVER_PANE=''
LOG_PANES=()

# set_driver_title <text> — what the demo driver pane's border reads.
set_driver_title() {
    tmux select-pane -t "$DRIVER_PANE" -T "$1"
}

# sync_panes <scenario> <id|hint>... — (re)build the log panes for this act.
# Each "id|hint" spawns `tail -n 0 -F` on the node's log file, so a pane shows
# only lines that arrive after the pane itself exists; unneeded panes of the
# previous act are killed. The layout is re-tiled before every split, so a
# split can never fail for lack of room; a pane id is checked before use,
# because an empty tmux target means "the active pane" (that killed the driver
# pane once). Finishes with the driver pane active and the window laid out
# main-vertical around it.
sync_panes() {
    local scenario=$1; shift
    local pane spec node hint logfile
    tmux select-pane -t "$DRIVER_PANE" >/dev/null
    for pane in "${LOG_PANES[@]:-}"; do
        [ -n "$pane" ] && run_silent tmux kill-pane -t "$pane"
    done
    LOG_PANES=()
    for spec in "$@"; do
        node=${spec%%|*}
        hint=${spec#*|}
        logfile="$DEMO_RUN_DIR/$scenario/node-$node.log"
        # Re-tile first: every pane gets an even share, so the next split of
        # the active pane always has room.
        run_silent tmux select-layout -t "$SESSION" tiled
        pane=$(tmux split-window -d -P -F '#{pane_id}' -t "$SESSION" \
            "exec tail -n 0 -F -- $(printf '%q' "$logfile")" 2>/dev/null || true)
        if [ -z "$pane" ]; then
            say "no room for a pane for node $node — enlarge the terminal; its log still lives at $logfile"
            continue
        fi
        tmux select-pane -t "$pane" -T "node $node ($hint)"
        LOG_PANES+=("$pane")
    done
    tmux select-pane -t "$DRIVER_PANE"
    tmux select-layout -t "$SESSION" main-vertical
}

# pause <narration to read while watching> — block until Enter (or q to quit).
pause() {
    printf '\n\033[1m%s\033[0m\n' "Watch the panes on the right. $1"
    printf '\033[2m(500-frame movies stream ~%ss per flow; a frozen log means the flow ended or died.)\033[0m\n' "$MOVIE_SECONDS"
    local answer
    while :; do
        printf '\n\033[1m[Enter]\033[0m next step   \033[1m[q]\033[0m quit the demo: '
        IFS= read -r answer || answer=q
        case "$answer" in
            q|Q) quit_demo ;;
            *) break ;;
        esac
    done
}

MOVIE_SECONDS=50  # 500 frames at 10 fps; mentioned in the pause footer

# quit_demo — tear everything down: all scenarios down, all panes, session.
quit_demo() {
    say "Tearing down: scenarios down (logs kept in $DEMO_RUN_DIR), tmux session closed."
    run_silent "$DEMO" down 1
    run_silent "$DEMO" down 2
    run_silent "$DEMO" down 3
    run_silent tmux kill-session -t "$SESSION"
    exit 0
}

# ---------------------------------------------------------------------
# The acts. Every state change goes through scripts/localhost-demo.sh.
# ---------------------------------------------------------------------

act_baseline() {
    banner "ACT 1/7 — request/pull streaming: WANT_FLOW over TCP, frames over UDP"
    say "Scenario 1 on 127.0.0.1: one server (node 0), one relay (node 1), two clients."
    run "$DEMO" roles 1
    run "$DEMO" down 1
    sync_panes 1 \
        "0|server: flow-table + WANT_FLOW" \
        "2|client: ANNOUNCE then frames" \
        "1|relay: overlay node, dials its neighbours"
    run "$DEMO" up 1 3
    hint "Server: 'owns flows ... waiting for WANT_FLOW'."
    hint "Client 2: 'control link to node 0 up' -> 'requested flows' -> 'server announces' -> 'frame ...' lines, two flows interleaved."
    hint "Relay 1 is a real overlay node now: it listens on 127.0.0.2:5000 and redials the nodes that do not answer (the server and the clients run ott server/client, which have no overlay port) — that retry loop is what lets a late node rejoin by itself."
    pause "Follow one flow end to end: client 2 asks, the server updates its entry (Origin LOCAL, Destinations [1]) and starts the source, then UDP frames tick at 10/s."
}

act_join() {
    banner "ACT 2/7 — dynamic join: client 3 arrives mid-stream"
    say "Same scenario, cold restart, client 3 held back (matrix case 4)."
    run "$DEMO" down 1
    sync_panes 1 \
        "3|client: joins mid-stream" \
        "0|server: one source, destinations grow" \
        "2|client: keeps streaming"
    run "$DEMO" up 1 3
    spinner 4 "let client 2 reach a mid-video sequence number"
    run "$DEMO" start 1 3
    hint "Client 3's FIRST frame already has a high sequence number: it attached to a running stream and the sink tolerates the gap."
    hint "Server: no 'started source' again — the flow keeps its single source; only the flow-table Destinations grow (remember: on the control plane the server numbers accepted clients 1, 2, ..., so client 3 appears as node 2)."
    pause "Client 3 joined a live stream without restarting it; the flow entry now has two destinations."
}

act_leave() {
    banner "ACT 3/7 — abrupt leave: kill -9 a client mid-stream (matrix case 5)"
    run "$DEMO" down 1
    sync_panes 1 \
        "0|server: sees EOF, drops destination" \
        "2|client: unaffected, keeps streaming"
    run "$DEMO" up 1
    spinner 3 "steady frames on both clients first"
    run "$DEMO" kill 1 3
    hint "kill -9 runs no code in the client, so the server's TCP reader sees EOF -> onNeighborDown -> the flow-table loses one destination."
    hint "Client 2's frames never stop: the other destination is independent."
    pause "Server felt the death through the transport (EOF) and pruned its flow entry; the surviving client streamed on."
}

act_twoservers() {
    banner "ACT 4/7 — two servers, two flows each (scenario 3, matrix case 8)"
    say "11 nodes: servers 1 and 6, five clients, four relays — each relay a real overlay node."
    run "$DEMO" roles 3
    run "$DEMO" down 3
    sync_panes 3 \
        "1|server 1: movie + movie2" \
        "6|server 6: movie3 + movie4" \
        "4|client of server 1" \
        "8|client of server 6" \
        "5|relay: overlay mesh forms"
    run "$DEMO" up 3
    hint "Four independent flow entries: server 1's clients are 4, 9, 11; server 6's are 8, 10 (the attach rule splits them)."
    hint "Relay 5's log: 'listening on ...:5002', then 'neighbour 3 up' and 'neighbour 7 up' — relays 2, 3, 5, 7 handshook with each other."
    hint "The 'not reachable yet, retrying' lines are the redials at the server/client roles: no overlay listener there on purpose."
    pause "Two control ports, two data planes, four flows, one mesh — all at once."
}

act_serverfail() {
    banner "ACT 5/7 — server failure: frozen flow, isolation, manual recovery (matrix case 7)"
    run "$DEMO" down 3
    sync_panes 3 \
        "4|client of server 1: link dies" \
        "8|client of server 6: never notices" \
        "1|server 1: about to be killed"
    run "$DEMO" up 3
    spinner 4 "frames on both sides first"
    run "$DEMO" kill 3 1
    hint "Client 4: 'control link to node 0 down' (the client felt the SIGKILL as EOF), then the log goes quiet — the flow froze."
    hint "Client 8 keeps streaming: separate server, separate entry. Recovery today is restart, not healing: bring the server and its three clients back."
    pause "One side froze and logged the transport failure; the other side streamed on untouched."
    run "$DEMO" kill 3 4
    run "$DEMO" kill 3 9
    run "$DEMO" kill 3 11
    run "$DEMO" start 3 1
    run "$DEMO" start 3 4
    run "$DEMO" start 3 9
    run "$DEMO" start 3 11
    hint "The recovered clients reattach with fresh flows (sequence numbers restart from 0); client 8's original stream kept running the whole time."
    pause "Restarted nodes rejoined and streamed again — redial and re-request did the work."
}

act_overlay() {
    banner "ACT 6/7 — overlay churn: GOODBYE vs kill -9, and rejoin by redial"
    say "Relays 2, 3, 5, 7 hold a live mesh (act 5 left it running; 'up' is idempotent anyway)."
    run "$DEMO" up 3
    sync_panes 3 \
        "3|relay 3"  \
        "7|relay 7" \
        "2|relay 2 (to be stopped)"
    say "Beat 1 — graceful stop of relay 2: SIGTERM runs its shutdown hook, GOODBYE reaches the neighbours."
    run "$DEMO" stop 3 2
    hint "On relays 3 and 7: 'neighbour 2 left: GOODBYE received'."
    pause "A graceful leave announces itself across the mesh."
    say "Beat 2 — relay 2 returns: its own dial loop (and relay 3's) re-establishes the links."
    run "$DEMO" start 3 2
    hint "'neighbour 2 up' on 3 and 7 again — reconnect needed no manual steps."
    pause "Dynamic rejoin: redial brought relay 2 back into the mesh."
    say "Beat 3 — kill -9 of relay 5 instead: SIGKILL runs no code, so the neighbours learn by EOF."
    run "$DEMO" kill 3 5
    hint "On relay 3 (which dials 5): 'neighbour 5 lost: connection closed by the peer' — contrast with the GOODBYE line above."
    run "$DEMO" start 3 5
    hint "Relay 5 redials 7, relay 3 redials 5: the mesh heals itself again."
    pause "The two leave signatures side by side: 'left: GOODBYE received' vs 'lost: connection closed by the peer'."
}

act_wrapup() {
    banner "ACT 7/7 — wrap-up"
    say "Final state of scenario 3 on the record:"
    run "$DEMO" status 3
    say "What the demo covered: request/pull streaming, flow-table updates, dynamic join, abrupt leave, server failure with isolation and manual recovery, four flows at once, and the overlay mesh announcing deaths (GOODBYE) and detecting them (EOF)."
    hint "Not visible on localhost (all nodes share 127.0.0.1): severed links and metric-driven reroutes — those are the CORE-emulator runs; the same commands work there."
    printf '\n\033[1m[Enter]\033[0m tears everything down (scenarios, session, panes). '
    local answer
    IFS= read -r answer || answer=''
    quit_demo
}

# ---------------------------------------------------------------------
# Modes: human wrapper (create+attach) and the pane-side driver
# ---------------------------------------------------------------------

# driver <act numbers...> — runs INSIDE the driver pane: narration + commands.
driver() {
    DRIVER_PANE=${TMUX_PANE:?}
    trap quit_demo INT TERM
    tmux set-option -t "$SESSION" -w pane-border-status top >/dev/null
    tmux set-option -t "$SESSION" -w pane-border-format ' #{pane_title} ' >/dev/null
    set_driver_title "OTT transport demo — driver (Enter = next step, q = quit)"
    banner "OTT transport demo — the agent/transport work, live"
    say "Every command below is scripts/localhost-demo.sh doing the real thing; the panes on the right are the nodes' own logs."
    hint "If the panes look cramped, detach (Ctrl-b d), resize your terminal, and re-attach with: tmux attach -t $SESSION"
    local index last_act answer
    for index in "$@"; do
        "act_${ACTS[index - 1]}"
    done
    last_act=${@: -1}
    if [ "$last_act" != 7 ]; then
        say "Selected acts finished — the scenarios still run in the background (logs kept in $DEMO_RUN_DIR)."
        printf '\n\033[1m[Enter]\033[0m tears everything down (scenarios, session, panes). '
        IFS= read -r answer || answer=''
    fi
    quit_demo
}

# wrapper <act numbers...> — run from a normal terminal: build the session and
# attach to it. A detached session survives terminal or ssh drops.
wrapper() {
    command -v tmux >/dev/null || { echo "tmux not found — install it first" >&2; exit 1; }
    if [ -n "${TMUX:-}" ]; then
        echo "already inside tmux — run this from a normal terminal," >&2
        echo "or attach to an existing demo session with: tmux attach -t $SESSION" >&2
        exit 1
    fi
    if tmux has-session -t "$SESSION" 2>/dev/null; then
        echo "session $SESSION already exists — attaching"
        exec tmux attach-session -t "$SESSION"
    fi
    tmux new-session -d -s "$SESSION" -n demo
    tmux respawn-pane -k -t "$SESSION:demo" \
        "$(printf '%q ' bash "$SELF" --drive "$@")"
    printf 'starting session %s — detach anytime with Ctrl-b d, quit the demo with q\n' "$SESSION"
    exec tmux attach-session -t "$SESSION"
}

# main <args...> — validate act selection, then run driver or wrapper.
main() {
    local arg selected act_numbers=()
    if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ] || [ "${1:-}" = "help" ]; then
        usage
        exit 0
    fi
    if [ "${1:-}" = "--drive" ]; then
        shift
    fi
    if [ "$#" -eq 0 ]; then
        set -- 1 2 3 4 5 6 7
    fi
    for arg in "$@"; do
        case "$arg" in
            1|2|3|4|5|6|7) ;;
            *) echo "act must be 1-7, got: $arg" >&2; usage; exit 2 ;;
        esac
    done
    for selected in 1 2 3 4 5 6 7; do
        for arg in "$@"; do
            [ "$arg" = "$selected" ] && act_numbers+=("$selected")
        done
    done
    if [ -n "${TMUX:-}" ]; then
        driver "${act_numbers[@]}"
    else
        wrapper "${act_numbers[@]}"
    fi
}

main "$@"
