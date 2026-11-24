#!/usr/bin/env bash
#
# demo.sh — paced, all-ages tour of the transport work (agent/transport) using
# only scripts/localhost-demo.sh to change state. Three ways to watch:
#
#   scripts/demo.sh [act ...]              single stream (default): the acts,
#                                         after each stage the demo prints the
#                                         interesting new log lines of every
#                                         watched node, curated, in one stream.
#   scripts/demo.sh --panes kitty [...]   splits in your kitty window: one
#                                         kitty pane per watched node log
#                                         (kitten @ remote control; needs
#                                         allow_remote_control yes in kitty.conf).
#   scripts/demo.sh --panes tmux [...]     the same via a tmux session (kept
#                                         from the first version; run from a
#                                         plain terminal, not inside tmux).
#
# Usage: scripts/demo.sh [--panes kitty|tmux] [act ...]
#
#   no args          run acts 1-7 in order
#   act numbers      run only those acts, in demo order, e.g.: demo.sh 2 3 6
#   -h, --help       this help and exit
#
# Pacing and controls in every mode: the demo narrates, runs the commands and
# waits for you. [Enter] advances, [r] repeats the current act from its
# beginning (every act starts with its own `down`, so a repeat is a clean
# cold restart), [q] tears everything down.
#
# Acts (number, name, the interaction to watch):
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
# Requirements: localhost-demo.sh builds are cached under /tmp/ott-cp.txt as
# usual; each act restarts the scenario it needs (movies are 500 frames,
# ~50 s at 10 fps); `--panes kitty` needs kitty and remote control, and to be
# run from inside a kitty window; `--panes tmux` needs tmux from a terminal
# outside tmux. Logs stay readable afterwards in /tmp/ott-localhost/<n>/.
#
# Internal design notes (not printed by --help):
#   - three presentation modes share the acts; `watch` registers watched
#     nodes per mode and `show` prints evidence (stream mode only):
#     frame lines are collapsed to a count plus the latest 3, control-plane
#     lines (flow-table, neighbour up/down, GOODBYE) are shown in full;
#   - stream mode advances the per-node log offset after each show, so every
#     excerpt contains only what happened since the previous beat;
#   - the pane backends each keep a list of their own tail panes and rebuild
#     it per act; kitty panes autosplit next to the previous log pane (a
#     right-hand column of logs next to the driver window).

set -euo pipefail

SELF="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/$(basename -- "${BASH_SOURCE[0]}")"
OTT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO="$OTT_DIR/scripts/localhost-demo.sh"
SESSION=ott-demo
DEMO_RUN_DIR="${RUN_ROOT:-/tmp/ott-localhost}"
ACTS=(baseline join leave twoservers serverfail overlay wrapup)
MODE=stream          # stream | kitty | tmux
MOVIE_SECONDS=50     # 500 frames at 10 fps; mentioned in the pause footer
REPEAT_ACT=0         # set by pause() when the user asks to rerun the act
WATCH_PHRASE=''

usage() {  # usage — print this file's header help block (lines 2-51)
    sed -n '2,51p' "$SELF" | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------
# Output helpers
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

run() {  # run <command...> — print the command, then run it
    local display="$*"
    display="${display//$OTT_DIR\//}"  # shorten repo paths to their relative form
    printf '\033[1m$\033[0m %s\n' "$display"
    "$@"
}

run_silent() {  # run_silent <command...> — run without echoing (teardown paths)
    "$@" >/dev/null 2>&1 || true
}

spin() {  # spin <seconds> <reason> — visible pause so logs fill first
    printf '\033[1m$\033[0m sleep %s   %s\033[0m\n' "$1" "$2"
    sleep "$1"
}

pause() {  # pause <narration> — Enter advances, r repeats the act, q quits
    printf '\n\033[1m%s\033[0m\n' "$WATCH_PHRASE $1"
    printf '\033[2m(500-frame movies stream ~%ss per flow; a quiet log means the flow ended or died.)\033[0m\n' "$MOVIE_SECONDS"
    local answer
    while :; do
        printf '\n\033[1m[Enter]\033[0m next act   \033[1m[r]\033[0m repeat this act   \033[1m[q]\033[0m quit the demo: '
        IFS= read -r answer || answer=q
        case "$answer" in
            q|Q) quit_demo ;;
            r|R) REPEAT_ACT=1; return 0 ;;
            *) REPEAT_ACT=0; return 0 ;;
        esac
    done
}

