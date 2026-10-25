# UI tweaks — 2 sessions

Follow-up to the 10-session build (PLAN.md). Small, scoped changes to the
Receive and Send screens. Each session is implemented by an OpenCode
DeepSeek sub-agent, sequential (session 2 doesn't depend on session 1's
code, but running sequentially keeps commits easy to review).

1. **Receive screen: ordered multi-select + clear.**
   - Selecting a row no longer just checks it — it records *when* it was
     selected, up to 15 at once. Each selected row shows its selection
     order (1-15) as a number in a green badge instead of a checkmark;
     deselecting one renumbers the rest so the shown numbers stay
     contiguous (1..N, no gaps).
   - "Add N to send queue" enqueues rows in that same order (first
     selected -> first queued), not list/row order.
   - A new "Clear received messages" action wipes all accumulated rows
     and the current selection.
2. **Send screen: drop presets, add clear-queue.**
   - Remove the six preset-scenario buttons and their card from the UI
     (and the now-unused plumbing feeding them).
   - Add a "Clear queue" action that empties the send queue in one tap.
