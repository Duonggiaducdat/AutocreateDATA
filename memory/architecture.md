# Architecture: Selenium generators vs API generators

## The existing pattern

Every data-creation flow is a `Generator` (Strategy pattern, see `backend/.../generator/Generator.java`).
`GeneratorRegistry` auto-discovers every `@Component` implementing it — no manual wiring.

Almost all existing generators (`LoginGenerator`, `OpportunityStep1/2/3Generator`,
`ContractRequestGenerator`, `IdcRequestGenerator`, etc.) work by driving a real
Chrome browser through `FtmsAutomation.withDriver(...)` and a `pages.*` Page
Object (`CreatePage`, `LoginPage`, ...). That's the only way to reach flows
that have no REST endpoint, or where the endpoint's contract isn't known.

**Trade-off:** this is why `backend/screenshots/` has 50+ `error_*.png` files
(one per failed run — element not found, stale wait, timing race, etc).
Each generator call also opens and tears down its own WebDriver session
(see README "Concurrency" note), so nothing is reused across steps either.

## The new pattern: direct-API generators

`OpportunityApiGenerator` (`type: opportunity.api`) is the first generator
that skips Selenium entirely — it POSTs straight to the BMS backend
(`POST {api.baseUrl}/opportunities`) using Java's built-in `java.net.http.HttpClient`.
No browser, no page objects, no multi-step navigation — one HTTP call in,
one JSON result out. It requires a bearer token (paste one from a logged-in
session; see `api-contracts.md`) since this path has no login step of its own.

**Where this pattern applies:** only where the BMS REST contract is known
(captured from a real browser Network tab, or reverse-engineered from the
frontend bundle). `api-contracts.md` documents the Opportunity contract this
way. Do the same before writing a new `*.api` generator for another flow —
don't guess a payload shape from the Selenium Page Object alone.

**Where it doesn't apply (yet):** flows with no confirmed REST contract —
those stay on the Selenium path until someone captures the real request.

## Config split

`AutodataProperties`:
- `autodata.webdriver.*` — Selenium path (base URL of the **UI**, `ftms-stag.fpt.net`)
- `autodata.api.*` — direct-API path (base URL of the **backend**, `ftisapi-stag.fpt.net/bms-service/api`) — different host, don't confuse the two.

## Extending

New Selenium generator: follow `OpportunityStep1Generator` — extend
`AbstractGenerator`, inject `FtmsAutomation`, call through a `pages.*` object.

New API generator: follow `OpportunityApiGenerator` — implement `Generator`
directly (no `FtmsAutomation` needed), inject `AutodataProperties` for the
base URL/timeout, build the payload with a `LinkedHashMap`/Jackson
(not string concatenation — a hand-built JSON string is how the bash
prototype in `bms-opportunity-testdata/` had to work around quoting, Java
doesn't need that workaround). Document the payload in `api-contracts.md`
before or alongside the code.
