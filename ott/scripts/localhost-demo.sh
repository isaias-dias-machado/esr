#!/usr/bin/env bash
#
# localhost-demo.sh — run one OTT scenario (1, 2 or 3) on a single host,
# without CORE, so the M4 test matrix of context/scenarios.md is repeatable
# by any group member.
#
# Usage: scripts/localhost-demo.sh <command> [args]
#
# Commands:
#   up <scenario_number> [node_id ...]       start all nodes except given node ids
#   start <scenario_number> <node_id>        start node <node_id> (mid-stream join)
#   down <scenario_number>                   stop the scenario (idempotent, logs kept)
#   stop <scenario_number> <node_id>         gracefully stop node <node_id>
#                                           (it sends GOODBYE to its neighbours)
#   kill <scenario_number> <node_id>         abruptly kill node <node_id> (failure case:
#                                           SIGKILL, neighbours detect the loss by EOF)
#   status <scenario_number>                 show per-node role and process state
#   roles <scenario_number>                  print the "id role ip_addr" table of the scenario
#   logs <scenario_number> [node_id]         tail node <node_id>'s log, or all
#   -h, --help                               show this help and exit
#
# Environment:
#   RUN_ROOT              run directory (default: /tmp/ott-localhost)
#   CLASSPATH_FILE        dependency classpath cache (default: /tmp/ott-cp.txt)
#
# Notes:
#   <scenario_number>    1, 2 or 3 — the test scenario to act on
#   <node_id>           a node id declared in the scenario YAML (`status` lists them)
#   run one scenario at a time (scenarios share the same port ranges)
#   `down` also sweeps node processes of this checkout that no pid file
#       records (orphans of an interrupted run hold the ports forever —
#       after their movie ends, server nodes wait on a latch and never exit)
#   every node start checks its port first, so a busy port fails with the
#       holder's pid instead of a raw "Address already in use" crash
#   full localhost runbook: context/scenarios.md
#
# Internal design notes (not printed by --help):
#   - node roles come from the scenario YAML itself, via `ott roles`
#     (`id role ip_addr` lines) — there is no role table in this script;
#   - scenario N runs `resorces/cenarioN-localhost.yaml`: node id x owns
#     127.0.0.(x+1)/8 and the scenario overlay port is 5000+N-1;
#   - port allocation (unique on the host): server control port
#     25001 + server index, client listen port 25010 + client index;
#   - every client attaches to the server with index
#     `client index % server count`, so with two servers the clients split
#     across them (case 8 of the test matrix);
#   - server 0 serves movie.Mjpeg + movie2.Mjpeg, every further server
#     serves movie3.Mjpeg + movie4.Mjpeg (distinct flow ids everywhere).
#
# Relay nodes run the real overlay process now: node mode (`ott <id>
# <scenario>`) keeps the neighbour mesh alive with redial and HELLO/GOODBYE
# (agent/transport). Data-plane forwarding (M3) is not wired yet, so relays
# forward no frames — their logs show the control-plane mesh instead.

set -euo pipefail

OTT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSPATH_FILE="${CLASSPATH_FILE:-/tmp/ott-cp.txt}"
RUN_ROOT="${RUN_ROOT:-/tmp/ott-localhost}"
SERVER_FIRST_CONTROL_PORT=25001
CLIENT_FIRST_LISTEN_PORT=25010

# Associative array: node id -> role, exactly what `ott roles` prints.
declare -A NODE_ROLE=()
ALL_IDS=()
SERVER_IDS=()
CLIENT_IDS=()
RELAY_IDS=()

# ---------------------------------------------------------------------
# Paths, classpath and the many `ott` invocations
# ---------------------------------------------------------------------

usage() {  # usage — print this file's header help block (lines 2-35)
    sed -n '2,35p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

require_scenario() {
    case "$1" in
        1|2|3) ;;
        *) echo "scenario must be 1, 2 or 3, got: $1" >&2; usage; exit 2 ;;
    esac
}

scenario_yaml() {  # scenario_yaml <n> — path relative to OTT_DIR
    echo "resorces/cenario$1-localhost.yaml"
}