# ---------------------------------------------------------------------
# Watched nodes: registration (watch) and evidence (show) per mode
# ---------------------------------------------------------------------

WATCH_IDS=()
WATCH_HINTS=()
WATCH_FILES=()
WATCH_OFFSETS=()
LOG_PANES=()

# watch <scenario> <id|hint>... — declare this act's watched nodes. Panes
# modes open tail panes; stream mode records each log's byte offset so the
# upcoming show() can print exactly what arrives from here on.
watch() {
    local scenario=$1; shift
    local spec id hint file
    WATCH_IDS=(); WATCH_HINTS=(); WATCH_FILES=(); WATCH_OFFSETS=()
    case "$MODE" in
        stream)
            for spec in "$@"; do
                id=${spec%%|*}; hint=${spec#*|}
                file="$DEMO_RUN_DIR/$scenario/node-$id.log"
                WATCH_IDS+=("$id"); WATCH_HINTS+=("$hint"); WATCH_FILES+=("$file")
                WATCH_OFFSETS+=("$(stat -c %s "$file" 2>/dev/null || echo 0)")
            done
            ;;
        kitty) kitty_sync "$scenario" "$@" ;;
        tmux)  sync_panes "$scenario" "$@" ;;
    esac
}

# show — print the new log lines of the watched nodes (stream mode; the pane
# modes are live and their show is a no-op). Frame lines (10/s) are collapsed
# to a count plus the latest three; every control-plane line is kept (capped
# at the 12 most recent). The offsets advance, so each beat shows only what
# arrived since the previous beat.
show() {
    [ "$MODE" = stream ] || return 0
    local index id hint file offset data others frame_count frame_lines
    for index in "${!WATCH_IDS[@]}"; do
        id=${WATCH_IDS[$index]}; hint=${WATCH_HINTS[$index]}
        file=${WATCH_FILES[$index]}; offset=${WATCH_OFFSETS[$index]}
        data=$(tail -c +"$((offset + 1))" -- "$file" 2>/dev/null || true)
        printf '\033[2m─────────────────────\033[0m\n'
        printf '\033[1mnode %s (%s):\033[0m\n' "$id" "$hint"
        if [ -z "$data" ]; then
            printf '    (no new output)\n'
            continue
        fi
        others=$(grep -v '^frame ' <<<"$data" | tail -n 12 || true)
        if [ -n "$others" ]; then
            printf '%s\n' "$others" | sed 's/^/    /'
        fi
        frame_lines=$(grep '^frame ' <<<"$data" | tail -n 3 || true)
        if [ -n "$frame_lines" ]; then
            frame_count=$(grep -c '^frame ' <<<"$data" || true)
            printf '\033[2m    (%s frame lines, latest:)\033[0m\n' "${frame_count:-0}"
            printf '%s\n' "$frame_lines" | sed 's/^/    /'
        fi
        WATCH_OFFSETS[$index]=$(stat -c %s "$file" 2>/dev/null || echo 0)
    done
}

# ---------------------------------------------------------------------
# Pane backends
# ---------------------------------------------------------------------

kitty_preflight() {  # kitty_preflight — remote control must answer; explain if not
    command -v kitten >/dev/null 2>&1 || {
        echo "ERROR: --panes kitty needs the kitten binary (kitty >= 0.27)" >&2
        exit 1
    }
    if ! kitten @ ls >/dev/null 2>&1; then
        echo "ERROR: --panes kitty cannot reach kitty's remote control from this terminal." >&2
        echo "       - run it inside a kitty window (not over ssh without a socket, not inside tmux)" >&2
        echo "       - kitty.conf needs: allow_remote_control yes" >&2
        echo "       fallback: scripts/demo.sh              (single stream, no panes)" >&2
        exit 1
    fi
    if [ -z "${KITTY_WINDOW_ID:-}" ]; then
        echo "ERROR: KITTY_WINDOW_ID is unset — the driver itself must run in a kitty window" >&2
        exit 1
    fi
}

