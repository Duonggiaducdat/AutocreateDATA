# Known issues needing owner attention

## 🟢 GitHub Personal Access Token exposed in git remote URL — RESOLVED 2026-09-11

The old origin (`Duonggiaducdat/AutomationCreatData`) had a live PAT embedded
directly in the remote URL (plaintext in `.git/config`). That token had
already stopped authenticating (expired/revoked) by the time this was found.

**Action taken:** moved to a new repo, `Duonggiaducdat/AutocreateDATA`
(private). The remote URL now has no embedded credential
(`https://github.com/Duonggiaducdat/AutocreateDATA.git`); pushes use a
short-lived fine-grained PAT passed on the command line only, never stored in
`.git/config`.

**Still to do:** if the old repo (`AutomationCreatData`) is not needed
anymore, consider deleting or archiving it — its history still contains the
old (now-dead) token.

## 🟡 Reviewer credentials in application.yml

`application.yml` has (or had) an `autodata.defaults.reviewer-username` /
`reviewer-password` block with a real BMS login (`reviewer01@fpt.com` /
password in plaintext). This file is tracked in git. Same risk category as
the PAT above — anyone with repo access has the password in plaintext, and
`git log` keeps it even if it's later removed from the working tree.

**Recommended fix:** move to environment variables (`${AUTODATA_REVIEWER_PASSWORD}`
in the yml, actual value in a local `.env`/secret store, never committed),
and rotate the password since it's already been exposed in history.

## 🟡 `backend/screenshots/` — 50+ error screenshots, not investigated

`error_*.png` files span 2026-05-12 through 2026-05-21 — a full day's worth
of Selenium runs failing repeatedly, no pattern analysis has been done. If a
generator is failing consistently rather than flakily, that's worth checking
before assuming "Selenium is just flaky" — could be a real regression in the
BMS UI (changed selector, new required field, etc). See `architecture.md`
for the case for migrating a proven-flaky flow to a direct-API generator
instead of just retrying it.

## 🟢 Sandbox network note (not a repo bug)

If you're running any of this from an isolated/cloud dev sandbox rather than
a machine on FPT's network/VPN, direct calls to `ftisapi-stag.fpt.net` or
`ftms-stag.fpt.net` may fail at the proxy/DNS layer before ever reaching the
BMS API — this looks like a `503`/connection error but has nothing to do with
the request itself. Confirmed by testing from such a sandbox on 2026-08-25:
a local mock server was also unreachable through the same proxy. Verify
network reachability (`curl -I <base-url>`) before debugging a generator as
if it were an application bug.