# Build staleness: newest .java under src/ vs newest .class under
# target/classes (integer seconds, stat %Y). Same-second edit+compile is
# the one blind spot: the build looks fresh and no recompile happens.
needs_compile() {  # needs_compile — true when the build is missing or stale
    [ -d "$OTT_DIR/target/classes" ] || return 0
    local newest_class newest_source
    newest_class=$(find "$OTT_DIR/target/classes" -name '*.class' -exec stat -c %Y {} + |
        sort -rn | head -n1)
    newest_source=$(find "$OTT_DIR/src" -name '*.java' -exec stat -c %Y {} + |
        sort -rn | head -n1)
    [ -z "$newest_class" ] && return 0
    [ "${newest_source:-0}" -gt "$newest_class" ]
}

ensure_runtime() {
    if needs_compile; then
        echo "compiling $OTT_DIR (build missing or older than the sources)"
        mvn -q -f "$OTT_DIR/pom.xml" compile
    fi
    if [ ! -s "$CLASSPATH_FILE" ]; then
        echo "writing the dependency classpath to $CLASSPATH_FILE"
        mvn -q -f "$OTT_DIR/pom.xml" dependency:build-classpath \
            -Dmdep.outputFile="$CLASSPATH_FILE"
    fi
}

# ott — run the OTT program in the ott directory (so resorces/ paths resolve).
ott() {
    (cd "$OTT_DIR" && exec java -cp "$OTT_DIR/target/classes:$(cat "$CLASSPATH_FILE")" dev.pl36.Main "$@")
}

run_dir()  { echo "$RUN_ROOT/$1"; }
pid_file() { echo "$RUN_ROOT/$1/node-$2.pid"; }
log_file() { echo "$RUN_ROOT/$1/node-$2.log"; }

# ---------------------------------------------------------------------
# Roles (single source of truth: the scenario YAML, via `ott roles`)
# ---------------------------------------------------------------------

load_roles() {  # load_roles <n>
    require_scenario "$1"
    ensure_runtime
    # Local on purpose: bash scoping is dynamic and `read` nulls its targets
    # at EOF, so unlocalised loop variables would clobber a caller's own
    # `id`/`role` locals (e.g. cmd_kill's).
    local id role
    NODE_ROLE=()
    ALL_IDS=(); SERVER_IDS=(); CLIENT_IDS=(); RELAY_IDS=()
    while read -r id role _; do
        ALL_IDS+=("$id")
        NODE_ROLE[$id]="$role"
        case "$role" in
            server) SERVER_IDS+=("$id") ;;
            client) CLIENT_IDS+=("$id") ;;
            relay)  RELAY_IDS+=("$id") ;;
        esac
    done < <(ott roles "$(scenario_yaml "$1")")
    if [ "${#SERVER_IDS[@]}" -eq 0 ]; then
        echo "scenario $1 declares no server node — nothing can stream" >&2
        exit 1
    fi
}

index_of() {  # index_of <wanted> <candidate> ... — position in the list, or fail
    local wanted=$1; shift
    local position=0
    for candidate in "$@"; do
        if [ "$candidate" = "$wanted" ]; then
            echo "$position"
            return 0
        fi
        position=$((position + 1))
    done
    echo "node id $wanted not found in scenario (known ids: $*)" >&2
    return 1
}

# ---------------------------------------------------------------------
# Per-role start, stop and inspect
# ---------------------------------------------------------------------

server_videos() {  # server_videos <server index> — the --video flags
    case "$1" in
        0) echo "resorces/movie.Mjpeg resorces/movie2.Mjpeg" ;;
        *) echo "resorces/movie3.Mjpeg resorces/movie4.Mjpeg" ;;
    esac
}

server_flows() {  # server_flows <server index> — the flow ids those videos carry
    case "$1" in
        0) echo "movie.Mjpeg movie2.Mjpeg" ;;
        *) echo "movie3.Mjpeg movie4.Mjpeg" ;;
    esac
}

is_running() {  # is_running <n> <id>
    local pidfile pid
    pidfile=$(pid_file "$1" "$2")
    [ -f "$pidfile" ] || return 1
    pid=$(cat "$pidfile")
    kill -0 "$pid" 2>/dev/null
}

