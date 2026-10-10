#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Guard for marketing / install smoke / release verification on shared Macs.
# Refuses to proceed if the effective data dir is the real ~/.aiworkdeck unless
# AWD_ALLOW_REAL_HOME=1 AND AWD_GM_HOME_ACK=1 (GM written confirmation).
# See team/incidents/2026-10-10-desktop-home-wipe.md and team/QUALITY.md.
set -euo pipefail

REAL_HOME="${HOME}/.aiworkdeck"
USER_DATA="${AWD_USER_DATA_DIR:-${ELECTRON_USER_DATA_DIR:-}}"
SANDBOX_HOME="${AWD_SANDBOX_HOME:-}"

die() { echo "check-no-real-home: FAIL — $*" >&2; exit 1; }
ok() { echo "check-no-real-home: ok — $*"; }

# Explicit user-data-dir must not resolve to real home
if [[ -n "$USER_DATA" ]]; then
  resolved="$(cd "$USER_DATA" 2>/dev/null && pwd -P || echo "$USER_DATA")"
  real_resolved="$(cd "$REAL_HOME" 2>/dev/null && pwd -P || echo "$REAL_HOME")"
  if [[ "$resolved" == "$real_resolved" ]]; then
    die "AWD_USER_DATA_DIR points at real ~/.aiworkdeck ($resolved). Use demo-userdata."
  fi
  ok "user-data-dir=$resolved (not real home)"
  exit 0
fi

# Sandbox HOME (marketing): ~/.aiworkdeck under sandbox is fine
if [[ -n "$SANDBOX_HOME" ]]; then
  ok "sandbox HOME=$SANDBOX_HOME"
  exit 0
fi

# No isolation flags: require GM ack to touch real home
if [[ "${AWD_ALLOW_REAL_HOME:-}" == "1" && "${AWD_GM_HOME_ACK:-}" == "1" ]]; then
  ok "real home allowed (AWD_ALLOW_REAL_HOME+AWD_GM_HOME_ACK)"
  exit 0
fi

die "refusing unmarked run against default ~/.aiworkdeck. Set --user-data-dir / AWD_USER_DATA_DIR to a demo path, or get GM ack (AWD_ALLOW_REAL_HOME=1 AWD_GM_HOME_ACK=1)."
