# Repository agent instructions

- v2 (the on-TV Android app) follows [`docs/redesign-plan.md`](docs/redesign-plan.md).
  - At the start of every session, read the plan and [`docs/ledger.md`](docs/ledger.md). The ledger records what is done, what failed, and what comes next.
  - Before a session ends, update the ledger as its rules say, even when the work failed or stopped partway.
- **Budget: free only.** The whole project must cost under $1 in total.
  - Use only free and open-source tools, libraries, fonts, services and hosting.
  - Never sign up for or enable anything paid, including trials that need a card, paid CI minutes, the Google Play developer fee or paid fonts.
  - If a task seems to need something paid, stop and offer the free alternative instead.
- **The repo is public, so everything committed is world-readable.** Never commit IP or MAC addresses, network names, device serials, build fingerprints, pairing codes, keys, tokens or owner identity. This includes the ledger, the device matrix, test fixtures and PR text. Use placeholders such as `<tv-ip>`.
- Keep GitHub in sync. `origin` is github.com/k45sle/android-tv-speed-compiler. Push verified commits to it, including ledger updates, so the next session starts from the current state.
- For v1 (the Python and Docker service), deployment, container troubleshooting, upgrades, or restore work, follow [`docs/agent-self-hosting.md`](docs/agent-self-hosting.md) before acting.
