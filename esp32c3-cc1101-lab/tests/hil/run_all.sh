#!/bin/bash
# Run the T1..T9 hardware-in-the-loop test suite on the bench machine.
#
#   ~/venv/bin/python tests/hil/run_all.sh            # everything
#   HIL_TESTS="t1 t3 t4" tests/hil/run_all.sh         # a subset
#
# Honours the same env vars as the individual scripts (ports overrides
# HIL_STM32_PORT / HIL_ESP_PORT, plus optional sigrok player channels via
# HIL_CH_ESP_TX / HIL_CH_ESP_RX); the venv python is one of them.
#
# T7 assumes the device is freshly booted (IDLE) when it starts, so it runs
# after T6, which leaves the radio back in IDLE like T4/T5 do.
#
# Captures land in tests/hil/captures/ (one file per test step, each kept
# under HIL_SR_MAX_MB=2 MB, older files are pruned daily-and-by-count).

set -u
HIL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${HIL_PY:-$HOME/venv/bin/python}"
CAP_DIR="$HIL_DIR/captures"
LOG_DIR="$CAP_DIR/logs"
mkdir -p "$CAP_DIR" "$LOG_DIR"

ALL="t1_diagnostics t2_tx_carrier t3_tx_frame t4_rx_frame t5_ble t6_usb t7_modes t8_long_burst t9_abort"
NAMES=""
for t in ${HIL_TESTS:-T1 T2 T3 T4 T5 T6 T7 T8 T9}; do
  case "$t" in
    T1|t1) NAMES="$NAMES t1_diagnostics";;
    T2|t2) NAMES="$NAMES t2_tx_carrier";;
    T3|t3) NAMES="$NAMES t3_tx_frame";;
    T4|t4) NAMES="$NAMES t4_rx_frame";;
    T5|t5) NAMES="$NAMES t5_ble";;
    T6|t6) NAMES="$NAMES t6_usb";;
    T7|t7) NAMES="$NAMES t7_modes";;
    T8|t8) NAMES="$NAMES t8_long_burst";;
    T9|t9) NAMES="$NAMES t9_abort";;
    *) case "$t" in
         t?_*) NAMES="$NAMES $t";;
         *) echo "unknown test id '$t', skipping" >&2;;
       esac;;
  esac
done
[ -z "$NAMES" ] && NAMES="$ALL"

# prune captures: keep each file <= 2 MB, drop anything older than 2 days,
# and never keep more than the newest 80 files
find "$CAP_DIR" -maxdepth 1 -type f -size +2M -delete 2>/dev/null || true
find "$CAP_DIR" -maxdepth 1 -type f -mtime +2 -delete 2>/dev/null || true
ls -t "$CAP_DIR" 2>/dev/null | tail -n +81 2>/dev/null | while read -r old; do
    [ -n "$old" ] && rm -f -- "$CAP_DIR/$old"
done

if [ ! -x "$PY" ]; then
  echo "hil: python interpreter '$PY' not found (set HIL_PY=...)" >&2
  exit 2
fi

declare -a RESULTS=()
FAILED=0
for t in $NAMES; do
  echo "=== $t ===" >&2
  START=$(date +%s)
  "$PY" "$HIL_DIR/${t}.py" 2>&1 | tee "$LOG_DIR/${t}.log"
  rc=${PIPESTATUS[0]}
  EL=$(( $(date +%s) - START ))
  FINAL=$(grep -E "^$t t?_?[a-z_]+: (PASS|FAIL)" "$LOG_DIR/${t}.log" | tail -n 1)
  [ -z "$FINAL" ] && FINAL="$(grep -E "^(T[0-9]+|selftest)" "$LOG_DIR/${t}.log" | tail -n 1)"
  RESULTS+=("${t} rc=${rc} ${EL}s :: ${FINAL}")
  if [ "$rc" -ne 0 ]; then FAILED=$((FAILED+1)); fi
done

echo
echo "================= hil summary ================="
for line in "${RESULTS[@]}"; do
  echo "$line"
done
echo "-----------------------------------------------"
if [ "$FAILED" -eq 0 ]; then
  echo "ALL ${NAMES# } PASSED"
else
  echo "$FAILED of ${NAMES// /+} script(s) exited non-zero"
fi
exit $([ "$FAILED" -eq 0 ] && echo 0 || echo 1)
