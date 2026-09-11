# Auto-Create DATA · Backend

Production-grade fullstack wrapper around the existing FTMS Selenium tool.

```
backend/
└── src/main/java/com/fpt/autodata/
    ├── AutoDataApplication.java
    ├── config/                CORS, WebDriver props
    ├── controller/            REST endpoints + global error handler
    ├── service/               GeneratorRegistry, GenerationService, automation/
    ├── generator/             Strategy interface + DTOs
    │   └── impl/              One @Component per generator (auto-discovered)
    └── model/                 Request/response DTOs
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

- **Strategy + factory.** Every data type is a `Generator` implementation. `GeneratorRegistry` collects all `@Component`-annotated `Generator` beans into a `Map<String, Generator>` keyed by `type()`. To add a new generator, drop a class in `generator/impl/` — no other wiring.
- **Self-describing catalog.** Each generator publishes its parameter list via `ParamSpec` (text, password, number, enum, multi-enum, boolean). The frontend renders form fields purely from this metadata, so backend changes propagate automatically — zero hardcoded UI.
- **Layered.** `controller → service → generator → automation facade → page objects`. Each layer has one job.
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

Naming convention: class `PascalCaseGenerator` in `generator/impl/`, `type()`
is a lowercase dotted key `domain.action` (e.g. `opportunity.step1`,
`auth.get-token`), grouped under a `category()` for the UI (e.g.
`Authentication`, `Opportunity`, `IDC`).

1. Create `src/main/java/com/fpt/autodata/generator/impl/MyThingGenerator.java`:

```java
@Component
public class MyThingGenerator extends AbstractGenerator {
    public MyThingGenerator(FtmsAutomation a) { super(a); }
    @Override public String type()        { return "my.thing"; }
    @Override public String displayName() { return "My Thing"; }
    @Override public String category()    { return "Custom"; }
    @Override public List<ParamSpec> params() {
        return List.of(ParamSpec.text("foo").required().defaultValue("bar"));
    }
    @Override public GenerationResult generate(Map<String, Object> p) {
        String foo = Params.requireString(p, "foo");
        return result().put("foo", foo).build();
    }
}
```

2. Restart backend. The card appears in the UI automatically — no frontend changes needed.

Two implementation patterns coexist — see
[`memory/architecture.md`](memory/architecture.md) before choosing one:
- **Selenium generator** (most existing ones): extend `AbstractGenerator`, inject `FtmsAutomation`, drive a `pages.*` Page Object.
- **Direct-API generator** (e.g. `OpportunityApiGenerator`): implement `Generator` directly, no browser, one HTTP call via `java.net.http.HttpClient`. Only where the real BMS REST contract is confirmed — document it in [`memory/api-contracts.md`](memory/api-contracts.md).

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
