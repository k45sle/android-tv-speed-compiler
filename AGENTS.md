# Repository agent instructions

- v2 (the on-TV Android app) follows [`docs/redesign-plan.md`](docs/redesign-plan.md).
  - At the start of every session, read the plan and [`docs/ledger.md`](docs/ledger.md). The ledger records what is done, what failed, and what comes next.
  - Before a session ends, update the ledger as its rules say, even when the work failed or stopped partway.
- Keep GitHub in sync. `origin` is github.com/k45sle/android-tv-speed-compiler. Push verified commits to it, including ledger updates, so the next session starts from the current state.
- For v1 (the Python and Docker service), deployment, container troubleshooting, upgrades, or restore work, follow [`docs/agent-self-hosting.md`](docs/agent-self-hosting.md) before acting.
