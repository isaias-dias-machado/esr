#!/usr/bin/env bash
# ESR 2026/27 - TP1
# Common variables for all experiments.
# When this file is sourced from a CORE VCMD (normally as root),
# TP1_ROOT is derived from the actual package location rather than from $HOME.

_TP1_ENV_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export TP1_ROOT="${TP1_ROOT:-$(cd "$_TP1_ENV_DIR/.." && pwd)}"
unset _TP1_ENV_DIR

# Addressing plan for the tp1.xml topology
export ROUTERA_LAN_IP="${ROUTERA_LAN_IP:-10.0.1.1}"
export STREAMER_IP="${STREAMER_IP:-10.0.1.10}"
export PC1_IP="${PC1_IP:-10.0.1.11}"
export PC2_IP="${PC2_IP:-10.0.1.12}"

export ROUTERA_TRANSIT_IP="${ROUTERA_TRANSIT_IP:-10.0.12.1}"
export ROUTERB_TRANSIT_IP="${ROUTERB_TRANSIT_IP:-10.0.12.2}"

export ROUTERB_LAN_IP="${ROUTERB_LAN_IP:-10.0.2.1}"
export PC3_IP="${PC3_IP:-10.0.2.13}"
export PC4_IP="${PC4_IP:-10.0.2.14}"

# Ports used in TP1
export DASH_HTTP_PORT="${DASH_HTTP_PORT:-8080}"
export HTTP2_PORT="${HTTP2_PORT:-8443}"
export HTTP3_PORT="${HTTP3_PORT:-8444}"
export RTP_PORT="${RTP_PORT:-5004}"
export MCAST_GROUP="${MCAST_GROUP:-239.10.10.10}"

# Reference values for the routerA-routerB link
export BASELINE_BW_MBIT="${BASELINE_BW_MBIT:-10}"
export BASELINE_DELAY_MS="${BASELINE_DELAY_MS:-10}"
export BASELINE_LOSS_PCT="${BASELINE_LOSS_PCT:-0}"

# dash.js version expected in the package
export DASHJS_VERSION="${DASHJS_VERSION:-5.2.0}"
