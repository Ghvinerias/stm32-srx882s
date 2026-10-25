# Favorites — 1 session

Follow-up to PLAN.md / PLAN_UI_TWEAKS.md. Lets the user save the current
send queue under a name and reload it later. Favorites must survive the
app process being stopped and relaunched (not just a configuration change),
so they're persisted to disk, not just kept in memory.

1. **Save/load named send-queue snapshots.** A new `com.eelab.rflab.favorites`
   package: a `FavoriteQueue` model (name + a list of hopping/serial/button/
   frameCount tuples — no runtime id/status, those belong to the live send
   queue only), a `FavoritesRepository` exposing the saved list as a
   `StateFlow` with save/load/delete actions, and a small storage seam
   (interface + a `SharedPreferences`-backed implementation using `org.json`,
   already on the classpath — no new dependency needed) so the repository is
   unit-testable with an in-memory fake and the production path survives
   process death.
2. **Send screen UI.** A "Favorites" section: save the current queue under a
   typed name (disabled when the queue is empty), a list of saved favorites
   each with Load/Delete, following the existing header/card conventions in
   `SendScreen.kt`.
