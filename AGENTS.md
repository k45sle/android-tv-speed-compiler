# Repository agent instructions

- **Private notes.** The v2 plan (`notes/redesign-plan.md`) and the progress ledger (`notes/ledger.md`) live in `notes/`. It is a clone of the private repo `k45sle/android-tv-speed-compiler-notes`, and this public repo ignores it.
  - Start every session with `git -C notes pull`, then read the ledger, starting with its **Next session** block, and the plan.
  - If `notes/` is missing, look in the main checkout at `~/android-tv-speed-compiler/notes/`. If it isn't there either (for example in a cloud session), run `gh repo clone k45sle/android-tv-speed-compiler-notes notes`.
  - When the owner says "continue" or gives no specific task, run the ledger's Next session prompt.
  - Before a session ends, update the ledger as its rules say, even when the work failed or stopped partway, and rewrite its Next session block. Then commit and push inside `notes/`.
  - Never copy plan or ledger content into this public repo or into PR text. Subagents in worktrees have no `notes/`, so pass them the chunk text they need.
- **Budget: free only.** The whole project must cost under $1 in total.
  - Use only free and open-source tools, libraries, fonts, services and hosting.
  - Never sign up for or enable anything paid, including trials that need a card, paid CI minutes, the Google Play developer fee or paid fonts.
  - If a task seems to need something paid, stop and offer the free alternative instead.
- **This repo is public, so everything committed here is world-readable.**
  - Never commit any of these: IP or MAC addresses, network names, device serials, build fingerprints, pairing codes, keys, tokens or owner identity.
  - This covers `docs/device-matrix.md`, test fixtures and PR text. Use placeholders such as `<tv-ip>`.
- **Keep GitHub in sync.** `origin` is github.com/k45sle/android-tv-speed-compiler. Push verified commits to it.