# kitty_sync <scenario> <id|hint>... — one kitty pane per watched log,
# autosplit next to the previous one (driver window first: a right-hand
# column of logs). Titles carry the node id and what to watch there; ids are
# kept for closing.
kitty_sync() {
    local scenario=$1; shift
    local pane spec node hint logfile next_to
    for pane in "${LOG_PANES[@]:-}"; do
        if [ -n "$pane" ]; then
            run_silent kitten @ close-window --match "id:$pane"
        fi
    done
    LOG_PANES=()
    next_to=$KITTY_WINDOW_ID
    for spec in "$@"; do
        node=${spec%%|*}; hint=${spec#*|}
        logfile="$DEMO_RUN_DIR/$scenario/node-$node.log"
        pane=$(kitten @ launch --type=window --location=split --next-to "id:$next_to" \
            --keep-focus --window-title "node $node ($hint)" \
            -- tail -n 0 -F -- "$logfile" 2>/dev/null | tail -n 1 || true)
        if ! printf '%s' "$pane" | grep -qE '^[0-9]+$'; then
            say "could not create a kitty pane for node $node — the window may be too small; its log: $logfile"
            continue
        fi
        LOG_PANES+=("$pane")
        next_to=$pane
    done
    run_silent kitten @ focus-window --match "id:$KITTY_WINDOW_ID"
}

DRIVER_PANE=''

# sync_panes <scenario> <id|hint>... — tmux backend: (re)build the log panes
# for this act. Each pane runs `tail -n 0 -F` on the node's log, so a pane
# shows only lines that arrive after the pane itself exists. The layout is
# re-tiled before every split (a split never runs out of room) and pane ids
# are checked (an empty tmux target means "the active pane", once fatal).
sync_panes() {
    local scenario=$1; shift
    local pane spec node hint logfile
    tmux select-pane -t "$DRIVER_PANE" >/dev/null
    for pane in "${LOG_PANES[@]:-}"; do
        if [ -n "$pane" ]; then
            run_silent tmux kill-pane -t "$pane"
        fi
    done
    LOG_PANES=()
    for spec in "$@"; do
        node=${spec%%|*}; hint=${spec#*|}
        logfile="$DEMO_RUN_DIR/$scenario/node-$node.log"
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

# ---------------------------------------------------------------------
# Teardown
# ---------------------------------------------------------------------

quit_demo() {  # quit_demo — all scenarios down (sweep orphans), panes closed
    say "Tearing down: scenarios down (logs kept in $DEMO_RUN_DIR)."
    run_silent "$DEMO" down 1
    run_silent "$DEMO" down 2
    run_silent "$DEMO" down 3
    local pane
    for pane in "${LOG_PANES[@]:-}"; do
        if [ -n "$pane" ]; then
            if [ "$MODE" = kitty ]; then
                run_silent kitten @ close-window --match "id:$pane"
            elif [ "$MODE" = tmux ]; then
                run_silent tmux kill-pane -t "$pane"
            fi
        fi
    done
    if [ "$MODE" = tmux ]; then
        run_silent tmux kill-session -t "$SESSION"
    fi
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
    watch 1 \
        "0|server: flow-table + WANT_FLOW" \
        "2|client: ANNOUNCE then frames" \
        "1|relay: overlay node, dials its neighbours"
    run "$DEMO" up 1 3
    spin 2 "let the control exchange and first frames happen"
    hint "Server: 'owns flows ... waiting for WANT_FLOW'. Relay 1 (overlay node) listens on 127.0.0.2:5000 and redials neighbours that do not answer — that retry is what lets a late node rejoin."
    show
    pause "One flow end to end: client 2 asks, the server's entry gains Destination [1], frames tick at 10/s on the two interleaved flows."
}

act_join() {
    banner "ACT 2/7 — dynamic join: client 3 arrives mid-stream"
    say "Cold restart, client 3 held back (matrix case 4)."
    run "$DEMO" down 1
    watch 1 \
        "3|client: joins mid-stream" \
        "0|server: one source, destinations grow" \
        "2|client: keeps streaming"
    run "$DEMO" up 1 3
    spin 4 "let client 2 reach a mid-video sequence number"
    run "$DEMO" start 1 3
    spin 2 "frames reach client 3 already mid-numbering"
    hint "Client 3's FIRST frame has a three-digit sequence number (attached to a running stream, sink tolerates the gap). Server: no new 'started source' — one source per flow; only the Destinations grow. On the control plane the server numbers accepted clients 1, 2, ..., so client 3 appears as node 2."
    show
    pause "Client 3 joined a live stream without restarting it."
}

act_leave() {
    banner "ACT 3/7 — abrupt leave: kill -9 a client mid-stream (matrix case 5)"
    run "$DEMO" down 1
    watch 1 \
        "0|server: sees EOF, drops destination" \
        "2|client: unaffected, keeps streaming"
    run "$DEMO" up 1
    spin 3 "steady frames on both clients first"
    run "$DEMO" kill 1 3
    spin 2 "the server's TCP reader notices the EOF"
    hint "kill -9 runs no code in the client: the server's reader sees EOF -> onNeighborDown -> the flow-table loses one destination. Client 2's frames never stop."
    show
    pause "The server felt the death through the transport and pruned its flow entry."
}

act_twoservers() {
    banner "ACT 4/7 — two servers, two flows each (scenario 3, matrix case 8)"
    say "11 nodes: servers 1 and 6, five clients, four relays — each relay a real overlay node."
    run "$DEMO" roles 3
    run "$DEMO" down 3
    watch 3 \
        "1|server 1: movie + movie2" \
        "6|server 6: movie3 + movie4" \
        "4|client of server 1" \
        "8|client of server 6" \
        "5|relay: overlay mesh forms"
    run "$DEMO" up 3
    spin 3 "clients dial in, meshes handshake"
    hint "Four independent flow entries. Relay 5's log: 'listening on ...:5002', then 'neighbour 3 up' and 'neighbour 7 up' — relays 2, 3, 5, 7 handshook with each other; the 'not reachable yet, retrying' lines aim at server/client roles on purpose (no overlay listener there)."
    show
    pause "Two control ports, two data planes, four flows, one mesh — all at once."
}

act_serverfail() {
    banner "ACT 5/7 — server failure: frozen flow, isolation, manual recovery"
    run "$DEMO" down 3
    watch 3 \
        "4|client of server 1: link dies" \
        "8|client of server 6: never notices" \
        "1|server 1: about to be killed"
    run "$DEMO" up 3
    spin 4 "frames on both sides first"
    run "$DEMO" kill 3 1
    spin 2 "clients detect the dead control link"
    hint "Client 4: 'control link to node 0 down' (the SIGKILL feels like EOF), then the log goes quiet — the flow froze. Client 8 keeps streaming: separate server, separate entry. Recovery today is restart, not healing."
    show
    pause "One side froze and logged the transport failure; the other side streamed on untouched."
    run "$DEMO" kill 3 4
    run "$DEMO" kill 3 9
    run "$DEMO" kill 3 11
    run "$DEMO" start 3 1
    run "$DEMO" start 3 4
    run "$DEMO" start 3 9
    run "$DEMO" start 3 11
    spin 3 "recovered clients reattach with fresh flows"
    hint "The recovered clients' sequence numbers restart from 0; client 8's original stream kept running the whole time."
    show
    pause "Restarted nodes rejoined and streamed again — redial and re-request did the work."
}

act_overlay() {
    banner "ACT 6/7 — overlay churn: GOODBYE vs kill -9, and rejoin by redial"
    say "Relays 2, 3, 5, 7 hold a live mesh (act 5 left it running; `up` is idempotent anyway)."
    run "$DEMO" up 3
    watch 3 \
        "3|relay 3"  \
        "7|relay 7" \
        "2|relay 2 (to be stopped)"
    say "Beat 1 — graceful stop of relay 2: SIGTERM runs its shutdown hook, GOODBYE reaches the neighbours."
    run "$DEMO" stop 3 2
    spin 2 "GOODBYEs arrive on relays 3 and 7"
    show
    hint "On relays 3 and 7: 'neighbour 2 left: GOODBYE received'."
    pause "A graceful leave announces itself across the mesh."
    say "Beat 2 — relay 2 returns: redial alone re-establishes the links."
    run "$DEMO" start 3 2
    spin 2 "'neighbour 2 up' on 3 and 7 again"
    show
    hint "Reconnect needed no manual steps — that is the dial loop doing dynamic join."
    pause "Dynamic rejoin: relay 2 is back in the mesh."
    say "Beat 3 — kill -9 of relay 5 instead: SIGKILL runs no code, neighbours learn by EOF."
    run "$DEMO" kill 3 5
    spin 2 "neighbours see the connection close"
    hint "On relay 3 (which dials 5): 'neighbour 5 lost: connection closed by the peer' — contrast with the GOODBYE line above."
    show
    run "$DEMO" start 3 5
    spin 2 "relay 5 redials 7; relay 3 redials 5"
    hint "The mesh heals itself again: 'neighbour 5 up' both ways."
    show
    pause "The two leave signatures side by side: 'left: GOODBYE received' vs 'lost: connection closed by the peer'."
}

act_wrapup() {
    banner "ACT 7/7 — wrap-up"
    say "Final state of scenario 3 on the record:"
    run "$DEMO" status 3
    say "What the demo covered: request/pull streaming, flow-table updates, dynamic join, abrupt leave, server failure with isolation and manual recovery, four flows at once, and the overlay mesh announcing deaths (GOODBYE) and detecting them (EOF)."
    hint "Not visible on localhost (all nodes share 127.0.0.1): severed links and metric-driven reroutes — those are the CORE-emulator runs; the same commands work there."
    printf '\n\033[1m[Enter]\033[0m tears everything down (scenarios, panes). '
    local answer
    IFS= read -r answer || answer=''
    quit_demo
}

# ---------------------------------------------------------------------
# Driver and entry points
# ---------------------------------------------------------------------

driver() {  # driver <act numbers...> — narration + commands, runs until quit
    WATCH_PHRASE="Watch the log panes."
    case "$MODE" in
        stream) WATCH_PHRASE="Read the excerpts above — each block is one node's own log." ;;
        kitty)   kitty_preflight
                 WATCH_PHRASE="Watch the kitty panes next to this window." ;;
        tmux)    DRIVER_PANE=${TMUX_PANE:?}
                 tmux set-option -t "$SESSION" -w pane-border-status top >/dev/null
                 tmux set-option -t "$SESSION" -w pane-border-format ' #{pane_title} ' >/dev/null
                 tmux select-pane -t "$DRIVER_PANE" -T "OTT transport demo — driver (Enter = next, r = repeat act, q = quit)"
                 ;;
    esac
    banner "OTT transport demo — the agent/transport work, live"
    say "Every command below is scripts/localhost-demo.sh doing the real thing; what you see per node is that node's own log."
    local index last_act answer
    for index in "$@"; do
        # r at a pause re-runs the act: every act opens with its own `down`,
        # so a repeat is a clean cold restart of the same stage.
        while :; do
            REPEAT_ACT=0
            "act_${ACTS[index - 1]}"
            [ "$REPEAT_ACT" -eq 1 ] || break
            say "Repeating act $index — ${ACTS[index - 1]} (cold restart)."
        done
    done
    last_act=${@: -1}
    if [ "$last_act" != 7 ]; then
        say "Selected acts finished — the scenarios still run in the background (logs kept in $DEMO_RUN_DIR)."
        printf '\n\033[1m[Enter]\033[0m tears everything down (scenarios, panes). '
        IFS= read -r answer || answer=''
    fi
    quit_demo
}

