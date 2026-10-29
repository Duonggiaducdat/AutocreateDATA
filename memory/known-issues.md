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

## 🟢 Reviewer credentials in application.yml — RESOLVED 2026-10-29

`application.yml` used to carry an `autodata.defaults.reviewer-username` /
`reviewer-password` block with a real BMS login + a real STG OTP-bypass
password in plaintext, tracked in git since the initial commit. Before
making the repo public:

1. **`application.yml` fixed** — both now read from env vars
   (`${AUTODATA_REVIEWER_USERNAME:}` / `${AUTODATA_REVIEWER_PASSWORD:}`),
   empty default. Set the real values locally via a gitignored `.env` /
   shell profile, never commit them.
2. **Every real FPT employee username/email and the real OTP-bypass code
   were redacted from the ENTIRE git history** (not just the working tree —
   `git log -p` kept the old password even after step 1), then force-pushed.
   Role names are now generic placeholders (`sale01`, `admin01`, `bgd01`,
   `tpp01`-`tpp05`, `reviewer01`, `qatester01`) throughout code, memory docs,
   and comments.
3. **Known side-effect:** `pages/CreatePage.java`'s `chooseProve` /
   `scrollProveOptions` / `chooseReview` locators matched literal display
   text rendered by the real BMS UI (an approver's real name, e.g.
   `"Bgd01 - BGD01"`). Those literals are now redacted placeholders
   and will **not** match the real UI anymore. If you resume driving this
   exact legacy Selenium flow against a real STG account, restore the real
   display text in those 3 `By` locators locally — don't recommit it.

**Still to do (not something I can do from here):** the real password was
already live on STG and exposed in this history — **rotate/change it** with
whoever administers FTMS STG auth, independent of the git cleanup above.

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
