# Repository agent instructions

- v2 (the on-TV Android app) follows [`docs/redesign-plan.md`](docs/redesign-plan.md).
- **Private notes.** The progress ledger and session logs live in `notes/`. It is a clone of the private repo `k45sle/android-tv-speed-compiler-notes`, and this public repo ignores it.
  - Start every session by reading the plan and `notes/ledger.md`. If `notes/` is missing, look in the main checkout at `~/android-tv-speed-compiler/notes/`. If it isn't there either (for example in a cloud session), run `gh repo clone k45sle/android-tv-speed-compiler-notes notes`.
  - Run `git -C notes pull` before reading.
  - Before a session ends, update the ledger as its rules say, even when the work failed or stopped partway. Then commit and push inside `notes/`.
  - Never copy ledger content into this public repo or into PR text.
- **Budget: free only.** The whole project must cost under $1 in total.
  - Use only free and open-source tools, libraries, fonts, services and hosting.
  - Never sign up for or enable anything paid, including trials that need a card, paid CI minutes, the Google Play developer fee or paid fonts.
  - If a task seems to need something paid, stop and offer the free alternative instead.
- **This repo is public, so everything committed here is world-readable.**
  - Never commit any of these: IP or MAC addresses, network names, device serials, build fingerprints, pairing codes, keys, tokens or owner identity.
  - This covers `docs/device-matrix.md`, test fixtures and PR text. Use placeholders such as `<tv-ip>`.
- **Keep GitHub in sync.** `origin` is github.com/k45sle/android-tv-speed-compiler. Push verified commits to it.
- For v1 (the Python and Docker service), deployment, container troubleshooting, upgrades, or restore work, follow [`docs/agent-self-hosting.md`](docs/agent-self-hosting.md) before acting.
