# Auto-Create DATA · Backend

A Spring Boot tool that generates/drives test data end-to-end through FPT's
internal BMS/IVOICE/CM staging systems — built to replace slow, flaky manual
QA data setup.

**Approach evolved mid-project, and the code is organized to show that
evolution, not hide it:** it started as pure Selenium (click through every
wizard step like a human tester), which worked but was slow and brittle
(every UI change broke a locator). Once the real REST contracts behind each
screen were reverse-engineered (captured from DevTools, documented in
[`memory/api-contracts.md`](memory/api-contracts.md)), most flows were
rewritten as direct `HttpClient` calls — Selenium only remains where it's
genuinely unavoidable: the login/OTP step, which only a real browser session
can complete, used solely to mint a token/cookie that the direct-API calls
then reuse.

```
backend/
└── src/main/java/com/fpt/autodata/
    ├── AutoDataApplication.java
    ├── config/                CORS, WebDriver props
    ├── controller/            REST endpoints + global error handler
    ├── service/               GeneratorRegistry, GenerationService, automation/
    ├── generator/             Strategy interface + DTOs
    │   ├── selenium/          Original approach: full browser automation (one @Component per wizard step/flow), drives pages/*.java Page Objects
    │   ├── auth/              Bridge layer: Selenium ONLY for the login/OTP wall — mints a bearer token / cookie jar, nothing else
    │   └── api/               Current approach: pure HttpClient, no browser — direct calls against the reverse-engineered REST contracts
    └── model/                 Request/response DTOs
├── src/main/java/pages/       Selenium Page Objects (used by generator/selenium + generator/auth only)
└── src/main/resources/static/ Frontend: HTML + Bootstrap 5 + Vanilla JS
    ├── index.html
    ├── styles/main.css
    └── js/{api,ui,app}.js
```

See [`memory/MEMORY.md`](memory/MEMORY.md) for the project's own dev knowledge
base (architecture decisions, confirmed API contracts, known issues) — read
that before extending the tool.

---

## Architecture in 30 seconds

- **Strategy + factory.** Every data type is a `Generator` implementation. `GeneratorRegistry` collects all `@Component`-annotated `Generator` beans into a `Map<String, Generator>` keyed by `type()`. To add a new generator, drop a class in the right `generator/` sub-package — no other wiring.
- **Three generator sub-packages, one evolution story:**
  - `generator/selenium/` — the original click-through-the-UI approach (extends `AbstractGenerator`, injects `FtmsAutomation`, drives a `pages.*` Page Object).
  - `generator/auth/` — also Selenium-backed, but scoped to *only* the login/OTP step; its output (a token or cookie jar) is what makes `generator/api/` possible.
  - `generator/api/` — the current approach for every flow whose real REST contract is confirmed: implements `Generator` directly, one or more `java.net.http.HttpClient` calls, no browser at all.
- **Self-describing catalog.** Each generator publishes its parameter list via `ParamSpec` (text, password, number, enum, multi-enum, boolean). The frontend renders form fields purely from this metadata, so backend changes propagate automatically — zero hardcoded UI.
- **Layered.** `controller → service → generator → (automation facade + page objects) | (HttpClient)`. Each layer has one job.
- **Stable error envelope.** `GlobalExceptionHandler` maps every exception to `ApiError {timestamp, status, error, message, path}`.

---

## REST API

| Method | Path | Body | Returns |
|---|---|---|---|
| `GET`  | `/api/health`     | — | `{ status, generators }` |
| `GET`  | `/api/generators` | — | `[{ type, displayName, category, params, ... }]` |
| `GET`  | `/api/services`   | — | Service-group/type catalog (`Services.json`) |
| `GET`  | `/api/proxy/**`   | — | Server-side proxy to the real BMS API (forwards bearer token) |
| `POST` | `/api/generate`   | `{ type, params }` | `{ result: { type, data, log, durationMs, ranAt } }` |
| `GET`  | `/api/log`        | — | Recent audit log entries (`?n=50`) |

Example:

```bash
curl -X POST http://localhost:8080/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"type":"login","params":{"username":"sale@fpt.com","password":"..."}}'
```

---

## Run the backend

Requires JDK 17+, Maven 3.9+, and (for the production Selenium path) Chrome installed locally.

```bash
mvn spring-boot:run
# → http://localhost:8080
```

Override config via env / CLI:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--autodata.webdriver.headless=false --server.port=9090"
```

Build a fat JAR:

```bash
mvn clean package
java -jar target/auto-create-data-backend-1.0.0.jar
```

The frontend is served automatically from `src/main/resources/static/` —
visit `http://localhost:8080` directly, no separate frontend server needed.

---

## Adding a new generator

Naming convention: class `PascalCaseGenerator`, `type()` is a lowercase
dotted key `domain.action` (e.g. `opportunity.step1`, `auth.get-token`),
grouped under a `category()` for the UI (e.g. `Authentication`,
`Opportunity`, `IDC`).

**Pick the sub-package that matches the real contract, not habit:**

- **`generator/api/`** — prefer this. If the real BMS/IVOICE/CM REST contract behind a screen has been confirmed (captured via DevTools, documented in [`memory/api-contracts.md`](memory/api-contracts.md)), implement `Generator` directly and call it with `java.net.http.HttpClient`. No browser, seconds instead of ~10-15s per step.

  ```java
  @Component
  public class MyThingGenerator implements Generator {
      @Override public String type()        { return "my.thing"; }
      @Override public String displayName() { return "My Thing"; }
      @Override public String category()    { return "Custom"; }
      @Override public List<ParamSpec> params() {
          return List.of(ParamSpec.text("foo").required().defaultValue("bar"));
      }
      @Override public GenerationResult generate(Map<String, Object> p) {
          // build request, HttpClient.send(...), parse response
      }
  }
  ```

- **`generator/selenium/`** — only when no REST contract is known yet (or the screen genuinely can't be reverse-engineered). Extend `AbstractGenerator`, inject `FtmsAutomation`, drive a `pages.*` Page Object.

- **`generator/auth/`** — only for login/OTP-wall generators whose entire purpose is minting a token/cookie for `generator/api/` callers to reuse. Also extends `AbstractGenerator`.

Restart the backend and the card appears in the UI automatically — no
frontend changes needed, since the UI renders purely from each generator's
`ParamSpec` list.

---

## Run the frontend

Nothing separate to run — it's bundled as Spring static resources
(`src/main/resources/static/`) and served by the backend itself at
`http://localhost:8080`.

---

## Notes for production

- **Chrome / driver.** `WebDriverFactory` uses `WebDriverManager` which downloads a matching driver on startup. In a sandboxed deploy, mount Chrome into the image (e.g. `selenium/standalone-chrome`).
- **Concurrency.** Each request creates and tears down its own driver. For session continuity (login + downstream actions over multiple HTTP calls), introduce a `SessionManager` keyed by a `sessionId` request param.
- **Secrets.** ⚠️ See [`memory/known-issues.md`](memory/known-issues.md) — reviewer credentials currently live in `application.yml` in plaintext and need rotating + moving to environment variables before any wider access to this repo.
- **CORS.** Allowed origins are listed in `autodata.cors.allowed-origins`. Add your prod frontend origin there.
- **Observability.** Method-level logs use SLF4J (`com.fpt.autodata` namespace). Wire to your aggregator (Loki / Datadog / etc.).
