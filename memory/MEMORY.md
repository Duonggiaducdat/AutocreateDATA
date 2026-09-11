# memory/ — AutocreateDATA · fullstack

Project-local knowledge base for this repo. Not the same thing as any AI
assistant's personal memory — this is documentation that lives in git,
for whoever (human or AI) works on this codebase next.

## Index

- [architecture.md](architecture.md) — Selenium generators vs the new direct-API generator; why `opportunity.api` exists
- [api-contracts.md](api-contracts.md) — BMS REST payload reference for Opportunity creation (confirmed vs unconfirmed fields)
- [known-issues.md](known-issues.md) — security/config issues found in the repo that need owner attention

## How to keep this updated

- Adding a new **API-based** generator (bypassing Selenium)? Document its payload in `api-contracts.md` the same way `opportunity.api` is documented — endpoint, full field table, what's confirmed vs guessed, and how it was confirmed (curl capture, live UI cross-check, etc).
- Confirming a previously-"UNCONFIRMED" field (e.g. the real `priority` numeric codes, or a `serviceGroup`/`serviceType` label→code map)? Update `api-contracts.md` in place and remove the UNCONFIRMED flag — don't leave stale guesses next to confirmed facts.
- Found a new flaky Selenium spot (another 20 screenshots in `backend/screenshots/`)? Note the pattern in `known-issues.md` — if there's an API endpoint for it, that's a candidate for a new `*.api` generator.