# wrapper_tmux <act numbers...> — build the tmux session and attach to it.
wrapper_tmux() {
    command -v tmux >/dev/null || { echo "tmux not found — install it, or use the default mode" >&2; exit 1; }
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
        "$(printf '%q ' bash "$SELF" --drive --panes tmux "$@")"
    printf 'starting session %s — detach anytime with Ctrl-b d, quit the demo with q\n' "$SESSION"
    exec tmux attach-session -t "$SESSION"
}

main() {  # main <args...> — flag parsing, act selection, mode dispatch
    local arg selected act_numbers=() drive=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
            -h|--help|help) usage; exit 0 ;;
            --panes)
                [ "$#" -ge 2 ] || { usage; exit 2; }
                MODE=$2; shift 2 ;;
            --panes=*) MODE=${1#*=}; shift ;;
            --drive) drive=1; shift ;;
            [1-7]) act_numbers+=("$1"); shift ;;
            *) echo "act must be 1-7, got: $1" >&2; usage; exit 2 ;;
        esac
    done
    case "$MODE" in
        stream|kitty|tmux) ;;
        *) echo "--panes must be kitty or tmux (or absent for the single-stream mode)" >&2; exit 2 ;;
    esac
    if [ "${#act_numbers[@]}" -eq 0 ]; then
        act_numbers=(1 2 3 4 5 6 7)
    fi
    local ordered_numbers=()
    for selected in 1 2 3 4 5 6 7; do
        for arg in "${act_numbers[@]}"; do
            if [ "$arg" = "$selected" ]; then
                ordered_numbers+=("$selected")
            fi
        done
    done
    if [ "$drive" -eq 1 ]; then
        driver "${ordered_numbers[@]}"        # inside a tmux pane (MODE=tmux)
    elif [ "$MODE" = tmux ]; then
        wrapper_tmux "${ordered_numbers[@]}"
    else
        driver "${ordered_numbers[@]}"        # stream and kitty run in place
    fi
}

main "$@"