# this_checkout_nodes — every `java dev.pl36.Main` process whose classpath is
# this checkout's build (pgrep prints "pid full-cmdline" with -a). Matching on
# the build path keeps the sweep strictly local: a demo running from another
# checkout or a colleague's IDE-launched node is never touched.
this_checkout_nodes() {
    pgrep -af 'dev\.pl36\.Main' 2>/dev/null | grep -F "$OTT_DIR/target/classes" || true
}

# sweep_orphans — kill node processes of this checkout that no pid file owns.
# This is what heals the "Address already in use" of a next `up`: an interrupted
# or double `up` can leave servers/clients/relays alive while their pid files
# are stale or gone, so `down` alone cannot see them and the fixed ports
# (25001+, 25010+, 500x) stay occupied forever (a server awaits a latch after
# its movie ends — it never exits by itself). TERM first so overlays send
# GOODBYE, then KILL what is left.
sweep_orphans() {
    local line pid swept=0
    while IFS= read -r line; do
        pid=${line%% *}
        kill "$pid" 2>/dev/null || true
        swept=$((swept + 1))
    done < <(this_checkout_nodes)
    if [ "$swept" -gt 0 ]; then
        sleep 0.3
        while IFS= read -r line; do
            kill -9 "${line%% *}" 2>/dev/null || true
        done < <(this_checkout_nodes)
        echo "swept $swept orphaned node process(es) of this checkout (unrecorded pid)"
    fi
}

# require_free_port <tcp|udp> <port> <description> [addr] — pre-flight bind
# check so a node never starts just to die on "Address already in use". With
# `ss` present, the error names the holder's pid; either way it names the fix.
# Without an address argument any listener on the port counts (the server
# control and client data ports are wildcard-bound, so one per port). With an
# address, only that exact local address counts — relays of one scenario share
# the overlay port on distinct 127.0.0.x addresses. ":<port>" must be followed
# by a non-digit, so :5000 never matches inside :50001.
require_free_port() {
    local proto=$1 port=$2 description=$3 addr=${4:-} raw line holder pattern matches
    command -v ss >/dev/null || return 0
    case "$proto" in
        tcp) raw=$(ss -ltnpH 2>/dev/null) ;;
        udp) raw=$(ss -lunpH 2>/dev/null) ;;
    esac
    if [ -n "$addr" ]; then
        pattern="$addr[^0-9]*:$port[^0-9]"
    else
        pattern=":$port[^0-9]"
    fi
    # Grep's empty result must mean "free": a `<<<""` here-string carries one
    # empty line, which would run the error branch on an empty match.
    matches=$(grep -E "$pattern" <<<"$raw" || true)
    [ -n "$matches" ] || return 0
    while IFS= read -r line; do
        # `|| true` on a pipeline whose grep often has no match (`pid=` needs
        # same-user processes and `ss -p`): without it, pipefail makes the
        # assignment fail and set -e kills the script before any error prints.
        holder=$(grep -oE 'pid=[0-9]+' <<<"$line" | head -n1 | cut -d= -f2 || true)
        echo "ERROR: $description cannot bind port $port — the port is already taken" >&2
        if [ -n "$holder" ]; then
            echo "       holder: pid $holder ($(ps -o cmd= -p "$holder" 2>/dev/null | cut -c1-60))" >&2
        fi
        echo "       likely an orphaned node of an earlier run, or another scenario/checkout" >&2
        echo "       fix: $0 down <scenario_number>   (down also sweeps orphaned processes)" >&2
        exit 1
    done <<<"$matches"
}

start_process() {  # start_process <n> <id> <ott args...> — background java, log + pid file
    local n=$1 id=$2; shift 2
    (
        cd "$OTT_DIR"
        nohup java -cp "$OTT_DIR/target/classes:$(cat "$CLASSPATH_FILE")" \
            dev.pl36.Main "$@" </dev/null >>"$(log_file "$n" "$id")" 2>&1 &
        echo $! >"$(pid_file "$n" "$id")"
    )
}

