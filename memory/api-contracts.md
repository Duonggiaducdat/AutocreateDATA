# API contract: Create Opportunity

**Endpoint:** `POST {api.baseUrl}/opportunities`
Default `api.baseUrl` = `https://ftisapi-stag.fpt.net/bms-service/api` (STG). Different host
from the UI (`ftms-stag.fpt.net`) — the UI calls this API under the hood.

**Auth:** `Authorization: Bearer <token>` — a JWT obtained by logging into the BMS UI.
No token-issuing endpoint has been found/used yet; `OpportunityApiGenerator` requires the
caller to paste one (e.g. from a logged-in browser's DevTools Network tab).
⚠️ Tokens are short-lived (the one this contract was captured from expired same-day) — expect
to re-paste one per session, don't try to cache it long-term.

**Sources this was built from:**
1. A real `curl` captured from a logged-in STG session (2026-08-25).
2. Cross-checked live against the actual "Tạo cơ hội" UI (3-step wizard) via browser automation the same day — confirmed field meanings, the approval step's 4 sub-fields, and the `priority` dropdown labels.
3. `bms-v2.0/02_analyze-requirements/v2.0/opportunity/test_data_catalog.md` (separate QA project, same BMS system) — has additional boundary values pulled from the URD spec doc (max lengths, thresholds) not yet cross-checked against this API directly.

## Request body

```json
{
  "customerId": "FTI260088",
  "leadId": null,
  "leadDescription": null,
  "opportunity": {
    "name": "...",
    "description": "...",
    "expectedRevenue": 100000000,
    "expectedRevenuePeriod": 1,
    "expectedBudget": 50000000,
    "successRate": 50,
    "endDate": "2026-12-31",
    "priority": 2,
    "numberRack": null,
    "services": [
      { "serviceGroup": 7000, "serviceType": 7001, "servicePackCode": null }
    ],
    "documents": null
  },
  "approval": {
    "saleName": "ThiTLH",
    "tppCanApproval": ["AnhDTT12"],
    "usersCanApproval": ["Huyennhk"],
    "userReceiveNotification": null,
    "campaignId": null
  }
}
```

## Field reference

| Field | Confirmed? | Notes |
|---|---|---|
| `customerId` | ✅ confirmed | Existing customer's code (e.g. `FTI260088`). Invalid/unknown code → 4xx in the curl-tool test, not yet re-verified through this Java path. |
| `leadId`, `leadDescription` | ✅ confirmed nullable | Only used when the opportunity originates from a Lead. |
| `opportunity.name` | ✅ confirmed required | UI: "Tên cơ hội". `test_data_catalog.md` says ≤200 chars, 201 blocked — not yet re-verified against this API directly. |
| `opportunity.description` | ✅ confirmed required | UI: "Mô tả cơ hội". Same 200-char boundary claim as above. |
| `opportunity.expectedRevenue` | ✅ confirmed required | UI: "Doanh thu kỳ vọng (Chưa VAT)", VNĐ. |
| `opportunity.expectedRevenuePeriod` | ⚠️ UNCONFIRMED enum | UI shows a period dropdown next to revenue (default "Năm" = Year). Numeric code for "Năm" not confirmed — `1` is a guess carried over from the original curl capture. |
| `opportunity.expectedBudget` | ✅ confirmed, optional | UI: "Ngân sách dự kiến". |
| `opportunity.successRate` | ✅ confirmed required | UI: "Khả năng thành công" (%). `test_data_catalog.md` claims valid range is 0-99 (100 blocked) — differs from what the curl-tool prototype assumed (0-100); **not yet re-verified against the live API**, treat as open question. |
| `opportunity.endDate` | ✅ confirmed required | Format `yyyy-MM-dd`. UI: "Dự kiến kết thúc", must be ≥ today. |
| `opportunity.priority` | ✅ **confirmed** (2026-08-25) | `Cao=1 (HIGH), Trung bình=2 (MEDIUM), Thấp=3 (LOW)` — read directly from the frontend's own source chunk `assets/priority.<hash>.js` (`https://ftms-stag.fpt.net/bmsv2/assets/priority.DYSg-8DH.js`, hash will change on redeploy — find it fresh via `document.scripts`/`performance.getEntriesByType('resource')` on the create-opportunity page, then GET the chunk — it's a static asset, no auth needed to read it). Matches what `OpportunityApiGenerator.PRIORITY_CODES` already used. |
| `opportunity.numberRack` | ✅ confirmed nullable | Only relevant when a Rack service is selected. |
| `opportunity.services[].serviceGroup` / `.serviceType` | ✅ **confirmed** (2026-08-25) | `GET /api/master-data/service-types/groups` returns a **flat list of service-type rows**, each carrying its own group id/name inline (not a nested tree despite the endpoint name): `{ data: [ { id, serviceTypeName, serviceGroupId, serviceGroupName, servicePackCode, isActive }, ... ] }`. Group client-side by `serviceGroupId`. `isActive:0` rows exist (e.g. a disabled "Voice Brandname") and should be filtered out. Real sample pairs seen: `serviceGroupId:2000 "Cloud partner"` → `id:2004 "Google Workspace"`, `id:2005 "Google Cloud Platform"`; `serviceGroupId:6000 "Điện toán đám mây"` → `id:6010 "AWS-Billing"`, `id:6011 "Fshare"`, `id:6012 "FStorage"`; `serviceGroupId:8000 "Trung tâm dữ liệu"` → `id:8005 "CDN"`; `serviceGroupId:5000 "Dịch vụ phần mềm(SaaS)"` → `id:5003`, `id:5004 "UBot Invoice"`; `serviceGroupId:9000 "Thiết bị"` → `id:9002`, `id:9003`. **Do not assume `7000`/`7001` (the original curl's values) map to any of the above** — that curl predates this confirmation and may be a different/unlisted group. Implemented in `app.js normalizeServiceCatalog()`. `Catalog.java`'s `SERVICE_GROUPS`/`SERVICE_TYPES` lists (Trung tâm dữ liệu, Internet, Cloud, ...) are from a *different, older* taxonomy used by the Selenium `CreatePage` — do not conflate the two. |
| AM (`approval.saleName`) lookup | ✅ **confirmed** (2026-08-26) | `GET /api/bam/sales` — found by walking the real "Tạo cơ hội" wizard step 3 as a non-Sale account (`chuyennt5@fpt.com`) and reading its network calls. Returns the **full company-wide Sale directory** (~1131 rows): `{saleId, userName, fullName, email, phone, divisionCode/Name, departmentCode/Name, positionCode/Name, titleCode/Name, areaCode/Name}`. **Server-side role-gated**: called with a plain Sale's own token (e.g. `thitlh`), returns `{"data":[],"success":true}` — empty, not an error — matching the business rule that a Sale IS their own AM and doesn't need to browse the directory; only non-Sale callers (managers, TPP, BGĐ, ops accounts, etc.) get real rows back. `normalizeFlatList` (already used for TPP/BGĐ) handles this shape as-is via its `userName`/`fullName` fallback chain, no new normalizer needed. Wired into `app.js` as a native `<datalist>` on the AM field (kept the field a plain text input rather than restructuring into a full async-picker UI) — see "Sale phụ trách auto-detect" below. Separately observed but NOT this lookup: `GET /api/bam/employees/managers?email=X` — fires alongside `/bam/sales` on the same step, appears to be a narrower self/manager lookup, not the general AM directory (not used). |
| TPP (`approval.tppCanApproval`) lookup | ✅ **confirmed** (2026-08-25) | `GET /api/bam/employees/{amEmail}/department-managers` — keyed by the **AM's email** (not `cdCode` as first guessed), e.g. `ThiTLH@fpt.com`. Confirmed by watching the real "Chọn TPP đánh giá" dropdown's network call live. Real options seen for that AM: `AnhDTT12, Dattq6, Dungntm10, HungPT38, tampnh3, "Trường Phòng TTKD DCSMN1"`. Item field shape (label vs id) not yet confirmed — `normalizeFlatList`'s fallback chain handles it defensively; update once seen. Implemented in `app.js mountOpportunityPickers()` via `bam/employees/${encodeURIComponent(email)}/department-managers`. |
| BGĐ (`approval.usersCanApproval`) lookup | ✅ **confirmed** (2026-08-25) | `GET /api/bam/employees/{amEmail}/central-managers` — same keying as TPP above. Real options seen: `anhdc4manager, Duyvq4, Huyennhk, KhoaTD19, Linhntt118, NgocHBM, NhuDTH5, nhunglh20, ...` (matches `Huyennhk`, the default `usersCanApproval` value already used elsewhere in this project — good cross-check). Implemented the same way as TPP above, `central-managers` instead of `department-managers`. |
| `opportunity.services[].servicePackCode` | ✅ confirmed nullable | Not yet seen populated in any captured example. |
| `opportunity.documents` | ✅ confirmed nullable | File attachments — likely a separate upload endpoint + reference here, not yet captured. |
| `approval.saleName` | ✅ confirmed required | = **AM** (Account Manager) in the UI's step 3 ("Chọn người quản lý phụ trách trực tiếp cơ hội"). Username, not display name (e.g. `ThiTLH`, `ducnta`). |
| `approval.tppCanApproval` | ✅ confirmed required, array | = **TPP đánh giá** in UI step 3 ("Chọn Trưởng/Phó phòng thực hiện đánh giá sơ bộ"). `AdvanceStageGenerator`/`OpportunityStep3Generator` call the equivalent field `truongPhong` (single value); this API takes an array — unclear if the UI ever submits >1, treat single-element array as the safe default. |
| `approval.usersCanApproval` | ✅ confirmed required, array | = **BGĐ phê duyệt** in UI step 3 ("Chọn Ban Giám đốc (BGĐ) trung tâm của AM"). `OpportunityStep3Generator` calls the equivalent field `banGiamDoc`. Live UI test used `Huyennhk` here — matches the default already in `OpportunityStep3Generator.params()`, good cross-check. |
| `approval.userReceiveNotification` | ✅ confirmed optional | = **Gửi thông báo** in UI step 3 — the only one of the 4 approval sub-fields without a required asterisk. |
| `approval.campaignId` | ✅ confirmed nullable | Not yet seen populated in any captured example. |

## Response

Not yet fully captured (the STG environment wasn't reachable from the session
that wrote `OpportunityApiGenerator` — see `known-issues.md` if this keeps
happening). `OpportunityApiGenerator` returns whatever JSON body the server
sends back as `responseBody` in its result — update this section once a real
success response has been seen, including the created opportunity's ID field
name.

## API contract: Evaluate Opportunity (Đánh giá — TPP step)

**Endpoint:** `PUT {api.baseUrl}/evaluation-opportunities/evaluate` — confirmed 2026-08-25 from a real captured request (user-supplied curl, evaluating `CH00085266`).

```json
{
  "opportunityId": "CH00085266",
  "note": "...",
  "criteria": [
    { "criteriaId": 2, "id": 0, "criteria": "Nhu cầu", "requirement": "...", "description": "...", "evaluation": 1, "note": null },
    { "criteriaId": 3, "id": 0, "criteria": "Thẩm quyền", "requirement": "...", "description": "...", "evaluation": 1, "note": null }
  ]
}
```

| Field | Confirmed? | Notes |
|---|---|---|
| `opportunityId` | ✅ | the opportunity code, e.g. `CH00085266` |
| `note` | ✅ required | overall evaluation note (the modal's bottom "Ghi chú", max 500 chars) |
| `criteria[].criteriaId` | ✅ **source endpoint confirmed** (2026-08-25) | `GET {api.baseUrl}/evaluation-opportunities/criteria?opportunityId=X` returns the dynamic per-opportunity criteria list to fill the modal table: `{"data":{"id":<evalRecordId>,"tppApproveDate":null,"note":null,"evaluations":[{"id":49693,"criteriaId":0,"criteria":"Nhu cầu","description":"...","requirement":"...","note":null,"evaluation":null}, ...]}}`. **The GET response's `evaluations[].id` is what must be sent back as `criteria[].criteriaId` in the PUT evaluate payload** — confirmed by a full live round-trip (create → GET criteria → PUT evaluate, all 200/success) on opportunity `CH00085280`. Do NOT use the GET's `criteriaId` field (always `0`) — that's a decoy/unused field on the GET side. |
| `criteria[].criteria` / `.requirement` / `.description` | ✅ | display text, echoed back in the submit payload (Vietnamese, verbatim from whatever the GET returned) |
| `criteria[].id` | ⚠️ always `0` in the one sample seen | Purpose unclear — possibly an evaluation-record id (0 = not yet evaluated) rather than a criteria id. Don't over-interpret from a single sample. |
| `criteria[].evaluation` | ⚠️ `1` = "Đạt" (Pass) confirmed by this sample (all 4 were left at their default "Đạt" selection) | The modal's dropdown also offers "Không đạt" (Fail) — its numeric code is **unconfirmed** (likely `0` or `2`, not verified). |
| `criteria[].note` | ✅ nullable | per-criterion note (the modal's per-row "Ghi chú" column, separate from the overall `note` above) |

**Result of a successful evaluate:** toast "Đánh giá cơ hội thành công"; page then exposes **Phê duyệt / Từ chối** buttons — confirmed live this only happens when `expectedRevenue ≥ 300,000,000` (BR03, see the opportunity module's own `test_data_catalog.md` in the QA project `bms-v2.0` for the doc citation). Below that threshold, evaluate alone likely completes the flow (not yet verified directly — inferred from BR03's wording).

## API contract: Approve Opportunity (Phê duyệt — BGĐ step) — ✅ CONFIRMED 2026-08-25

**Endpoint:** `PUT {api.baseUrl}/evaluation-opportunities/bgd-approve` — found by guessing (the `approval-opportunities/*` naming family used earlier all 404'd; this lives under the SAME `evaluation-opportunities` module as the evaluate step, just a different action verb). Confirmed 3 ways: (1) `OPTIONS` → `405` + `Allow: PUT` header (vs `404` for a garbage sibling path, ruling out a path-variable catch-all false-positive — `evaluation-opportunities` has no catch-all, unlike plain `opportunities/{code}` which does and gave several misleading 405s during the hunt); (2) an empty-body `PUT` returned a Spring validation error naming the real fields (`REASON`, `OPPORTUNITYID`); (3) a full live round-trip (create → evaluate → bgd-approve, all `success:true`) on a fresh ≥300tr opportunity `CH00085280`.

```json
{
  "opportunityId": "CH00085299",
  "bgdApprovalStatus": 3,
  "reason": "..."
}
```

| Field | Confirmed? | Notes |
|---|---|---|
| `opportunityId` | ✅ | the opportunity code |
| `bgdApprovalStatus` | ⚠️ **CRITICAL, was missing — see bug below** | `3` = Approve, `4` = Reject (confirmed from `ApproveNewOppModal.*.js`: `const et=3,it=4; ... b = decision==='approve' ? et : it`). |
| `reason` | ✅ required | free-text — maps to the UI modal's single "Ý kiến" field. Max length not yet probed. |

Response shape matches evaluate's: `{"data":{"isDuplicated":false,"duplicatedOppt":null},"success":true,"errors":[],...}`. Calling it on an opportunity already approved returns a `BUSINESS_ERROR`: `"Cơ hội đã được phê duyệt đánh giá"`.

**🐛 BUG found and fixed 2026-08-25 — omitting `bgdApprovalStatus` silently defaults to REJECT, not approve.** The original version of `ApproveOpportunityGenerator` sent only `{opportunityId, reason}` (this field hadn't been discovered yet). The API accepted that shape with `HTTP 200 success:true` — no validation error — but silently treated it as a **reject**. Confirmed by reading the opportunity's own "Thảo luận/Hoạt động" activity feed on its detail page: the entry read **"Đã từ chối đánh giá cơ hội. Ý kiến: Phê duyệt tự động qua tool"** (rejected, with the generator's own default reason text) — not "Đã phê duyệt". This is why a user report ("BGĐ chưa duyệt thành công") turned out to be a **real bug**, not the earlier stale-cache false alarm (see below). Root cause found by fetching `ApproveNewOppModal.*.js` (loaded on `/bmsv2/approval-opportunity/detail?opportunityId=X`) and reading the real modal's submit handler — it wraps the call as `h({bgdApproveEvaluationRequest: {opportunityId, bgdApprovalStatus, reason}})`; by the same pattern as the stage-update mutation (outer object name is just the JS call-site arg, RTK Query unwraps it), the actual wire body is the **flat inner object**, confirmed by testing: wrapping it under `bgdApproveEvaluationRequest` in the literal POST body 400s with the same "REASON/OPPORTUNITYID missing" errors as an empty body. Fix: send flat `{opportunityId, bgdApprovalStatus: 3, reason}`. Re-verified live end-to-end after the fix on `CH00085295`/`CH00085299` — `GET /opportunities/pending-approval?SearchKey=X` now shows `"bgdApprovalStatus":3` with a real timestamp, and the opportunity fully clears from the pending-approval queue.

Only meaningful/reachable when `expectedRevenue ≥ 300,000,000` (BR03) AND the opportunity has already been evaluated (`evaluate` called first) — calling it before evaluate is untested, assume it will business-error too.

Untested: whether `4` (reject) round-trips correctly through the same endpoint with the fixed flat shape — not yet needed for the current feature, which only ever sends `3`.

**Separate, unrelated finding — do not confuse with the bug above:** earlier the same day, a first report of "still shows as chờ duyệt" on `CH00085290` turned out to be a genuinely stale/cached browser tab (the approve call *had* worked, pre-bugfix, on that specific call) — confirmed by opening `/bmsv2/approval-opportunity/detail?opportunityId=X` fresh and seeing "Hệ thống đã: Phê duyệt", then seeing the pending-approval list show 0 results after a real reload. **Also separately:** an opportunity can stay in "Cơ hội chờ duyệt" for a completely different reason — `requestTypeName:"Yêu cầu tạo cơ hội"` (a **duplicate-opportunity review** flag, unrelated to evaluate/approve) — triggered by BMS's own dedup logic when creating multiple similar test opportunities for the same customer+service combo in a short time. Visible in the UI as a "Xem xét cơ hội trùng" badge and a "Cơ hội trùng" tab on that opportunity's approval-detail page. Not a bug; just use varied service codes across rapid test runs against the same test customer to avoid tripping it.

## Login flow — ✅ fully automated (2026-08-25), cookie renamed 2026-09-03

Implemented in `GetTokenGenerator.java` (`auth.get-token`, category Authentication). Recipe, confirmed working end-to-end against a real business endpoint:

1. Run the existing `pages.LoginPage.login(username, password)` (Selenium) — no changes needed to that class. On STG, `password` = `ISC22QC`, a **fixed OTP-bypass code** (reached via "Chọn phương thức xác thực khác" → "Sử dụng mật khẩu một lần (OTP)" trên login screen) — same value already hardcoded as `autodata.defaults.reviewer-password` in `application.yml`. Confirmed working for `huyennhk@fpt.com`, and (2026-08-25) also for `AnhDTT12@fpt.com` (a TPP-role account, `PositionCode:"TP"` per its JWT) — at least 3 accounts across at least 2 different roles (BGĐ + TPP) share it, strongly suggesting it's a **global STG OTP-bypass code**, not per-account. Still worth a fallback path if a future account rejects it.
2. `driver.get("https://ftms-stag.fpt.net/bmsv2")` (plain URL, **no sub-path**) — this lets the bmsv2 SPA run its own login bootstrap using the live CAS session cookie from step 1.
3. Poll for a cookie holding the token to appear. Read its value directly — that *is* the bearer token, no further exchange needed.

**⚠️ 2026-09-03: the cookie name changed.** Originally this was `access_token` (confirmed 2026-08-25). After a site redeploy, `access_token` got repurposed to hold only generic identity claims (name/nameidentifier/sid — the same shape `access_token_sso` always had, see below), and the full business token moved to a **new cookie, `BMS_access_token`**. Found by decoding every candidate cookie's JWT payload (keys only) side-by-side in a real logged-in session — `BMS_access_token` has `DepartmentCode`/`DivisionCode`/`UserId`/`UserName`/the role claim, plain `access_token` no longer does. Symptom that led here: `auth.get-token` timed out for `thitlh@fpt.com` even though the Selenium browser was visibly, successfully logged in (screenshot showed the real dashboard) — login itself was never the problem, only the cookie lookup. **Fixed**: `GetTokenGenerator` now tries `BMS_access_token` first, falls back to `access_token`, in `TOKEN_COOKIE_CANDIDATES`. If this breaks again, repeat the same decode-every-cookie sweep rather than guessing a name. Also fixed in the same pass: the timeout exception used to get thrown *after* the driver already quit, so `screenshot-on-error` never captured anything on this exact failure path — moved the throw inside the driver lambda so future failures leave a real screenshot in `backend/screenshots/`.

**The `BMS_access_token` cookie is the one to use for all real business calls** (`ftisapi-stag.fpt.net/bms-service/api/*` — opportunities, master-data, bam/employees, etc.) — verified live: `GET .../accounts/permissions` with this token → 200 with real data (confirmed both via the app and via a raw `curl` the user captured from real browser DevTools).

### Dead ends ruled out along the way (don't retry these)

- `localStorage` / `sessionStorage` — never held the token, on either the legacy `/bms/OppsMagt` app or bmsv2.
- **`FTIBMS-Auth` cookie** — an **ASP.NET Core Data Protection–encrypted blob** (`CfDJ...` prefix is the tell). Not a JWT, not usable as a bearer token, only the server can decrypt it.
- **`access_token_sso` cookie / `POST /api/bam/auth/sso` ticket exchange** — real JWT (`iss`/`aud` = `"FTMS"`), but this is a **BAM identity-layer token only**. Works for `ftms-stag.fpt.net/api/bam/*` (e.g. `GET /api/bam/accounts/me`, `GET /api/bam/accounts/permissions` — both 200), but **401s against the real business API**. Two separate backends, two separate token types — don't conflate them.
- `GET /api/bam/accounts/permissions` (with or without an `?appCode=` query param — tried several guesses, all ignored) — always returns `{"appCode":"FTIBAM","permissions":[]}`, no token anywhere in body or response headers (checked both).
- Response headers generally — checked `me`/`permissions`/`sso` responses for a hidden token in headers (`Set-Cookie` etc.) — only ever an `INGRESSCOOKIE` (load-balancer routing cookie, irrelevant).
- A network-request literally labeled `"jwt"` in Chrome DevTools' request list turned out to have the exact same Request URL as `"me"` — almost certainly just a static JS asset (a JWT-decode library chunk) coincidentally sorted next to it, not a distinct endpoint.

### Real token's decoded claims (for reference — matches what real API calls need)

```json
{
  ".../nameidentifier": "19226",
  ".../emailaddress": "huyennhk@fpt.com",
  ".../role": "FTI-SAL CENTER MANAGER",
  "UserId": "19226", "UserName": "huyennhk", "FullName": "HuyenNHK",
  "PositionCode": "GDTT", "DivisionCode": "FTIDCSMN",
  "DivisionName": "DCS - Trung tâm Giải pháp Truyền dẫn số liệu (MN)",
  "ManagedDivisions": "FTIDCSMN,FTIODC",
  "InsideToken": "ST-...", "exp": 1787651863,
  "iss": "asdfakshferkjhaskfjhdskas232423fhdsakjfskaskdsakdsalkjsadkjsadkjsakjasd",
  "aud": "234dsfsdg2342dfgd342342314123dfgdsfgdfasdf23423423423asfsasg"
}
```
The garbled `iss`/`aud` strings are the fingerprint for "this is the real business token" — if a token you're inspecting has `iss`/`aud` = `"FTMS"` instead, it's the wrong (BAM-only) one.

## Feature: auto Đánh giá + Phê duyệt checkboxes (2026-08-25) — ✅ implemented

Two checkboxes on the `opportunity.api` card ("Tự động Đánh giá (TPP) sau khi tạo" / "Tự động Phê duyệt (BGĐ) nếu doanh thu ≥ 300 triệu"), both default ON. On a successful creation, `app.js runPostCreateFlow()` chains the two new generators:

- `evaluation.evaluate` (`EvaluateOpportunityGenerator.java`) — GETs the live criteria list, marks everything "Đạt", PUTs evaluate.
- `evaluation.approve` (`ApproveOpportunityGenerator.java`) — PUTs `bgd-approve`. Skipped silently (not an error) when `expectedRevenue < 300,000,000`, or when evaluate was requested but failed.

**Role separation (important, added after initial version — user flagged that reusing one token for every step was wrong):** each step logs in as its OWN role instead of reusing the creator's (Sale/AM) token — this only matters for realistic RBAC testing, since the BMS UI itself would never let one person do all three steps.
- Đánh giá logs in as the **first person picked in the "TPP đánh giá" chip list** (`tppCanApproval`).
- Phê duyệt logs in as the **first person picked in "BGĐ phê duyệt"** (`usersCanApproval`).
- Both via a fresh `auth.get-token` call (real Selenium login, ~10-15s each) with the shared OTP-bypass password, same as the manual "Lấy Token" button. So the full create→evaluate→approve chain now takes ~30-60s (3 logins total: Sale for creation is already-provided, TPP + BGĐ are fetched fresh), not seconds.
- Verified live end-to-end 2026-08-25 on `CH00085282`: created with Sale token, evaluated with a freshly-logged-in `AnhDTT12@fpt.com` (TPP, `PositionCode:"TP"`) token, approved with a freshly-logged-in `Huyennhk@fpt.com` (BGĐ) token — all 3 separate real logins, all API calls `success:true`.
- Untested edge case: TPP/BGĐ picker left empty (no one selected) — `runPostCreateFlow` shows an error line ("Chưa chọn TPP/BGĐ — không có tài khoản để đăng nhập") and skips that step; not yet exercised against a real empty-picker run.
- **Token caching added 2026-08-25** (user asked, after noticing every run re-logs in and wastes ~10-15s/step even seconds apart): a decoded real JWT's `exp` claim showed ~63 min validity from login. `app.js` now keeps an in-memory `Map` (`tokenCache`, keyed by login email) with a 50-min TTL (safety margin under the real ~63min) — `loginAs()` reuses a cached token if still fresh instead of calling `auth.get-token` again. Cache is per-tab/in-memory only, cleared on page reload (deliberate — short TTL doesn't justify persisting it).
- **Checkbox behavior corrected 2026-08-25**: user pointed out that after ticking "ghi chú Vòng đời cơ hội", the note appeared on all 5 stages but the opportunity's actual progress/stage never moved (screenshot showed still 30%, stuck at Giai đoạn 1). That was correct-as-designed for `opportunity.stage-note` (an in-place note/date edit, deliberately doesn't touch progress — see contract above) — but not what the user wanted. Confirmed via AskUserQuestion: switch to a REAL stage transition. The checkbox (now labeled "Tự động chuyển hết Vòng đời cơ hội (tiến độ 100%)") calls the new `opportunity.stage-advance` generator instead of `opportunity.stage-note`.

## API contract: Advance Opportunity Stages ("Chuyển giai đoạn", real transition) — ✅ CONFIRMED 2026-08-25

**Endpoint:** `POST {api.baseUrl}/opportunities/{opportunityId}/stages/{targetId}/change` — found the same way as the stage-note endpoint, reading the RTK Query API slice in `index.0lrqjQK8.js`. `{targetId}` in the URL is the TARGET stage row's own `id` (from `GET .../stages`, same `id` field `StageNoteGenerator` uses — NOT the 1-6 `stageId` enum).

```json
{
  "opportunityId": "CH00085303",
  "id": 536085,
  "planEndDate": "2026-12-31",
  "note": "..."
}
```

Same field shape as the stage-note PUT (`opportunityId`, `id` = target row id, `planEndDate` date-only, `note`) — only the HTTP method (`POST`) and URL suffix (`/change` vs nothing) differ, plus this one actually advances `isActive`/`actualStartDate`/`actualEndDate` instead of just updating `note`.

**Mechanics confirmed via a real round-trip** on `CH00085303` (implemented as `StageAdvanceGenerator.java`, type `opportunity.stage-advance`): `GET .../stages` to find the currently active row (`isActive == 2`) and the ordered list of remaining rows, then call `/change` once per remaining row **in order** (stage2 → stage4 → stage5 → stage6, i.e. Demo/Tư vấn → Chào giá → Đàm phán → Kết thúc) — each call moves `isActive` forward to that row and closes out the previous one (`actualEndDate` set). After all 4 calls: `GET .../stages` showed **`"percentage":100`**, stages 1-5 all had `actualEndDate` set, only the final "Kết thúc" row was `isActive:2` (current, no `actualEndDate` — expected, it's the last one). No extra "complete" call was needed to reach 100%.

Untested: calling `/change` with a target that ISN'T the immediate next stage (e.g. jump straight from stage 1 to stage 5) — the real UI's dropdown allows picking any non-current, non-completed stage as target, so it's plausible, but not exercised; the generator always walks sequentially through every remaining row.

## API contract: Create Quotation (Báo giá) — ✅ CONFIRMED 2026-08-26

**Endpoint:** `POST {api.baseUrl}/quotation` — **multipart/form-data**, not JSON (a plain JSON POST 415s). Found via the same "read the real bundle" method: `QuotationEndpoints.*.js` gave the full endpoint list (`GET quotation/list`, `GET quotation/services/{opportunityId}`, `GET quotation/progress`, `POST quotation` = create, `PUT quotation` = update, `POST quotation/check-approval-limit`, ...), then the actual FormData-building code was found in the main `index.*.js` chunk by searching for the string `` services[${P}] `` (the template-literal fragment survives minification).

Supporting lookup: `GET {api.baseUrl}/quotation/services/{opportunityId}` → `{"data":{"services":[{"serviceGroupId","serviceTypeId","servicePackCode","serviceName"}, ...]}}` — the opportunity's own service list, one row per service actually attached to it (from creation). This is what the real "Thêm báo giá" form's service dropdown is populated from — one quotation covers ALL of an opportunity's services in one call, priced individually.

**Real multipart fields** (exact names/casing matter — lowercase, unlike the PascalCase validation-error codes which are just error labels, not the field names):

```
opportunityId: "CH00085305"
description: "..."
totalValue: "2"                          — string, must equal the sum of every services[i].value or the API 400s with "Giá trị báo giá không khớp"
services[0].serviceGroup: "10000"        — from the services lookup's serviceGroupId
services[0].serviceType: "10001"         — from serviceTypeId
services[0].servicePackCode: ""          — "" when the lookup's servicePackCode is null (always empty string, never omit the field)
services[0].servicePackId: ""            — ALWAYS empty string in the real UI too — dead field, hardcoded client-side
services[0].value: "1"                   — this service's price
services[1]....                         — repeat per service, 0-indexed
quotationDocuments: <file>               — at least one file attachment is required (multipart file part); the real UI lets you attach several, append the same field name once per file
```

Validation errors seen along the way (from an empty multipart POST): `SERVICES`/`TOTALVALUE`/`DESCRIPTION`/`OPPORTUNITYID`/`QUOTATIONDOCUMENTS` all "Không được để trống" — confirms these 5 are the only hard-required top-level fields.

**Response on success:** `{"data":{"isSuccess":true,"error":null},"success":true,...}`. Confirmed live end-to-end on `CH00085305` (1 service) and `CH00085314` (2 services, `6000:6011,8000:8005`) — `GET quotation/list?OpportunityId=X` afterward showed the real quote row: correct `serviceNames` (joined, human-readable), `totalValue`, `versionNumber:"v1.0"`, and **`status:"Phê duyệt"`** / `approvedBy:"Hệ thống"` (auto-approved by the system) — small `totalValue` (VND `1`-`2` in these tests) stays under whatever auto-approval threshold `check-approval-limit` gates; a large quote might instead land in a pending-BGĐ-approval state (untested — `check-approval-limit` itself wasn't probed, only the create call).

Implemented as `QuotationGenerator.java` (`opportunity.quotation`) — GETs the opportunity's services, prices each at a flat `pricePerService` param (default `1`), attaches the project's existing `file/dummy.pdf` classpath resource (same file the old Selenium `BaoGia`/`QuoteGenerator` used), builds the multipart body by hand (`java.net.http.HttpClient` has no native multipart support), and POSTs. Untested: the `PUT quotation` update-existing-quote path, and what happens above the auto-approval value threshold.

## Feature: Sale phụ trách (AM) auto-detect from token — ✅ implemented 2026-08-26

User request: the AM field should default to whoever is actually logged in via the Bearer Token, if that person is a plain Sale — but stay open for manual choice when the logged-in account is a different role (they'd be creating on behalf of a Sale, not themselves).

- **Role signal:** JWT claim `role` (e.g. `"FTI-SAL SALESMAN"` for a plain Sale vs `"FTI-SAL SALE MANAGER"`/`"FTI-SAL CENTER MANAGER"` for TPP/BGĐ) — checked via `role.toUpperCase().includes('SALESMAN')`. Cross-checked against `PositionCode`: Sale (`thitlh`) = `"NV"` (Nhân viên); TPP (`AnhDTT12`) = `"TP"`; BGĐ (`Huyennhk`) = `"GDTT"` — `role` string is the more precise signal since `PositionCode` alone doesn't distinguish role family.
- **Sale branch:** `app.js` decodes the token client-side (`decodeJwt`/`jwtClaim` helpers, no signature check — UX convenience only), auto-fills the AM field with the JWT's own `UserName` claim, and sets it `readOnly` (not `disabled`, so the value stays visible/copyable) + a `.is-locked` CSS class (grey background) for a visual cue.
- **Non-Sale branch:** unlocks the field (`readOnly = false`) and populates a native `<datalist>` from `GET bam/sales` (see contract above) so the user can autocomplete-select a real AM instead of guessing a username — field stays a plain `<input>`, just gains a `list=` attribute pointing at the shared datalist.
- Re-evaluated on every Bearer Token field blur (`applySaleDefaultFromToken()`, called from the same blur handler that reloads the service/TPP/BGĐ pickers).

## API contract: Approve Hợp đồng/Phụ lục (HĐ/PL) — BMS + CM, 2 hệ thống riêng — ✅ CONFIRMED 2026-08-27

After a quotation is approved and a "registration" (đăng ký triển khai) exists for an opportunity's service, it must clear **two separate approval gates in two separate backends** before it becomes a real contract:

**Step 1 — BMS approve (role BGĐ, same account as `usersCanApproval` on the opportunity):**
`POST {api.baseUrl}/approval-opportunities/approve-registration` — flat body `{registrationId, approvalStatus, reason}`. `registrationId` comes from the opportunity's own `services[].registrationId` (`GET opportunities/{id}`). **`approvalStatus: 1` = Approved** (confirmed two ways: (a) after a real approve click, `bgdApprovalBy`/`bgdApprovalDate` populate and the record shows under status filter `1`; (b) a fresh, never-touched registration starts at `bgdApprovalStatus: 2` = chờ duyệt, and calling this with `approvalStatus:1` flips it to `1` with `bgdApprovalBy` set to the caller). Do NOT confuse this `approvalStatus` with the unrelated evaluation-module `bgdApprovalStatus` enum (3=approve/4=reject there) — same field name, different module, different codes.

Sibling endpoint `POST approval-opportunities/registration-opportunity` `{keyword, approvalStatus, pageIndex, pageSize}` is the **list/search** used by the "danh sách chờ duyệt" screen — NOT an approve action (has pagination params, is queried not mutated). Useful for looking up a registration's current `bgdApprovalStatus`/`registrationId`/`bgdApprovalBy` by `keyword` (accepts the opportunity code or the reg's `refCode`, e.g. `CMR2614586`).

**Step 2 — CM approve (separate app, `ftms-stag.fpt.net/cm/v1/...`, separate auth, e.g. account `truongnv64`):**
The CM app (`FTI-CM`) is a **different SPA with its own gateway** (`gwcm/contract/api/v1/...`, `gwcm/api/...`) and a **different JWT** than the bms-service one (extra claims: `TICKET`, `UID`, `DVC`/`AC`/`DPC` division codes, `role` as an array) — minted via the CM app's own CAS ticket exchange when you log into `https://ftms-stag.fpt.net/cm/`, NOT the same token `auth.get-token`/the `access_token` cookie produces for bms-service calls. **First-time interactive login for an account in a fresh browser profile triggers a real emailed OTP challenge** (no password fallback) — only a browser/profile that has logged into that account before skips it (device-trust cookie). This blocks pure API automation for a never-used account; either warm up the account once interactively, or have a human complete the OTP once per browser profile.

Lookup: `GET gwcm/contract/api/v1/Registration/Get?Page=1&PageSize=20&KeyWord={regCode}` — search by the registration's `refCode` (e.g. `CMR2614586`; searching by the opportunity's `CH...` code does NOT match here, only `regCode` does) → returns the registration including its `id`/transaction GUID. `GET gwcm/contract/api/v1/Registration/GetById?TransactionId={guid}` fetches the same record by GUID directly (this GUID is what the UI calls `CMRTransactionId`, visible in the page URL `/cm/v1/registration/{guid}/card/1`).

Two sequential mutations (both required — the UI's own stepper shows 3 stages: Tạo mới → Tạo HĐ/PL → Hoàn thành):
1. **`POST gwcm/contract/api/v1/Contract/CreateContractCM`** `{CMRTransactionId, createBy}` — button "Tạo hợp đồng & PL", advances Tạo mới→Tạo HĐ/PL. Response includes the real contract/phụ lục numbers (e.g. `SGSE22495`/`SGSE22495-001`).
2. **`PUT gwcm/contract/api/v1/Registration/UpdateStatus`** `{updateby, status, cmrTransactionId}` — button "Gửi yêu cầu triển khai", advances Tạo HĐ/PL→Hoàn thành. **`status: 4` = Hoàn thành/Approved** (confirmed: preceded live by `GET .../Registration/CheckRegistration?transactionId=X&status=4` validation call, and the stepper shows all 3 stages checked immediately after).

Supporting read-only calls the UI fires around these (not required to replicate, just context): `Contract/GetTotalDebtSubContract?saleId=X` and `Registration/GetFirstBillUnPaidByCusCode?customerCode=X` — customer/sale debt checks shown as a warning banner ("vui lòng kiểm tra thông tin nợ cước..."), non-blocking.

**End-to-end verified 2026-08-27 on `CH00084099`** (registrationId `92684`, regCode `CMR2614586`): before = `bgdApprovalStatus:2, cmApprovalStatus:null, contractNo:null`; after both steps = `bgdApprovalStatus:1, cmApprovalStatus:1, contractNo:"SGSE22495"` (re-checked via `GET opportunities/{id}`).

**✅ Fully automated 2026-08-27.** `auth.get-cm-token` solved step 2's token problem — Selenium logging into `/bmsv2` then navigating the SAME session to `/cm/` does NOT re-trigger the OTP wall (OTP is tied to establishing device trust once, not per sub-app); the CM JWT itself isn't in a cookie though, it's in **`localStorage.Auth`** — scan cookies AND local/sessionStorage for anything JWT-shaped, don't assume cookie-only like the bms-service token.

**Identify-by-Mã-YC (regCode) instead of opportunity code — ✅ added 2026-08-27**, per user request ("lấy theo mã YC rồi tự tìm BGĐ rồi tự chạy"): `approval-opportunities/registration-opportunity`'s `keyword` search also matches a **regCode directly** (not just an opportunity code), and the row it returns already carries `usersCanApproval` (the real BGĐ username) + `registrationId` — no need to separately know the opportunity code or cross-reference who's assigned. Confirmed **any authenticated BMS account can run this search** regardless of whether they're the assigned approver (it's read-only) — the tool reuses the CM convention account (`truongnv64`) as a fixed lookup identity so the user never has to supply it. Implemented as `LookupRegistrationGenerator.java` (`registration.lookup`): searches `approvalStatus:1` then `:2` (don't know current state up front) and returns `{opportunityId, registrationId, bgdUsername, alreadyApproved}`.

Full generator chain, all confirmed live end-to-end (`CH00084099`/`CMR2614586`):
1. `registration.lookup` (fixed lookup account) → `{opportunityId, bgdUsername}`
2. auto-login as the discovered `bgdUsername` → `registration.approve-bms`
3. `auth.get-cm-token` (CM account) → `registration.approve-cm` (using the same regCode the user typed in, not a re-derived one)

UI: `mountHdPlApprovalCard` in app.js — 2 fields only (Mã YC + tài khoản CM), no manual BGĐ field at all anymore.

**🐛 BUG found and fixed 2026-08-27 — CM mutation calls need a `ticket` header, and the 403 error text lies about the field name.** `CreateContractCM`/`UpdateStatus` (unlike the read-only `Registration/Get`) 403 with `"You don't have permission! Please enter field InsideToken in your header request url"` unless a request header is sent. The error message says "InsideToken" — that name is **wrong**; an `InsideToken` header (or query param) still 403s. The real required header is **`ticket`** (lowercase, exactly matching the real UI's own captured header), and its value is the JWT's own `TICKET` claim (a CAS service ticket) echoed straight back. Confirmed live by probing with a dummy `cmrTransactionId` — a `ticket` header flips the response from `403 permission` to `400 "Phiếu yêu cầu không tồn tại!"` (real validation error, proving auth passed). This bug was invisible in every earlier "confirmed live" test of `registration.approve-cm` because those all happened to hit the **idempotent skip branch** (`status:4` already) — the actual mutation code path was never exercised until a genuinely fresh regCode (`CMR2614588`) surfaced it. Lesson: an "idempotent, already-done" test result proves the read path, not the write path — don't call a mutation "confirmed" without seeing it act on a not-yet-done record at least once.

## Investigation: Creating the Yêu cầu tạo HĐ/PL itself (not just approving it) — ✅ walked end-to-end successfully 2026-08-27 (CH00085368)

Walked the real UI end-to-end **successfully all the way to "Chờ BGĐ duyệt"** (`CH00085368`, Đầu số thoại/Điện thoại cố định, as Sale ThiTLH), confirming the full field set for each of the 3 legacy subsystems involved. Not implemented as a generator yet — these are the exact notes needed to build that.

**Precondition:** opportunity must have progress `100%` (all 5 giai đoạn done) AND an existing quotation (`opportunity.quotation`) — the "Yêu cầu tạo HĐ" button's own `GET opportunities/{id}/can-create-registration?serviceGroup=X&serviceType=Y` check 400s with `"Vui lòng tạo Yêu cầu báo giá trước khi lên Yêu cầu tạo HĐ/PL!"` otherwise.

**Surprise: the whole wizard runs on the OLD legacy BMS (bmsv2 React app is only the entry point).** Clicking "Yêu cầu tạo HĐ" on the bmsv2 opportunity page → modal (pick service) → confirm dialog ("sẽ không thể review HĐ") → confirm dialog #2 ("Xác nhận tạo yêu cầu hợp đồng") → client-side navigates to `bmsv2/opportunity/create-registration?refCode={oppId}_{randomSuffix}` — this is STILL the bmsv2 React SPA (Bước 1/2/3 stepper), NOT the legacy system yet. **All of Bước 1/2 (contacts, cước, vùng miền) is bmsv2 REST-backed and persists server-side per refCode** (confirmed: reload the URL mid-wizard, all previously-filled fields/contacts survive) — good news for a pure-API generator, this part likely IS a normal JSON API like everything else once the exact endpoint is found (not yet captured — every attempt to read the request body hit the cookie/query-string block; only endpoint NAMES were recoverable, see below).

**Bước 2 (Thông tin dịch vụ, bmsv2) required fields:** Loại văn bản, Hạn mức công nợ, Phân loại hợp đồng, **Vùng miền — only 2 valid values exist: "Thành phố Hà Nội" / "Thành phố Hồ Chí Minh"** (from `GET bms-service/api/master-data/locations-for-regist`, no query params), Hình thức thanh toán, Cước trả một lần, Cước tháng, Thời hạn hợp đồng, Kỳ hạn thanh toán, Ngày hiệu lực hợp đồng, **≥1 Liên hệ thu cước** (tên + tỉnh/phường + địa chỉ + quốc gia + SĐT + email) and **≥1 Liên hệ kỹ thuật** (tên + SĐT + email) — both contact-name fields reject digits/special chars (same person-name pattern as the CM customer-create endpoint's individual variant).

**"THÔNG TIN TRIỂN KHAI" → "Thêm chi tiết"** shows a warning dialog ("Thông tin thay đổi của phiếu sẽ được lưu để đồng bộ với tool dịch vụ") then **redirects out to a THIRD, older subsystem: FTI-IVOICE** at `ftms-stag.fpt.net/ivoice/registration/iframecreate?ParentFrame=BMS&Action=PageAdd&ServiceType={n}&RefCode={oppId}_{suffix}&callback={bmsv2CreateRegistrationUrl}&Contract=` — plain jQuery/Kendo/ASP.NET MVC, no SPA framework, same legacy session cookies (a 403 seen once here on first attempt was a stale double-click, not a real permission gap — confirmed by retrying cleanly and by the user's own manual test).

For Điện thoại cố định (`ServiceType=1`) the "Tạo phiếu triển khai" modal ("Danh sách triển khai +") needs:
- **Thông tin dịch vụ đăng ký:** Loại kết nối (SIP Trunk), **Nền tảng** (dropdown — user wants this exposed as a picker in the future UI, values seen: "Thường", "Voice Brandname", others likely exist), Auto Call (Có/Không radio)
- **Địa chỉ triển khai:** Tỉnh thành, Phường/Xã, Số nhà/tên đường, Ngày hẹn triển khai, Người liên hệ, Điện thoại, Email — **⚠️ gotcha confirmed live: changing Tỉnh thành does NOT clear-and-refill Phường/Xã, it silently reverts to unselected, and "Lưu" then fails with ZERO visible feedback (no toast, no red text, no network call at all)** — must always re-select Phường/Xã after every Tỉnh thành change, in code and in the eventual generator's field-order logic
- **Hệ thống của đối tác (SIP):** IP kết nối, Loại đường truyền (Public/MPLS), Mô tả hướng cuộc gọi (checkbox multi-select: Nội hạt/Liên tỉnh/Di động/Quốc tế/Giải trí), Gói Profile (CC/SPS)
- **Danh sách số điện thoại đã book** — a table that auto-populates via `POST /ivoice/Registration/GetPhoneBookedNumber` once Tỉnh thành+Phường are set; **you must tick ≥1 row** (this is the actual phone-number assignment, not a free-text field). **Confirmed live: Hà Nội/any phường returned an empty list in STG; switching Tỉnh thành to "Thành phố Hồ Chí Minh" + "Phường Bình Dương" populated 2 real bookable numbers (`02547300048`, `02547300049`, 20,000đ/tháng, mã số thuế 2886885800)** — per user: HCM is the main platform they test with, so the future generator/UI should let the user pick Tỉnh thành first, call this endpoint, and present the returned numbers as a checkbox list (exactly mirroring the manual UI).
- Also fires `POST /ivoice/MasterData/GetWard` (cascading phường list for a tỉnh) and `GET /ivoice/Registration/GetDebtCharges?taxId={customerTaxId}` (auto, once customer's tax ID is known).

**✅ SOLVED 2026-09-03 — full save payload captured live by the user via real browser DevTools ("Copy as cURL"), after every in-session capture attempt (XHR/fetch monkey-patch, `HTMLFormElement.submit` patch, CDP network log, Performance Resource Timing API) had come up completely empty across many tries. Correction to the earlier note above: it IS a real AJAX call (jQuery `$.ajax`, confirmed by `x-requested-with: XMLHttpRequest`) — not a plain form POST as guessed before; why none of the in-session interception techniques ever saw it remains unexplained, but is moot now that the real payload is in hand.**

**Endpoint:** `POST https://ftms-stag.fpt.net/ivoice/Registration/InsertRegistrationIframe`
**Auth:** session cookies only, no `Authorization` header — `IVOICE_USER_COOKIE`, `IVOICE_USER_SESSION` (both ASP.NET Core Data-Protection–encrypted `CfDJ...` blobs, opaque) plus the usual `access_token`/`BMS_access_token`/`access_token_sso` riding along. A pure-`HttpClient` generator needs a real cookie jar from a one-time Selenium login (same pattern as `auth.get-cm-token`), not a bearer token.
**Content-Type:** `application/x-www-form-urlencoded; charset=UTF-8`, body is a **single form field, `dataJson`**, whose value is one big URL-encoded JSON blob — not individual `name=` fields per input as guessed from the DOM field-name sweep earlier. `UpdateRegistrationIframe` (edit) almost certainly takes the identical shape (unverified — only the create path was captured).

Real captured body (`dataJson`, URL-decoded and pretty-printed; ordering theirs, this run's business values), for `CH00085410` / `RefCode=CH00085410_96362` / Đầu số thoại-Điện thoại cố định:

```json
{
  "linkServiceType": "", "initServiceCharge": "", "contractServiceID": "",
  "isManyPrices": 0,
  "PromotionJson": "[]", "DeviceJson": "[]", "SwitchboardJson": "[]", "Cp1900Json": "[]",
  "lRegistrationDetailModel": [
    {
      "isNew": 1, "RegDetailId": 1, "ID": 1,
      "serviceGroup": "1", "serviceType": "1", "connectType": "1",
      "internetContract": "",
      "locationId": "8", "streetId": 0, "wardId": "1000009755", "locationNumber": "123",
      "contactName": "Nguyen Van An", "contactPhone": "0976987001", "contactEmail": "datdgd@fpt.com",
      "description": "",
      "locationName": "Thành phố Hồ Chí Minh", "districtName": "", "wardName": "Phường Phú An", "streetName": "",
      "fromObjId": null, "buildingAddress": "",
      "autoCall": "1", "deployDate": "09/03/2026",
      "configInfo": {
        "connectType": "1", "ipMain": "10.0.0.1", "ipMainType": "2", "portMain": "5060",
        "portBK1": "", "portBK2": "", "ipbK1Type": "", "ipbK2": "", "ipbK2Type": "", "ipbK1": "",
        "lineNo": "", "voiceDirectionOpen": "2,1", "maxValue": "2000000",
        "packageProfile": "1", "platform": "1", "prefix": "Yes",
        "ipMedia": "", "ipMediaType": "", "portMedia": "", "forwardType": "",
        "intermediateNumber": "", "destinationNumber": "0", "MaxCall": "0",
        "ivR1900": "No", "ivrDebtReminder": "No", "allowHighCost": "No", "highCostCode": "",
        "voiceTypeSystem": "", "voiceFirstEndEq": "", "voiceModerm": "", "voiceConnectLayout": "", "voiceCodec": ""
      },
      "lRegistrationPhoneModel": [
        {"phoneNumber": "02547300148", "subcriberCharge": 20000, "id": 592982, "vipPhoneCharge": 0,
         "fromObjID": 0, "regDetailId": 1, "chargeCommitment": null, "methodBillingType": null,
         "platformFixPhone": "1", "callDirectionId": null, "callDirectionName": null,
         "brandNameId": null, "voiceBrandName": null, "bookingRequestId": null},
        {"phoneNumber": "02547300149", "subcriberCharge": 20000, "id": 592983, "...": "(same shape)"}
      ]
    }
  ],
  "opportunityCode": "CH00085410", "taxId": "P37590260",
  "serviceType": "1", "serviceGroup": "1", "refCode": "CH00085410_96362", "autoCall": "1",
  "PriceJson": "[]", "ContractBMS": ""
}
```

Key mappings back to the UI fields documented above (DOM `name=` attribute → this JSON, where they differ):
- `SubCompanyId` (Tỉnh thành) → `locationId` (numeric code, e.g. `"8"` = Hồ Chí Minh — same id space as `bms-service/api/master-data/locations-for-regist`'s `id` field on the bmsv2 side, both resolve HCM to `8`) + human-readable `locationName` sent alongside, redundantly.
- `WardId` (Phường/Xã) → `wardId`, a **long numeric string code** (`"1000009755"`), NOT the ward's display name or a small sequential id — must come from `POST /ivoice/MasterData/GetWard`'s response for the chosen `locationId`, plus `wardName` sent alongside for display.
- `AdressNumber` (Số nhà, tên đường) → `locationNumber`.
- `VoiceDirectionOpen` (Mô tả hướng cuộc gọi checkboxes) → `configInfo.voiceDirectionOpen`, a **comma-joined string of numeric codes** (`"2,1"` for whichever two checkboxes were ticked), not the Vietnamese labels — codes for Nội hạt/Liên tỉnh/Di động/Quốc tế/Giải trí not yet individually confirmed, would need one probe per checkbox to map them.
- **Phone numbers are NOT sent as bare strings** — each ticked row from "Danh sách số điện thoại đã book" becomes a full object in `lRegistrationPhoneModel`, and critically carries **`id` (592982/592983 here) — the booked-number's own internal DB id**, which only exists in `GetPhoneBookedNumber`'s response rows and can't be invented; a generator MUST call that endpoint first and copy the chosen row's `id` (plus `subcriberCharge`, `platformFixPhone`, etc.) through verbatim rather than just sending the phone number string.
- `Nền tảng` (`VoiceBrandName` field in the DOM) maps to `configInfo.platform` — `1` = "Thường", **`2` = "Voice Brandname"** (confirmed live 2026-09-03 by the user reading the real Payload tab in DevTools while switching the dropdown — both values also feed `ivoice.get-phone-booked`'s `VoiceBrandName` request field, which genuinely returns a different phone-number list per platform, confirmed live too). Both wired into `InsertIvoiceRegistrationGenerator`/`GetIvoicePhoneBookedGenerator`'s `platform`/`voiceBrandName` ParamSpec and the UI's Nền tảng dropdown.
- Top-level `taxId` is the **customer's tax code** (`P37590260`), matching the `GetDebtCharges?taxId=` call fired earlier in the flow — needed for the debt-check tie-in, sourced from the opportunity's own customer record, not user input.
- No CSRF/anti-forgery token field appears anywhere in the body — auth + the `x-requested-with: XMLHttpRequest` header are apparently sufficient for this legacy endpoint.

**"Tiếp theo" → "Đồng ý"** on the outer IVOICE page (`Chi tiết dịch vụ Ivoice - Loại dịch vụ`, with the Danh sách triển khai/Chiết khấu/Thiết bị/Tổng đài `+` sections — only Danh sách triển khai was required, the rest were skippable per user confirmation) navigates back into **legacy BMS** at `bms/RegistrationMagt_v12/...` then straight to `bms/OppsMagt/Detail?opportunityId={oppId}` on the "Tạo hợp đồng" tab, showing the registration row with **Trạng thái = "Chờ BGĐ duyệt"** — the exact same state `registration.lookup`/`registration.approve-bms`/`registration.approve-cm` (documented above) already know how to pick up and finish approving. Detail/edit link for this row goes to `bms/RegistrationMagt_v12/UpdateRegist?RefCode={oppId}_{suffix}` (note: brand-new registrations have NO `CMR...` refCode yet — the `_{suffix}` internal key is all that exists pre-approval, matching why `registration.lookup`'s opportunityId-fallback matching was needed in the first place).

**⚠️ Known footgun from this session, worth guarding against in the generator:** clicking fast/blindly through repeat confirm-dialogs (coordinates computed from a stale screenshot before a dialog re-rendered) can create a **second, unwanted "Danh sách triển khai" row with auto-filled defaults** (seen: address `"21, Phường Bình Dương, Thành phố Hồ Chí Minh"`, contact = the logged-in user's own name, date = day-before) — always re-`find()`/re-screenshot immediately before each confirm click rather than reusing old coordinates. Deleting the stray row via its own `x` button worked cleanly (confirmed after the fact: final "Tạo hợp đồng" tab showed only 1 row, correctly de-duplicated).

**Final confirmed outcome (CH00085368):** the registration progressed past "Chờ BGĐ duyệt" on its own to **`Mã yêu cầu = CMR2614591`, `Số hợp đồng = HNV016960` already assigned, `Trạng thái = "Chờ DEM duyệt"`** — a real contract number was generated purely from this UI walkthrough, no approve step run yet. `CMR2614591` is a valid input to `registration.lookup` going forward for this case.

**Net effect / recommendation for building this:** the IVOICE deployment-detail save (`InsertRegistrationIframe`) is now fully mapped — see the real payload above — and implemented as a pure `HttpClient` POST (`InsertIvoiceRegistrationGenerator`, type `ivoice.create-registration-detail`) using a cookie jar from a one-time Selenium login (`GetIvoiceSessionGenerator`, type `auth.get-ivoice-session` — same pattern as `auth.get-cm-token`), per the user's "API hết chứ không phải Selenium" instruction. The phone-number picker (`GetPhoneBookedNumber`) is also captured and implemented (`GetIvoicePhoneBookedGenerator`, type `ivoice.get-phone-booked`) — plain form fields (`LocationId`, `TaxID`, `ServiceType`, `VoiceBrandName`, `RegDetailId=-1`, `RegExcludeId=-1`, `isCPN=false`, `PartnerId=`, `ContractCode=`), notably keyed off Tỉnh thành + Mã số thuế KH, NOT Phường — the earlier assumption that Phường triggered the phone list was wrong. `GetWard`'s request shape is ALSO captured (`GetIvoiceWardGenerator`, type `ivoice.get-ward`) — see its own section below. All 4 wired into a dedicated "Ivoice" page (nav link + `/ivoice` URL, `WebConfig#addViewControllers` forwards it to `index.html` for direct hits/reloads) with live "Tìm số điện thoại"/"Tải Phường/Xã" pickers, auto-triggered on field change (no manual button click needed, per user request 2026-09-03/07), random-filled contact/IP/date fields, and RefCode/Mã số thuế auto-derived from Mã cơ hội via `ivoice.lookup-opportunity` (disabled inputs, not user-editable — see its own section below). Still open: (1) the **legacy BMS `InsertRegistration` save is now ALSO captured** (2026-09-07) — see its own section right below, the true final piece; (2) map `voiceDirectionOpen`'s per-checkbox numeric codes; (3) confirm `UpdateRegistrationIframe`'s body shape matches (assumed identical, unverified); (4) the 3 unsupported Ivoice sub-loại (1800/1900/CSKH) each need their own captured `InsertRegistrationIframe` payload; (5) how a brand-new `RegistrationId` gets minted in the first place (the very first "Xác nhận tạo yêu cầu hợp đồng" click) is still unobserved — everything captured so far assumes one already exists.

## API contract: Lookup Opportunity for Ivoice (auto-derive RefCode/taxId) — ✅ CONFIRMED 2026-09-03

Implemented as `LookupOpportunityForIvoiceGenerator.java` (`ivoice.lookup-opportunity`). Given just an Opportunity code, chains 2 plain `bms-service` REST GETs (real Bearer token, NOT the IVOICE cookie jar) to auto-fill what the Ivoice card used to make the user type by hand:

1. `GET bms-service/api/opportunities/{opportunityCode}` → `data.customerId` + `data.services[]` (each service row has its own `serviceGroup`/`serviceType`/`registrationId`) — `registrationId` is exactly the RefCode suffix (`opportunityCode + "_" + registrationId`).
2. `GET bms-service/api/customners/{customerId}` — **note the real endpoint's typo, "customners" not "customers"**, found live via Performance Resource Timing after every correctly-spelled guess 404'd. Its `taxOrHouseholdCode` is the org customer's tax code; for an individual/foreign customer that's null and the same value lives in `identity` instead (confirmed: a customer with `identity:"P37590260"` was the exact `taxId` a real captured IVOICE request used) — try `taxOrHouseholdCode` first, fall back to `identity`.

Wired to the Ivoice card's "Mã cơ hội" field via a `change` (blur) listener — auto-fills RefCode + Mã số thuế KH (now disabled inputs, not user-editable, per user request 2026-09-07) and triggers the phone-number search since taxId just became known.

## API contract: List Phường/Xã (IVOICE) — ✅ CONFIRMED 2026-09-03

Implemented as `GetIvoiceWardGenerator.java` (`ivoice.get-ward`). `POST https://ftms-stag.fpt.net/ivoice/MasterData/GetWard`, cookie-jar authed (same as the other IVOICE endpoints), single form field **`LocationID`** (capital ID — distinct from `GetPhoneBookedNumber`'s `LocationId`, confirmed live, don't assume the two controllers share casing). **Response is a bare JSON array** (not wrapped in `data`/`Data`), each row `{id, name, priority, districtID}` — `id` is the long numeric wardId `InsertRegistrationIframe` needs, `name` is the display wardName; picking one dropdown option now supplies both at once (replaced the 2 manual-entry fields). Confirmed live for `LocationID=4` (Hà Nội) — returned ~140 real wards across Hà Nội's districts.

## API contract: Insert Registration (legacy BMS) — ⚠️ NOT the "Thêm chi tiết" call, re-scoped 2026-09-07

**⚠️ Correction 2026-09-07 (real browser walkthrough, not just curl replay):** clicking "**+ Thêm chi tiết**" in the real bmsv2 wizard's "THÔNG TIN TRIỂN KHAI" section (Bước 2 "Thông tin dịch vụ") navigates the whole tab to `ivoice/registration/iframecreate?ParentFrame=BMS&Action=PageAdd&ServiceType=1&RefCode=...&callback=...` (a real cross-area navigation, confirmed by network tab — NOT an iframe despite the URL's name, and NOT an SPA-internal transition — matches the user's own observation "ấn Ivoice nó điều hướng qua đó"). Filling the "Tạo phiếu triển khai" form there and clicking **Lưu** fires:

```
POST https://ftms-stag.fpt.net/ivoice/Registration/InsertRegistrationIframe   →  HTTP 200, "Thêm thông tin thành công"
```

**✅ Field mapping CONFIRMED CORRECT 2026-09-07** — user captured a real "Copy as cURL" for a fresh RefCode (`CH00085443_96442`) that structurally matches this generator's built payload field-for-field. Debugging an earlier `code:400 "Tạo PĐK không thành công"` failure (generic — payload malformed/invalid) eventually surfaced the REAL specific error once a locked phone number was used:
```json
{"code": 400, "data": "Số điện thoại: 02547300147 thuộc mã cơ hội: CH00085411 - mã y/c: . Vui lòng liên hệ Saleman truongnv64 để kết thúc cơ hội."}
```
i.e. a booked phone number gets LOCKED to whichever opportunity first successfully submits it via `InsertRegistrationIframe` — reusing that same number for a DIFFERENT opportunity is correctly rejected until the first opportunity is closed. Practical fix for testing: always pick a phone number that hasn't been used by this tool before (each successful `ivoice.create-registration-detail` call permanently locks its chosen number(s) to that opportunity on the STAGING server — there is no "undo").

**❌❌ Real bug found and fixed 2026-09-07** (user correctly insisted the generic `"Tạo PĐK không thành công"` was a genuinely MISSING/wrong field, not just "different valid values" — they were right): `InsertIvoiceRegistrationGenerator` used to build `lRegistrationPhoneModel` by forwarding `ivoice.get-phone-booked`'s raw rows verbatim (`phones.forEach(phonesArray::add)`), on the assumption (stated in `GetIvoicePhoneBookedGenerator`'s javadoc) that the two shapes were identical. **They are not.** Field-by-field diff against the user's real captured cURL:
- `platformFixPhone` (`"1"` in every real example) — **completely absent** from `GetPhoneBookedNumber`'s raw response rows.
- `regDetailId` — the real submit needs this name; the raw phone row instead has `regDetailCode` (always `null` in every observed row).
Fixed: `InsertIvoiceRegistrationGenerator` now maps each phone row explicitly (`copyOrNull` helper) instead of raw-forwarding — hardcodes `platformFixPhone="1"` and `regDetailId=1` (matching the parent detail's own `RegDetailId`), copies the rest (`phoneNumber`/`subcriberCharge`/`id`/`vipPhoneCharge`/`fromObjID`/`chargeCommitment`/`methodBillingType`/`callDirectionId`/`callDirectionName`/`brandNameId`/`voiceBrandName`/`bookingRequestId`) faithfully.

**✅✅✅ FIX CONFIRMED WORKING live 2026-09-07** (fresh phone number, `CH00085443_96442`) — first time this endpoint's real SUCCESS response body has ever been observed:
```json
{"code": 200, "data": {"regId": 415417, "regCode": "CH00085443", "message": "Lưu thông tin thành công!"}}
```
So the success shape is `{code: 200, data: {regId, regCode, message}}` (vs. failure's `{code: <4xx>, data: "<string message>"}` — note `data` is an OBJECT on success but a STRING on failure, worth defensive handling in any code that parses this). `regId` (415417) is a new internal id worth keeping if a later step needs it (unconfirmed what it's for yet — possibly the actual "Danh sách triển khai" row id shown in the legacy BMS Bước 2 table).

**❌❌❌ Real UI bug found 2026-09-07** in the SAME "Ivoice — Thêm chi tiết" card, this time on the `bms.insert-registration` step: `InsertBmsRegistrationGenerator`'s built payload was field-complete (root + `Registration` object both verified 100% against the real captured shape, no missing/misnamed fields this time), yet a real live test still got `{"status":"NotOk","code":401,"message":"Bạn không có quyền thao tác hoặc yêu cầu đã hoàn thành không thể cập nhật."}` on a genuinely fresh, never-submitted RegistrationId (user confirmed they'd never clicked "Tạo" for it manually). Root cause turned out to be the FRONTEND, not the generator: the "ListMember (JSON)" textarea's default value (`OpportunityId` inside it) is computed **once at card-mount time**, when `opportunityCodeInput` is still empty — so it always fell back to a hardcoded `"CH00085412"` placeholder and **never refreshed** when the user later typed a real Mã cơ hội. Result: the submitted `jsonData` had a genuine internal inconsistency — top-level `OpportunityId` (e.g. `CH00085443`) didn't match `ListMember[0].OpportunityId` (stuck at `CH00085412`) — which the server rejected with this generic permission/already-completed-sounding message rather than a validation error naming the field. Fixed in `app.js`: `listMemberJsonInput` now has an `input` listener that flags `listMemberEditedByUser = true` on any manual edit, and `refreshListMemberDefault()` (called from the same `onIdentityChange()` that already refreshes wards/phones/derivedSummary on Mã cơ hội/Sale/Loại dịch vụ change) re-renders the JSON default from current field values — but only while the user hasn't manually edited it, so real hand-edits (e.g. a corrected `RefibbmembersId`) are never clobbered.

**❌❌❌❌ Real bug #2 found 2026-09-07, same session** — after the ListMember fix, `bms.insert-registration` STILL failed with the identical `"Bạn không có quyền thao tác hoặc yêu cầu đã hoàn thành không thể cập nhật."` on a genuinely brand-new opportunity (`CH00085444`, never touched by anyone before) — ruling out both "stale RegistrationId" and "field mismatch" theories. The log line gave it away: **both** the IVOICE and BMS-legacy login steps said `"Dùng lại session ... còn hạn"` (cache hit, no fresh login) even though the RefCode had changed. Root cause: `getCachedOrFreshBmsLegacySession`'s cache `Map` was keyed by **email only**, not `email+refCode` — so once a legacy-BMS cookie jar was minted for one refCode (e.g. `CH00085443_96442`), it got silently REUSED for every subsequent DIFFERENT refCode within the ~50min TTL window, including `CH00085444`. If the server ties the `FTIBMS-Auth` session to the specific refCode it was minted against (plausible — `CreateRegist?refCode=...` is a per-registration page, the server may stash "currently authorized registration" in session state), submitting `InsertRegistration` for a DIFFERENT refCode than the one the cookie was minted for would correctly get "no permission" — matching the observed symptom exactly. **Fixed**: cache key changed to `` `${email}|${refCode}` `` so a fresh legacy-BMS login (and thus a correctly-scoped cookie jar) is minted per refCode, never silently reused across different ones. Still failed identically after this fix too (same message, tested on genuinely fresh `CH00085444`) — see the 4th fix below, likely the real one.

**❌❌❌❌❌ Real bug #3 (likely the actual root cause) found 2026-09-07** — the user captured a real "Copy as cURL" for a request our automated flow had **never made at all**:
```
POST https://ftms-stag.fpt.net/bms/RegistrationMagt/CheckServiceIntergrate
(note: different legacy controller — no `_v12` suffix — and "Intergrate" is the endpoint's own real spelling)
Body: refCode=CH00085443_96442&serviceGroup=1&serviceType=1&totalAmt=2&paymentTypeCode=2&refKeys[]=null&refKeys[]=null&refKeys[]=null&refKeys[]=null&refKeys[]=null
```
Fired by the real wizard right before the final "Tạo" submit. Field correspondence confirmed by diffing against the same real InsertRegistration capture: `totalAmt` = `Registration.ContractValue` exactly, `paymentTypeCode` = `Registration.PaymentMethod` exactly, and the 5 `refKeys[]=null` entries matched that capture's 5 `ListDeployment` rows 1:1 — each is that row's own `refCodeOfIntegrated` field (all the literal string `"null"`). Built `CheckServiceIntegrateGenerator` (`bms.check-service-integrate`) and wired it into the UI card as a new **Bước 2/3** (between IVOICE detail-save and the final `InsertRegistration`, now **Bước 3/3**) — non-blocking (logs but doesn't stop the flow if it errors).

**❌ Bug #3 hypothesis REFUTED, ✅✅✅ REAL root cause confirmed live 2026-09-07 — role/permission, exactly the ORIGINAL hypothesis from earlier in this same debugging session:** `CheckServiceIntergrate` alone did NOT fix `InsertRegistration` (still `401 "Bạn không có quyền..."` on a genuinely fresh opportunity even with the check passing `{"status":"Ok"}` first). The actual fix: **log in as `truongnv64` (role `FTIBMS.ADMIN` among several ADMIN roles) instead of `thitlh` (role `FTI-SAL SALESMAN`)** for the `bms.insert-registration` step specifically — confirmed by direct A/B test, same generator/payload style, only the login account differed. **First-ever observed real SUCCESS response** (`CH00085447`):
```json
{"data": {"data": 3, "status": "OK", "code": 200, "message": "Sucess"}, "status": "Ok", "code": 200, "message": "Ok"}
```
(nested nested `data.data` — a small int, 3 here, meaning unconfirmed; note the real server's own typo "Sucess", not introduced here). **Independently confirmed via direct DB-state check** (`GET opportunities/{code}`) that earlier "failed" runs (`CH00085443`, `CH00085445`, both run as `thitlh`) had ALSO actually created the registration server-side (`hasCreatedRegistration: true`, `services[].bgdApprovalStatus: 2` = "Chờ duyệt") **despite the tool reporting the call as failed** (`{"status":"NotOk","code":401,...}`) — meaning **the 401/"no permission" response can be a false negative that still writes real data**, at least for some accounts/paths. Exact mechanics of why a write succeeds alongside a permission-denied response are still unclear (maybe a save-then-authorize-response ordering quirk server-side) — not fully understood, but the practical fix (use `truongnv64` for this step) is confirmed working end-to-end.

**Next problem, newly surfaced 2026-09-07 (separate from the above, NOT yet solved):** with `InsertRegistration` now genuinely succeeding, the next step in the chain — `registration.approve-bms` (already-existing generator, confirmed working since 2026-08-27 on other opportunities) — now fails on these freshly-created registrations:
```json
{"success":false,"errors":[{"code":"CM_VALIDATION_FAILED","message":"Dữ liệu không hợp lệ. Vui lòng kiểm tra lại thông tin!","details":null,"retryable":false}]}
```
This is the BMS API's own validation rejecting the registration's stored data at approval time — likely candidates given this tool's random/throwaway field values: fake email domains (`@example-test.vn`), tiny/odd `ContractValue`/`FeeContract`/`MonthlyFeeContract` test values (e.g. `1`, `2`), empty `Registration.StartContractDate`/`EndContractDate` (always sent as `""`), or duplicate/inconsistent `ListDeployment` rows. Not yet isolated which field — needs the same kind of field-by-field live A/B testing (try approving with progressively more "real-looking" values) that solved the two bugs above.

**This is `ivoice.create-registration-detail` (`InsertIvoiceRegistrationGenerator`) — already built and correct.** It is **NOT** `bms/RegistrationMagt_v12/InsertRegistration`. The two were previously conflated in this doc; they are different calls for different steps. `InsertRegistrationIframe`'s own save is scoped to IVOICE's internal DB only — reloading the outer bmsv2 wizard afterward still showed "THÔNG TIN TRIỂN KHAI: Không có dữ liệu!" (empty), meaning IVOICE's save does **not** automatically sync back into the bmsv2 wizard's own tracking; the real flow's `callback=` URL param is a **popup→opener callback** (`window.opener.location = callback`) — since our test navigated the tab directly instead of opening a real popup, that callback had no opener to call and "Tiếp theo" stalled afterward. Reaching bmsv2's own Bước 3 ("Xác nhận thông tin" — the step whose final submit this doc's `InsertRegistration` section originally described) requires redoing this through a real popup so the callback round-trip completes and the outer wizard picks up the saved deployment row. **Not yet re-confirmed live** whether `bms/RegistrationMagt_v12/InsertRegistration` is genuinely called at that later step — this doc's earlier claim was based solely on a user-supplied curl (request only, never a captured response) plus one later live test (below) that got real structured JSON back (ruling out "route doesn't exist," but not yet proving *when* in the flow it's really called). Next session: redo the walkthrough letting "Thêm chi tiết" open as an actual popup (or manually complete the callback navigation) to reach Bước 3 and observe its real submit network call directly.

**✅✅ FULL FLOW CONFIRMED LIVE 2026-09-07 (real Chrome walkthrough, `CH00085411_96363`, opened as a real popup this time so the callback round-trip actually completed):**

1. bmsv2 Bước 1 "Thông tin chung" → Tiếp theo → Bước 2 "Thông tin dịch vụ".
2. "+ Thêm chi tiết" → confirm "Thông tin thay đổi... đồng bộ với tool dịch vụ" → tab navigates to `ivoice/registration/iframecreate?...` (real cross-area navigation, not an iframe) → "Danh sách triển khai +" → fill form → **Lưu** → `POST ivoice/Registration/InsertRegistrationIframe` → 200 "Thêm thông tin thành công".
3. Back on the ivoice page, click page-level **Tiếp theo** → confirm "Bạn muốn lưu thông tin dịch vụ và tiếp tục?" → **Đồng ý** → a SECOND dialog appears: "Lưu thông tin dịch vụ thành công!" → click its **Đồng ý** → **this is the real popup→opener callback firing** → tab navigates to `bms/RegistrationMagt_v12/CreateRegist?refCode=CH00085411_96363` — **the real legacy "BMS cũ"** (classic MVC UI, offers "chuyển sang FTI BMS v2.0" which we declined with "Để sau").
4. This legacy page's own Bước 2 ("Thông tin Dịch Vụ") now shows the deployment row synced from step 2 (contact/address matched exactly) — confirms the sync did carry through once the callback completed properly. Added the 2 still-missing sections here (mandatory, not filled by the IVOICE step): "Thêm liên hệ thu cước" (`ListBillingContact` — Người liên hệ, Thành phố/Tỉnh, Phường/Xã, Địa chỉ, SĐT, Email; province/ward fields are type-ahead comboboxes, need accented Vietnamese text e.g. "Hồ Chí" to match) and "Thêm liên hệ kỹ thuật" (`TechRegistrations` — Người liên hệ, SĐT, Email only, no address).
5. Click **Tiếp tục** (bottom-right) → legacy Bước 3 "Xác Nhận Thông Tin" → button now reads **"Tạo"**.
6. Click **"Tạo"** → tab navigates to `bms/OppsMagt/Detail?opportunityId=CH00085411` (the opportunity detail page) → its "Quá trình cơ hội" timeline now shows **"Tạo hợp đồng"** as the active stage, and its own "Tạo hợp đồng" tab lists a real new row: **Trạng thái: "Chờ BGĐ duyệt"**, Ngày tạo 07/09/2026 14:00 — a genuinely new registration record now exists, sitting in the exact "pushed to BGĐ approval" state the user asked about earlier ("API 3 bước tạo đẩy BGĐ duyệt").

**The exact `InsertRegistration` POST request/response was NOT captured this run** — `read_network_requests` cleared/missed it across the step-6 navigation despite same-domain (tool behaves as if any top-level navigation clears the buffer, not just cross-domain as documented) — but the **behavioral result is unambiguous proof** it's the right endpoint at the right step: same URL family (`bms/RegistrationMagt_v12/CreateRegist?refCode=...` is literally the page whose button click fires it, matching the original Referer capture), same "3-step wizard, Bước 3 Tạo = final submit" structure, and a real DB state change occurred. **Root cause of the earlier live-curl 401 "NotOk" business-rule rejection (see above) is now understood**: that test hit `InsertRegistration` directly via HTTP before the legacy page's own Billing/Tech contact rows existed for that RegistrationId — the real UI enforces filling those first (steps 4 above); the generator/endpoint itself was likely correct all along, just called with incomplete prerequisite state. Next step to close this out completely: re-run `bms.insert-registration` via curl now that this RegistrationId (96363) has already been fully created (won't repro the missing-prereq case, but replaying against a FRESH never-touched refCode with all prerequisite contacts pre-populated via the API first would give the true confirming capture).

Original section content (auth findings below remain valid and reusable regardless of which UI action ultimately calls this endpoint):

**This was assumed to be the save behind Bước 2/3 of the "Yêu cầu tạo HĐ/PL" wizard.** Captured live by the user via DevTools "Copy as cURL" while clicking through the wizard for a Đấu số thoại/Điện thoại cố định registration (`CH00085412_96364`) — request only, response never observed until the live test below.

**Endpoint:** `POST https://ftms-stag.fpt.net/bms/RegistrationMagt_v12/InsertRegistration`
**Auth:** cookie jar, NOT a bearer token — `jwt_token_id`, `FTIBMS-Auth`, `FTIBMS`, `access_token`, `BMS_access_token`, `access_token_sso`, `.AspNetCore.Antiforgery.*` all rode along.

**❌ REFUTED live 2026-09-07 — the cookie-reuse hypothesis below (originally written 2026-09-07 before testing) was WRONG.** Calling this endpoint with `auth.get-ivoice-session`'s cookie jar got a real **HTTP 401**. Investigated by having Selenium also visit the legacy `bms/RegistrationMagt_v12/CreateRegist?refCode=...` page (the real Referer, see below) in the same browser session, in both orders:
- Legacy page visited AFTER bmsv2/ivoice → got `FTIBMS`/`FTIBMS-Auth`/`jwt_token_id`, but this WIPED `BMS_access_token`/`IVOICE_USER_*` right out of the jar.
- Legacy page visited BEFORE bmsv2/ivoice → got `BMS_access_token`/`IVOICE_USER_*` back, but visiting bmsv2/ivoice afterward WIPED `FTIBMS`/`FTIBMS-Auth`/`jwt_token_id` right back out.

So the legacy `bms/RegistrationMagt_v12/*` area and the bmsv2+IVOICE area are **two separate, mutually-exclusive auth realms within one browser session** — landing on either area's protected route triggers a fresh sign-in that signs the other scheme out. **User confirmed this matches real browser behavior too** ("chỗ này ấn Ivoice nó điều hướng qua đó" — clicking from BMS into IVOICE really does navigate to a separate area, not an SPA-internal transition), so this isn't a Selenium/raw-URL-navigation artifact — a real human browser session hitting both areas would need to authenticate into each separately too.

**Fix:** built a dedicated `auth.get-bms-legacy-session` generator (`GetBmsLegacySessionGenerator.java`) — login, then visit `CreateRegist?refCode=...`, poll for `FTIBMS-Auth`. **`auth.get-ivoice-session` and `auth.get-bms-legacy-session` are NOT interchangeable and must both be called separately** (two full Selenium logins) whenever a flow needs both `bms.insert-registration` and any `ivoice.*` call — exactly what the merged "Ivoice — Thêm chi tiết" UI card now does (2 login lines shown in its status log, one per step). ~~The original one-shared-jar plan is abandoned.~~

**✅ Cookie auth now confirmed WORKING live 2026-09-07** with the dedicated jar — a real `bms.insert-registration` call against `CH00085411_96363` (thitlh) got HTTP 200 at the transport level with a real parsed app response (first time either InsertRegistration or InsertRegistrationIframe response body has ever been observed):
```json
{"data": null, "status": "NotOk", "code": 401, "message": "Bạn không có quyền thao tác hoặc yêu cầu đã hoàn thành không thể cập nhật."}
```
So the app-level response shape is `{data, status: "Ok"|"NotOk", code, message}` — auth is solved, but this specific call was rejected by a BUSINESS rule (not credentials): either the logged-in Sale doesn't own/have rights on this registration, or `CH00085411_96363` was already submitted through this step before and can't be re-submitted. Not yet resolved which — needs the user to confirm whether CH00085411 is really thitlh's own untouched opportunity before this can be narrowed further. `ListMember[0].RefibbmembersId="1000772803"` was hardcoded from the earlier different capture (`CH00085412`, not `CH00085411`) — possibly needs to actually match the currently-logged-in Sale's own directory id, worth trying first if CH00085411 does belong to thitlh.
**Content-Type:** `application/x-www-form-urlencoded`, single field **`jsonData`** (like IVOICE's `dataJson`, but capital-J-less this time) whose value is one big JSON blob. `Referer` header on the real request was `bms/RegistrationMagt_v12/CreateRegist?refCode={refCode}` — confirms this legacy page (not any bmsv2 REST route) is what actually renders/submits the wizard, `bmsv2/opportunity/create-registration` in the address bar is just the outer shell.

Real captured body (`jsonData`, URL-decoded/pretty-printed, some long values elided):

```json
{
  "OpportunityId": "CH00085412", "OrderID": null,
  "RegistrationId": 96364, "RefCode": "CH00085412_96364",
  "Customer": {
    "id": 10376, "customerId": "FTI260739",
    "typeCusOfContract": 1, "typeCusMain": 1, "type": 2,
    "identity": "P37590260", "taxOrHouseholdCode": null,
    "fullName": "Nguyen Van Khang Linh",
    "identityDate": "2024-01-01T00:00:00", "identityPlace": "Cuc Quan ly xuat nhap canh",
    "email": "...", "phone": "0926998866",
    "groupCode": "Anonymous", "countryCode": "VNM",
    "typeName": "Nước ngoài", "typeCusMainName": "Khách hàng cá nhân",
    "fullAddress": "1 Duong Test So 142190, Test City",
    "nameOfNickName": "Ông", "identityDateString": "01/01/2024",
    "groupCodeName": "Anonymous", "countryCodeName": "Viet Nam",
    "charterCapitalString": "0"
    // ... ~15 more fields, this is basically the /customners/{id} GET
    // response passed through near-verbatim — same auto-fetch-then-
    // passthrough pattern as ivoice.get-phone-booked's chosen rows.
  },
  "ListMember": [
    {"Name": "thitlh", "Position": 1, "ContractRate": 100,
     "RefibbmembersId": "1000772803", "OpportunityId": "CH00085412"}
  ],
  "ServiceGroup": 1, "ServiceType": 1, "RegCode": "", "ContractNo": "",
  "ListBillingContact": [
    {"Id": 0, "ContactName": "Dat Duong Gia Duc", "ContactEmail": "datdgd@fpt.com",
     "ContactPhone": "0976543225", "Address": "10",
     "Location": 22, "District": 1000009075, "Ward": 0,
     "Country": "Việt Nam", "FullAddress": "10, Xã Mường Bám, Tỉnh Sơn La, Việt Nam"}
  ],
  "TechRegistrations": [
    {"Id": 0, "techContact": "da", "techEmail": "datdgd@fpt.com", "techNumber": "0976222333"}
  ],
  "ListDeployment": [
    {"ContactName": "Nguyen Van An", "ContactEmail": "datdgd@fpt.com", "ContactPhone": "0976987001",
     "Address": "1 Duong Test", "Location": 8, "District": 1000009712, "Ward": 0, "Country": "",
     "FullAddress": "1 Duong Test, Phường Bình Dương, Thành phố Hồ Chí Minh",
     "Id": 0, "refCodeOfIntegrated": "null", "IsSelected": true}
  ],
  "ListEContractDocument": [],
  "Registration": {
    "TypeContract": 1, "TypeDocContract": 1, "Location": 8,
    "StartContractDate": "", "EndContractDate": "",
    "AgencyContractFlag": 0, "OnSiteWarranty": 0, "AutoRenewContract": 0,
    "ContractValue": 20, "ContractValueCurrency": "VND",
    "PaymentMethod": 2, "DateContractMonth": 1,
    "FeeContract": 10, "MonthlyFeeContract": 10,
    "Notes": "", "Description": "",
    "LimitCreditDay": "30", "EContractFlag": 0, "IsDebt": 0
  },
  "IsUpdateTemp": false, "AppCodeInsert": "FTIBMS"
}
```

Field mapping back to the bmsv2 wizard's Bước 2 UI labels (documented earlier in this section, DOM `name=` never captured for this side since it's the legacy iframe not the React shell):
- `Registration.LimitCreditDay` ← Hạn mức công nợ; `Registration.TypeDocContract` ← Loại văn bản; `Registration.TypeContract` ← Phân loại hợp đồng; `Registration.Location` ← Vùng miền (`8`=HCM/`4`=HN, same id space as everywhere else in this system); `Registration.PaymentMethod` ← Hình thức thanh toán; `Registration.FeeContract`/`MonthlyFeeContract` ← Cước trả một lần / Cước tháng; `Registration.DateContractMonth` ← Kỳ hạn thanh toán; `StartContractDate` ← Ngày hiệu lực hợp đồng (empty string here, format unconfirmed).
- `ListBillingContact[]` ← the "Liên hệ thu cước" table (`Location`/`District`/`Ward` numeric codes, same cascading-picker id space as Tỉnh/Phường elsewhere — `District` here despite the label, not `Ward`, holds the phường-level code; `Ward` stayed `0` in this capture, meaning the actual phường selection lands in `District` for this payload, not `Ward` — a genuine, confirmed-live naming quirk, don't "fix" it when implementing).
- `TechRegistrations[]` ← "Liên hệ kỹ thuật" table (much smaller shape than billing contact — no address at all).
- `ListDeployment[]` ← a GENERIC deployment-address block distinct from IVOICE's own `lRegistrationDetailModel`/`lRegistrationPhoneModel` (no phone numbers here at all) — likely what non-Voice service types (Rack, etc.) use for their only deployment info, while Voice services get this AND the separate phone-specific IVOICE call afterward. `IsSelected: true` and `refCodeOfIntegrated: "null"` (the literal string, not JSON null) are copied as-observed, meaning unconfirmed.
- `ListMember[].RefibbmembersId` — an internal directory id for the Sale rep (`"1000772803"` for `thitlh`), not obviously derivable from anything already fetched in this project; source endpoint unknown.

**✅ Implemented 2026-09-07** as `bms.insert-registration` (`InsertBmsRegistrationGenerator.java`) — follows exactly the plan above: `Customer` auto-fetched via `/customners/{customerId}` (same call/typo as `ivoice.lookup-opportunity`); `ListMember`/`ListBillingContact`/`TechRegistrations`/`ListDeployment` accepted as raw JSON-array-string params (`listMemberJson`/`listBillingContactJson`/`techRegistrationsJson`/`listDeploymentJson`), same passthrough style as `phonesJson`; `Registration` contract-term fields accepted as flat scalar params with the observed defaults (`limitCreditDay=30`, `paymentMethod=2`, `dateContractMonth=1`, `monthlyFeeContract=10`, etc.); auth reuses `auth.get-ivoice-session`'s `cookieHeader` (the reuse hypothesis is UNTESTED against the real endpoint — needs a live run to confirm the legacy-BMS cookies minted during login are actually accepted here, since this call was only ever captured from a real interactive session, not yet replayed via this tool). Registered and confirmed live in `/api/generators` after a backend restart. Not yet wired into any UI card (no generic multi-generator form exists in this frontend — every card is hand-built) and not yet exercised end-to-end — `ListMember[].RefibbmembersId`'s source endpoint is still unknown, so a caller must supply it manually for now. Next steps before UI wiring: (1) live-test this generator with a real `cookieHeader` + real IDs to confirm the cookie-jar-reuse hypothesis and the request actually succeeds; (2) find `RefibbmembersId`'s source; (3) still-unsolved: how a brand-new `RegistrationId` first gets minted (the initial "Xác nhận tạo yêu cầu hợp đồng" click) — every payload here, including this one, assumes it already exists.

## API contract: Create Customer (random, CM) — ✅ CONFIRMED 2026-08-27

Fallback so `opportunity.api` can run without a hand-picked real `customerId`. Endpoint is on a THIRD API surface — not `bms-service/api`, not `gwcm/contract/api`, but `ftisapi-stag.fpt.net/api/cm/customer/create` — found by patching `XMLHttpRequest.prototype.open/send` client-side (Axios in this app uses XHR, not `fetch`) while clicking the CM "Tạo mới khách hàng" page's own **"Random fill (test)"** button then submitting; `fetch` patching alone caught nothing.

**Endpoint:** `POST https://ftisapi-stag.fpt.net/api/cm/customer/create` — takes the SAME CM bearer token as the HĐ/PL step 2 calls (`auth.get-cm-token`), confirmed working against this endpoint too.

**Minimal required body** (narrowed from the full random-filled payload via empty-body and partial-body probes — validation errors name missing fields directly):
```json
{
  "customerName": "...",
  "email": "...",
  "phone": "...",
  "groupCode": "Anonymous",
  "address": "...",
  "customerType": 2,
  "profileType": 4
}
```
`customerType: 2` + `profileType: 4` = "Tổ chức nước ngoài" (foreign org) — deliberately the simplest variant since it takes a free-text `address` instead of the cascading Tỉnh/Thành-phố → Phường/Xã pickers a domestic org (`Khách hàng tổ chức`) or individual (`Khách hàng cá nhân`) needs. `groupCode: "Anonymous"` matches the default "Mã nhóm" the create form itself ships with.

**Response on success:** `{"data":{"id":<numeric>,"customerCode":"FTI2607XX","taxNo":"FTINN0XXX"},"status":"OK",...}` — `data.customerCode` is the value to feed into `opportunity.api`'s `customerId`.

Confirmed end-to-end 2026-08-27: created `FTI260728` this way, then successfully created a real opportunity (`CH00085364`) against it with no issues.

**Second variant confirmed 2026-08-27 — Cá nhân nước ngoài (individual):** `customerType: 1` + `profileType: 2` (NOT 4 — profileType numbering is independent per customerType, don't assume it carries over). Extra required fields beyond the org set: `titleId` (danh xưng, `1` = Ông), `identifyId` (passport/CMND number), `passportDate`, `issuedBy`, `countryCode`. **`customerName` is validated against what looks like a real-person-name pattern for `customerType: 1`** — a name containing digits 400s with `"Tên khách hàng không hợp lệ, kiểm tra lại khoảng trắng và ký tự đặc biệt!"` (confirmed live; the org variant has no such restriction, digits are fine there for uniqueness). Confirmed live: `FTI260730`/`FTI260731`/`FTI260733`/`FTI260734` created this way.

Both variants deliberately skip the Tỉnh/Thành-phố → Phường/Xã cascading pickers (`profileType` combos that need them return `"Vui lòng chọn Tỉnh/Thành phố!"` etc. — swept live across `customerType` 1-3 × `profileType` 1-5, see git history of this file if the full matrix is ever needed again).

Implemented as `CreateRandomCustomerGenerator.java` (`customer.create-random`) — `customerKind` param (`to-chuc-nuoc-ngoai` default / `ca-nhan-nuoc-ngoai`) picks which shape to send; org randomizes `customerName` with a numeric suffix (digits OK), individual randomizes from small surname/given-name word pools (digits NOT OK, so entropy is lower — a name collision is possible on repeat runs, surfaces as the 400 above, just re-click). Wired to a "Loại khách hàng" `<select>` + "Tạo KH mới" button next to the Customer ID field on the opportunity.api card (`mountCreateCustomerButton` in app.js, per user request 2026-08-27 — the required type choice must be a visible, user-picked option, not silently hardcoded) — logs in as `truongnv64` (same CM account as the HĐ/PL card) via the cached CM token, calls the generator with the selected kind, fills the returned `customerCode` straight into the field.

## Open items before trusting this contract fully

1. ~~Confirm `priority` numeric codes~~ — ✅ done 2026-08-25, see table above.
2. ~~Get the real `serviceGroup`/`serviceType` label→code map~~ — ✅ done 2026-08-25, see table above.
3. ~~Find the right lookup for TPP/BGĐ~~ — ✅ done 2026-08-25, see table above (`bam/employees/{email}/department-managers` and `.../central-managers`). ~~AM lookup~~ — ✅ done 2026-08-26, see table above (`bam/sales`, role-gated).
4. Re-verify `successRate` valid range (0-99 per URD doc vs 0-100 assumed elsewhere) directly against this API.
5. Capture one real success response body to know the created-ID field name.
6. Confirm whether `tppCanApproval`/`usersCanApproval` ever legitimately hold more than one entry.
