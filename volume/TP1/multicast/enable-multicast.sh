#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
# shellcheck disable=SC1091
source "$TP1_ROOT/scripts/tp1-env.sh"

[[ ${EUID:-$(id -u)} -eq 0 ]] || { echo "ERROR: run this script with sudo/root on the router." >&2; exit 1; }
command -v ip >/dev/null 2>&1 || { echo "ERROR: ip command not found." >&2; exit 1; }
command -v smcrouted >/dev/null 2>&1 || { echo "ERROR: smcrouted is not installed (smcroute package)." >&2; exit 1; }
command -v smcroutectl >/dev/null 2>&1 || { echo "ERROR: smcroutectl is not installed." >&2; exit 1; }

iface_for_ip() {
  local addr="$1"
  ip -o -4 addr show | awk -v a="$addr" '$4 ~ ("^" a "/") {print $2; exit}'
}

RA_LAN_IF="$(iface_for_ip "$ROUTERA_LAN_IP" || true)"
RA_TR_IF="$(iface_for_ip "$ROUTERA_TRANSIT_IP" || true)"
RB_TR_IF="$(iface_for_ip "$ROUTERB_TRANSIT_IP" || true)"
RB_LAN_IF="$(iface_for_ip "$ROUTERB_LAN_IP" || true)"

if [[ -n "$RA_LAN_IF" && -n "$RA_TR_IF" ]]; then
  ROLE="routerA"; IIF="$RA_LAN_IF"; OIF="$RA_TR_IF"
elif [[ -n "$RB_TR_IF" && -n "$RB_LAN_IF" ]]; then
  ROLE="routerB"; IIF="$RB_TR_IF"; OIF="$RB_LAN_IF"
else
  echo "ERROR: this node does not appear to be routerA or routerB." >&2
  echo "Run this script in a routerA shell and then in a routerB shell." >&2
  exit 1
fi

IDENT="tp1mcast"
# Older SMCRoute versions use -I; recent versions use -i. Detect at runtime.
if smcrouted -h 2>&1 | grep -q -- '-i NAME'; then
  IDOPT=(-i "$IDENT")
else
  IDOPT=(-I "$IDENT")
fi

# Stop only the laboratory instance with this identity, if it exists.
smcroutectl "${IDOPT[@]}" kill >/dev/null 2>&1 || true
sleep 0.2

# Start the daemon in the namespace/network of the current router.
smcrouted "${IDOPT[@]}" -l notice
sleep 0.5

# Install a static source-specific multicast route (S,G).
smcroutectl "${IDOPT[@]}" rem "$IIF" "$STREAMER_IP" "$MCAST_GROUP" 2>/dev/null || true
smcroutectl "${IDOPT[@]}" add "$IIF" "$STREAMER_IP" "$MCAST_GROUP" "$OIF"

echo "Multicast configured on $ROLE"
echo "  input  : $IIF"
echo "  source : $STREAMER_IP"
echo "  group  : $MCAST_GROUP"
echo "  output : $OIF"
echo
smcroutectl "${IDOPT[@]}" show routes || true