start_node() {  # start_node <n> <id> — start one node according to its YAML role
    local n=$1 id=$2
    local role=${NODE_ROLE[$id]:-}
    if [ -z "$role" ]; then
        echo "node id $id is not declared in scenario $n" >&2
        exit 1
    fi
    if is_running "$n" "$id"; then
        echo "node $id ($role): already running (pid $(cat "$(pid_file "$n" "$id")")), skipped"
        return 0
    fi
    mkdir -p "$(run_dir "$n")"
    case "$role" in
        server)
            local server_index control
            server_index=$(index_of "$id" "${SERVER_IDS[@]}")
            control=$((SERVER_FIRST_CONTROL_PORT + server_index))
            require_free_port tcp "$control" "server node $id (control channel)"
            local -a args=(server)
            local video
            for video in $(server_videos "$server_index"); do
                args+=(--video "$video")
            done
            args+=(--control "$control")
            start_process "$n" "$id" "${args[@]}"
            sleep 1
            if ! is_running "$n" "$id"; then
                echo "server node $id died at startup, log tail:" >&2
                tail -20 "$(log_file "$n" "$id")" >&2
                exit 1
            fi
            echo "node $id ($role): started, control 127.0.0.1:$control, owns $(server_flows "$server_index")"
            ;;
        client)
            local client_index listen server_index control
            client_index=$(index_of "$id" "${CLIENT_IDS[@]}")
            listen=$((CLIENT_FIRST_LISTEN_PORT + client_index))
            require_free_port udp "$listen" "client node $id (data listen port)"
            server_index=$((client_index % ${#SERVER_IDS[@]}))
            control=$((SERVER_FIRST_CONTROL_PORT + server_index))
            if ! is_running "$n" "${SERVER_IDS[$server_index]}"; then
                echo "warning: server node ${SERVER_IDS[$server_index]} is not running; node $id will only listen"
            fi
            local -a args=(client --listen "$listen" --server "127.0.0.1:$control")
            local want
            for want in $(server_flows "$server_index"); do
                args+=(--want "$want")
            done
            start_process "$n" "$id" "${args[@]}"
            sleep 1
            if ! is_running "$n" "$id"; then
                echo "client node $id died at startup, log tail:" >&2
                tail -20 "$(log_file "$n" "$id")" >&2
                exit 1
            fi
            echo "node $id ($role): started, listen $listen, wants $(server_flows "$server_index") from 127.0.0.1:$control"
            ;;
        relay)
            # A relay runs the real overlay process (node mode): the neighbour
            # mesh with redial and HELLO/GOODBYE is the observable behaviour;
            # data-plane forwarding (M3) is not wired yet, so it carries no
            # frames — the node-mode listener only logs control messages and
            # counts datagrams per neighbour.
            # Bind check is relay-specific: the node owns 127.0.0.(id+1) and
            # the scenario overlay port 5000+N-1 (both TCP and UDP); other
            # relays of the same scenario share the port on other addresses.
            local overlay_port overlay_addr
            overlay_port=$((5000 + n - 1))
            overlay_addr="127.0.0.$((id + 1))"
            require_free_port tcp "$overlay_port" "relay node $id (overlay)" "$overlay_addr"
            require_free_port udp "$overlay_port" "relay node $id (overlay)" "$overlay_addr"
            start_process "$n" "$id" "$id" "$(scenario_yaml "$n")"
            sleep 1
            if ! is_running "$n" "$id"; then
                echo "relay node $id died at startup, log tail:" >&2
                tail -20 "$(log_file "$n" "$id")" >&2
                exit 1
            fi
            echo "node $id ($role): started, overlay node (log lines are prefixed [node $id])"
            ;;
    esac
}

kill_all() {  # kill_all <n> — TERM then KILL every recorded pid, sweep orphans; logs kept
    local pidfile pid
    for pidfile in "$RUN_ROOT/$1"/*.pid; do
        [ -e "$pidfile" ] || continue
        pid=$(cat "$pidfile" 2>/dev/null || true)
        [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
    done
    sleep 0.3
    for pidfile in "$RUN_ROOT/$1"/*.pid; do
        [ -e "$pidfile" ] || continue
        pid=$(cat "$pidfile" 2>/dev/null || true)
        [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null || true
    done
    rm -f "$RUN_ROOT/$1"/*.pid
    sweep_orphans
}

is_skipped() {  # is_skipped <id> <excluded ids...>
    local wanted=$1; shift
    for candidate in "$@"; do
        [ "$candidate" = "$wanted" ] && return 0
    done
    return 1
}

# ---------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------

UP_COMPLETE=0

cleanup_interrupted() {  # cleanup_interrupted <n> <why>
    echo >&2 "$2: stopping all nodes of scenario $1"
    kill_all "$1"
    [ "$2" = "interrupted" ] && exit 130
    exit 1
}

cmd_up() {  # cmd_up <n> [id to skip ...]
    local n=$1; shift
    local skipped_ids=("$@")
    load_roles "$n"
    mkdir -p "$(run_dir "$n")"

    # An interrupted or failing `up` must not leave orphans behind; a normal
    # exit must (UP_COMPLETE distinguishes the two). The scenario number lives
    # in a global because a trap expands its text where it fires — top scope —
    # where cmd_up's local `n` (and even an `n:`-free message) is unreachable;
    # expanding an unset local there was reported as the cryptic
    # "line 1: n: unbound variable".
    UP_SCENARIO=$n
    trap 'cleanup_interrupted "$UP_SCENARIO" interrupted' INT TERM
    trap '[ "$UP_COMPLETE" -eq 1 ] || cleanup_interrupted "$UP_SCENARIO" failed' EXIT

    # 1. relay nodes first: their dial loops start redialing immediately; a
    #    broken scenario dies here, before any long-running stream process.
    for id in "${RELAY_IDS[@]}"; do
        is_skipped "$id" ${skipped_ids+"${skipped_ids[@]}"} && continue
        start_node "$n" "$id"
    done
    # 2. servers: their control port must accept before clients send WANT_FLOW.
    for id in "${SERVER_IDS[@]}"; do
        is_skipped "$id" ${skipped_ids+"${skipped_ids[@]}"} && continue
        start_node "$n" "$id"
    done
    # 3. clients.
    for id in "${CLIENT_IDS[@]}"; do
        is_skipped "$id" ${skipped_ids+"${skipped_ids[@]}"} && continue
        start_node "$n" "$id"
    done

    UP_COMPLETE=1
    echo "scenario $n up: $((${#SERVER_IDS[@]} + ${#CLIENT_IDS[@]})) streaming process(es), logs in $(run_dir "$n")"
    echo "follow along with: $0 logs $n"
}

cmd_start() {  # cmd_start <n> <id>
    local n=$1 id=$2
    load_roles "$n"
    start_node "$n" "$id"
}

cmd_down() {  # cmd_down <n>
    require_scenario "$1"
    kill_all "$1"
    echo "scenario $1 down (logs kept in $(run_dir "$1"))"
}

# cmd_kill <n> <id> — abruptly kill one node: SIGKILL runs no exit code in the
# node, so neighbours detect the loss by EOF instead of a GOODBYE.
cmd_kill() {
    local n=$1 id=$2
    load_roles "$n"
    local role=${NODE_ROLE[$id]:-}
    if [ -z "$role" ]; then
        echo "node id $id is not declared in scenario $n" >&2
        exit 1
    fi
    local pidfile pid
    pidfile=$(pid_file "$n" "$id")
    if [ ! -f "$pidfile" ]; then
        echo "node $id of scenario $n is not running (no pid file)" >&2
        exit 1
    fi
    pid=$(cat "$pidfile")
    if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$pidfile"
        echo "node $id of scenario $n was already dead (stale pid file removed)" >&2
        exit 1
    fi
    kill -9 "$pid"
    rm -f "$pidfile"
    echo "node $id ($role) of scenario $n killed abruptly (pid $pid; neighbours see EOF, no GOODBYE)"
}

# cmd_stop <n> <id> — gracefully stop one node: SIGTERM runs the node's shutdown
# hook, which sends GOODBYE to every live neighbour before exiting.
cmd_stop() {
    local n=$1 id=$2
    load_roles "$n"
    local role=${NODE_ROLE[$id]:-}
    if [ -z "$role" ]; then
        echo "node id $id is not declared in scenario $n" >&2
        exit 1
    fi
    local pidfile pid
    pidfile=$(pid_file "$n" "$id")
    if [ ! -f "$pidfile" ]; then
        echo "node $id of scenario $n is not running (no pid file)" >&2
        exit 1
    fi
    pid=$(cat "$pidfile")
    if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$pidfile"
        echo "node $id of scenario $n was already dead (stale pid file removed)" >&2
        exit 1
    fi
    kill -TERM "$pid"
    rm -f "$pidfile"
    echo "node $id ($role) of scenario $n stopping gracefully (pid $pid; sends GOODBYE to its neighbours)"
}

# cmd_roles <n> — print the scenario's id/role/ip_addr table, straight from
# the YAML via `ott roles` (the single source of truth for both this script
# and any doc or demo on top of it).
cmd_roles() {
    require_scenario "$1"
    ensure_runtime
    ott roles "$(scenario_yaml "$1")"
}

cmd_status() {  # cmd_status <n>
    local n=$1
    load_roles "$n"
    local id role pidfile pid state
    printf '%-4s %-8s %-8s %s\n' "id" "role" "pid" "state"
    for id in "${ALL_IDS[@]}"; do
        role=${NODE_ROLE[$id]}
        pidfile=$(pid_file "$n" "$id")
        pid="-"
        state="not started"
        if [ -f "$pidfile" ]; then
            pid=$(cat "$pidfile" 2>/dev/null || true)
            if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
                state="running"
            else
                state="dead"
            fi
        elif [ -f "$(log_file "$n" "$id")" ]; then
            state="stopped (log exists, no pid)"
        fi
        printf '%-4s %-8s %-8s %s\n' "$id" "$role" "${pid:--}" "$state"
    done
}

cmd_logs() {  # cmd_logs <n> [id]
    local n=$1; shift
    load_roles "$n"
    local id
    local -a files=()
    if [ "$#" -gt 0 ]; then
        id=$1
        if [ -z "${NODE_ROLE[$id]:-}" ]; then
            echo "node id $id is not declared in scenario $n" >&2
            exit 1
        fi
        [ -f "$(log_file "$n" "$id")" ] || { echo "no log for node $id yet" >&2; exit 1; }
        files=("$(log_file "$n" "$id")")
    else
        for id in "${ALL_IDS[@]}"; do
            [ -f "$(log_file "$n" "$id")" ] && files+=("$(log_file "$n" "$id")")
        done
    fi
    if [ "${#files[@]}" -eq 0 ]; then
        echo "no logs under $(run_dir "$n") yet — start the scenario with: $0 up $n" >&2
        exit 1
    fi
    # Foreground tail: Ctrl-C stops the tail, never the scenario.
    exec tail -n 30 -f "${files[@]}"
}

# ---------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------

command=${1:-}
[ -n "$command" ] || { usage; exit 2; }
shift
case "$command" in
    up)     [ "$#" -ge 1 ] || { usage; exit 2; }; cmd_up "$@" ;;
    start)  [ "$#" -eq 2 ] || { usage; exit 2; }; cmd_start "$1" "$2" ;;
    down)   [ "$#" -eq 1 ] || { usage; exit 2; }; cmd_down "$1" ;;
    stop)   [ "$#" -eq 2 ] || { usage; exit 2; }; cmd_stop "$1" "$2" ;;
    kill)   [ "$#" -eq 2 ] || { usage; exit 2; }; cmd_kill "$1" "$2" ;;
    status) [ "$#" -eq 1 ] || { usage; exit 2; }; cmd_status "$1" ;;
    roles)  [ "$#" -eq 1 ] || { usage; exit 2; }; cmd_roles "$1" ;;
    logs)   [ "$#" -ge 1 ] || { usage; exit 2; }; cmd_logs "$@" ;;
    -h|--help|help) usage ;;
    *) usage; exit 2 ;;
esac
