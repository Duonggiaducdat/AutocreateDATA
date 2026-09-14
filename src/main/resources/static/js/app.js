/* =========================================================================
 *  app.js — controller. Wires the API client to the UI layer.
 *  No HTTP details, no DOM helpers — just orchestration.
 * ========================================================================= */
(function () {
  'use strict';

  // Default to same-origin so this works whether the SPA is served by the
  // Spring Boot backend (production) or a separate dev server (set
  // window.AUTODATA_API_BASE in that case).
  const API_BASE = (window.AUTODATA_API_BASE != null)
      ? window.AUTODATA_API_BASE
      : window.location.origin;
  const api = new window.AutoDataApi.ApiClient({ baseUrl: API_BASE });
  const UI  = window.AutoDataUI;

  // Mounted in index.html footer for visibility.
  const apiBaseLabel = document.getElementById('api-base');
  if (apiBaseLabel) apiBaseLabel.textContent = API_BASE || '(same origin)';

  const MASTER_TYPE = 'full-flow';
  const API_GEN_TYPE = 'opportunity.api';

  /* ---------- state ---------- */
  const state = {
    masterMeta: null,
    apiMeta: null,
    servicesConfig: null,
  };

  const handlers = {
    /** Card "Generate" click. */
    async onGenerate(meta, values, card) {
      card._setBusy(true);
      try {
        const resp = await api.generate(meta.type, values);
        card._setResult(resp.result, null);
        const suffix = values.stopAfter ? ` (stopped after ${values.stopAfter})` : '';
        UI.toast('success', `${meta.displayName} ✓${suffix}`);

        // opportunity.api: optionally chain Đánh giá (always, if ticked) and
        // Phê duyệt (only if ticked AND revenue >= 300tr, per BR03) right
        // after a successful creation — see mountAutoFlowCheckboxes().
        if (meta.type === API_GEN_TYPE) {
          const code = resp.result && resp.result.data && resp.result.data.opportunityCode;
          if (code) await runPostCreateFlow(card, values, code);
        }
      } catch (err) {
        card._setResult(null, err);
        UI.toast('error', `${meta.displayName} failed: ${err.message || err}`);
      } finally {
        card._setBusy(false);
      }
    },
  };

  const DEFAULT_OTP_PASSWORD = 'ISC22QC';

  /* ---------- random test-data helpers (user request 2026-09-03: pre-fill
     the Ivoice card's contact/IP/date fields so there's nothing left to
     type by hand) ---------- */
  const RAND_GIVEN_NAMES = ['An', 'Binh', 'Chi', 'Dung', 'Giang', 'Hoa', 'Khang', 'Linh', 'Minh', 'Nam', 'Phuc', 'Quyen', 'Thao', 'Tuan', 'Yen'];
  const RAND_SURNAMES = ['Nguyen', 'Tran', 'Le', 'Pham', 'Hoang', 'Vu', 'Dang', 'Bui', 'Do', 'Ngo'];
  function randVnName() {
    // No digits — same person-name validation pattern documented for the
    // BMS/CM contact fields (digits get 400'd), so keep it letters-only.
    const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
    return `${pick(RAND_SURNAMES)} Van ${pick(RAND_GIVEN_NAMES)}`;
  }
  function randVnPhone() {
    return '09' + String(Math.floor(10000000 + Math.random() * 89999999));
  }
  function randEmail(nameHint) {
    const slug = (nameHint || 'qa').toLowerCase().replace(/[^a-z0-9]+/g, '') + Math.floor(Math.random() * 9999);
    return `${slug}@example-test.vn`;
  }
  function randHouseNumber() {
    return String(1 + Math.floor(Math.random() * 300));
  }
  function randIp() {
    const octet = () => Math.floor(Math.random() * 254) + 1;
    return `10.${octet()}.${octet()}.${octet()}`;
  }
  function todayMMDDYYYY() {
    const d = new Date();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${mm}/${dd}/${d.getFullYear()}`;
  }

  /** Decodes a JWT's payload (no signature check — this is a client-side UX convenience, not a security boundary). Returns null on any parse failure. */
  function decodeJwt(token) {
    try {
      const payload = token.split('.')[1];
      const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
      const json = decodeURIComponent(
        atob(base64).split('').map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join('')
      );
      return JSON.parse(json);
    } catch {
      return null;
    }
  }

  /** JWT claims here use full schema-URI keys (e.g. ".../identity/claims/role") — match by suffix instead of exact key. */
  function jwtClaim(payload, shortName) {
    if (!payload) return null;
    if (payload[shortName] !== undefined) return payload[shortName];
    const lower = shortName.toLowerCase();
    for (const k of Object.keys(payload)) {
      if (k.toLowerCase().endsWith(lower)) return payload[k];
    }
    return null;
  }

  // Cả ivoice.create-registration-detail (InsertRegistrationIframe) và
  // bms.insert-registration (InsertRegistration) trả HTTP 200 ngay cả khi
  // THẤT BẠI ở tầng nghiệp vụ — lỗi thật nằm trong body JSON, không phải
  // status code. User report 2026-09-07: UI từng hiện nhầm ✓ cho case này.
  // 2 hình dạng lỗi đã quan sát được sống:
  //   IVOICE: {"code": 400, "data": "Tạo PĐK không thành công"}
  //   BMS:    {"data": null, "status": "NotOk", "code": 401, "message": "..."}
  function looksLikeFailureResponse(response) {
    if (!response || typeof response !== 'object') return false;
    if (response.status === 'NotOk') return true;
    if (typeof response.code === 'number' && response.code >= 400) return true;
    return false;
  }
  function responseFailureMessage(response) {
    if (!response) return '';
    return response.message || response.data || '';
  }

  /** Same short-username -> login-email normalization as amEmail() in mountOpportunityPickers. */
  function toLoginEmail(username) {
    const v = (username || '').trim();
    if (!v) return null;
    return v.includes('@') ? v : v + '@fpt.com';
  }

  // Per-tab token cache, keyed by login email — decoded a real token live
  // 2026-08-25 and confirmed the BMS-issued JWT is valid ~63 minutes from
  // login; TTL here is kept well under that (50 min) as a safety margin
  // (clock drift, network latency) so a fresh Selenium login (~10-15s) only
  // happens once per account per ~50 min instead of on every single
  // create→evaluate/approve run. Cleared on page reload (in-memory only —
  // not worth persisting across reloads given the short TTL).
  const TOKEN_CACHE_TTL_MS = 30 * 60 * 1000; // 30 phút — JWT BMS có TTL ~60 phút, giữ 30 để có margin
  const tokenCache = new Map(); // email -> { token, fetchedAt }

  async function getCachedOrFreshToken(email, { forceRefresh = false } = {}) {
    const cached = tokenCache.get(email);
    if (!forceRefresh && cached && (Date.now() - cached.fetchedAt) < TOKEN_CACHE_TTL_MS) {
      return { token: cached.token, cached: true };
    }
    tokenCache.delete(email); // invalidate trước khi lấy mới
    const resp = await api.generate('auth.get-token-api', { username: email, password: DEFAULT_OTP_PASSWORD });
    const token = resp.result.data.accessToken;
    tokenCache.set(email, { token, fetchedAt: Date.now() });
    return { token, cached: false };
  }

  // Separate cache — the CM app issues a completely different JWT (extra
  // TICKET/DVC/DPC claims) via auth.get-cm-token, not interchangeable with
  // the bms-service one above. See GetCmTokenGenerator.java / memory/api-contracts.md.
  const cmTokenCache = new Map(); // email -> { token, fetchedAt }

  async function getCachedOrFreshCmToken(email) {
    const cached = cmTokenCache.get(email);
    if (cached && (Date.now() - cached.fetchedAt) < TOKEN_CACHE_TTL_MS) {
      return { token: cached.token, cached: true };
    }
    const resp = await api.generate('auth.get-cm-token', { username: email, password: DEFAULT_OTP_PASSWORD });
    const token = resp.result.data.cmToken;
    cmTokenCache.set(email, { token, fetchedAt: Date.now() });
    return { token, cached: false };
  }

  /**
   * Runs after a successful opportunity.api creation. Reads the two
   * autoflow checkboxes (see mountAutoFlowCheckboxes) and, if ticked, calls
   * the evaluation.evaluate / evaluation.approve generators (added
   * 2026-08-25, see memory/api-contracts.md "Evaluate"/"Approve Opportunity").
   *
   * Each step logs in as its OWN role — reusing the creator's (Sale/AM)
   * token for evaluate/approve would let one account silently perform every
   * step, which doesn't match real BMS role separation (Sale tạo → TPP đánh
   * giá → BGĐ phê duyệt) and defeats the point of testing role-gated
   * actions. So: TPP đánh giá logs in as the FIRST person picked in the
   * "TPP đánh giá" chip list (tppCanApproval), BGĐ phê duyệt logs in as the
   * first person picked in "BGĐ phê duyệt" (usersCanApproval) — both via a
   * fresh auth.get-token call, same OTP-bypass password as the "Lấy Token"
   * button. Approve is skipped (not an error) when revenue is below the
   * 300tr BR03 threshold, or when evaluate was requested but failed.
   */
  async function runPostCreateFlow(card, values, opportunityCode) {
    const evalCb = card.querySelector('[data-autoflow="evaluate"]');
    const apprCb = card.querySelector('[data-autoflow="approve"]');
    const noteCb = card.querySelector('[data-autoflow="stage-note"]');
    const quoteCb = card.querySelector('[data-autoflow="quotation"]');
    const wantEvaluate = !!(evalCb && evalCb.checked);
    const wantApprove = !!(apprCb && apprCb.checked);
    const wantNote = !!(noteCb && noteCb.checked);
    const wantQuote = !!(quoteCb && quoteCb.checked);
    if (!wantEvaluate && !wantApprove && !wantNote && !wantQuote) return;

    const panel = card.querySelector('.gen-result');
    if (!panel) return;
    const status = UI.h('div', { class: 'autoflow-status' });
    panel.appendChild(status);

    const addLine = () => {
      const line = UI.h('div', { class: 'autoflow-line' }, '');
      status.appendChild(line);
      return line;
    };

    const revenue = Number(String(values.expectedRevenue || '0').replace(/[^\d]/g, '')) || 0;

    /** Logs in as `username` (role-specific account) and returns its bearer token (cached, see getCachedOrFreshToken), or null + a line update on failure. */
    async function loginAs(username, roleLabel, line) {
      const email = toLoginEmail(username);
      if (!email) {
        line.textContent = `✗ Chưa chọn ${roleLabel} — không có tài khoản để đăng nhập`;
        line.classList.add('is-error');
        return null;
      }
      line.textContent = `⏳ Đăng nhập ${roleLabel} (${email})…`;
      try {
        const { token, cached } = await getCachedOrFreshToken(email);
        if (cached) line.textContent = `✓ Dùng lại token ${roleLabel} (${email}, còn hạn)`;
        return token;
      } catch (err) {
        line.textContent = `✗ Đăng nhập ${roleLabel} lỗi: ` + (err.message || err);
        line.classList.add('is-error');
        return null;
      }
    }

    let evaluated = false;
    if (wantEvaluate) {
      const line = addLine();
      const tppUsername = (values.tppCanApproval || '').split(',')[0];
      const tppToken = await loginAs(tppUsername, 'TPP', line);
      if (tppToken) {
        line.textContent = '⏳ Đang đánh giá (TPP)…';
        try {
          await api.generate('evaluation.evaluate', { bearerToken: tppToken, opportunityId: opportunityCode });
          line.textContent = '✓ Đánh giá thành công (TPP: ' + toLoginEmail(tppUsername) + ')';
          evaluated = true;
        } catch (err) {
          line.textContent = '✗ Đánh giá lỗi: ' + (err.message || err);
          line.classList.add('is-error');
        }
      }
    }

    if (wantApprove) {
      const line = addLine();
      if (revenue < 300000000) {
        line.textContent = '— Bỏ qua Phê duyệt (doanh thu < 300 triệu, không cần phê duyệt)';
      } else if (wantEvaluate && !evaluated) {
        line.textContent = '— Bỏ qua Phê duyệt (đánh giá thất bại)';
        line.classList.add('is-error');
      } else {
        const bgdUsername = (values.usersCanApproval || '').split(',')[0];
        const bgdToken = await loginAs(bgdUsername, 'BGĐ', line);
        if (bgdToken) {
          line.textContent = '⏳ Đang phê duyệt (BGĐ)…';
          try {
            await api.generate('evaluation.approve', { bearerToken: bgdToken, opportunityId: opportunityCode });
            line.textContent = '✓ Phê duyệt thành công (BGĐ: ' + toLoginEmail(bgdUsername) + ')';
          } catch (err) {
            line.textContent = '✗ Phê duyệt lỗi: ' + (err.message || err);
            line.classList.add('is-error');
          }
        }
      }
    }

    if (wantNote) {
      const line = addLine();
      const noteText = card.querySelector('[data-autoflow="stage-note-text"]');
      const note = (noteText && noteText.value.trim()) || 'Chuyển giai đoạn tự động qua tool';
      line.textContent = '⏳ Đang chuyển giai đoạn Vòng đời cơ hội…';
      try {
        // opportunity.stage-advance actually transitions the opportunity through its
        // remaining stages (real "Chuyển giai đoạn" calls, progress -> 100%) — switched
        // from the note-only opportunity.stage-note after the user pointed out entering
        // a note alone didn't actually move the stage forward (2026-08-25).
        const resp = await api.generate('opportunity.stage-advance', {
          bearerToken: values.bearerToken, opportunityId: opportunityCode, note,
        });
        line.textContent = `✓ Đã chuyển ${resp.result.data.stagesAdvanced} giai đoạn`;
      } catch (err) {
        line.textContent = '✗ Chuyển giai đoạn lỗi: ' + (err.message || err);
        line.classList.add('is-error');
      }
    }

    if (wantQuote) {
      const line = addLine();
      const priceInput = card.querySelector('[data-autoflow="quotation-price"]');
      const pricePerService = (priceInput && priceInput.value.trim()) || '1';
      line.textContent = '⏳ Đang tạo báo giá…';
      try {
        const resp = await api.generate('opportunity.quotation', {
          bearerToken: values.bearerToken, opportunityId: opportunityCode, pricePerService,
        });
        line.textContent = `✓ Đã tạo báo giá ${resp.result.data.servicesCount} dịch vụ (tổng ${resp.result.data.totalValue} VND)`;
      } catch (err) {
        line.textContent = '✗ Tạo báo giá lỗi: ' + (err.message || err);
        line.classList.add('is-error');
      }
    }
  }

  /* ---------- rendering ---------- */
  function renderMaster() {
       const host = document.getElementById('catalog');
       host.innerHTML = '';

       // Master Flow (old Selenium multi-step pipeline card) hidden per user
       // request 2026-08-26 — the direct-API opportunity.api card + its
       // auto-flow checkboxes below fully replace it, faster and less flaky.
       // state.masterMeta is still fetched/kept (harmless) in case it's ever
       // needed again; just not rendered.

       // Direct-API generator (no Selenium) — paste a bearer token, one HTTP
       // call, done. Rendered generically from its ParamSpec catalog (same
       // renderer the old multi-card catalog used) so the token field is
       // just a normal PASSWORD param, nothing hardcoded here.
       if (state.apiMeta) {
         const apiWrapper = document.createElement('div');
         apiWrapper.className = 'master-grid';
         apiWrapper.style.marginTop = '2rem';
         const apiCard = UI.renderCard(state.apiMeta, handlers);
         apiWrapper.appendChild(apiCard);
         host.appendChild(apiWrapper);
         mountOpportunityPickers(apiCard);
       }

       // Standalone card — NOT tied to opportunity.api's create flow, since
       // it operates on a registration that already exists (see the create
       // card below for the step that makes one); this card only covers the
       // two APPROVE steps on whatever regCode the user already has sitting
       // in "chờ duyệt HĐ/PL".
       const hdplWrapper = document.createElement('div');
       hdplWrapper.className = 'master-grid';
       hdplWrapper.style.marginTop = '1.5rem';
       hdplWrapper.appendChild(mountHdPlApprovalCard());
       host.appendChild(hdplWrapper);
     }

  // "Ivoice" — the create-detail step for "Đấu số thoại" services, on its
  // own page (user request 2026-09-03: a nav-bar link, not another card
  // stacked on the home page). Reverse-engineered 2026-09-03 from a real
  // captured InsertRegistrationIframe request. 4 sub-loại per the same
  // request: Điện thoại cố định / 1800 / 1900 / CSKH — only Điện thoại cố
  // định has a captured payload so far, the other 3 render as "chưa hỗ
  // trợ" until their own InsertRegistrationIframe body is captured the
  // same way. Mounted once (not on every renderMaster() call).
  function renderIvoicePage() {
    const ivoiceHost = document.getElementById('ivoice-page-host');
    if (!ivoiceHost || ivoiceHost.childElementCount > 0) return; // already mounted
    const wrapper = document.createElement('div');
    wrapper.className = 'master-grid';
    // 1 card, 1 form duy nhất (user request 2026-09-07: "bên dưới điền
    // luôn trong 1 form luôn chứ vậy chi cho lằng nhằng" — trước đó tách
    // riêng 1 card "Xác định yêu cầu" + 1 card "Thêm chi tiết", không còn
    // cần thiết từ khi phần hành động chỉ còn 1 nút).
    wrapper.appendChild(mountIvoiceCard());
    ivoiceHost.appendChild(wrapper);
  }

  /**
   * Simple 2-page router (Home "/" / Ivoice "/ivoice") — real address-bar
   * URL via the History API (user request 2026-09-03), not just a JS
   * show/hide with no URL change. A direct hit or reload of "/ivoice"
   * itself is handled server-side (see WebConfig#addViewControllers, which
   * forwards it to the same index.html shell) — this just also has to
   * show the right page ON LOAD when landing on that path, and keep
   * back/forward browser buttons working via popstate.
   */
  // Danh sách các service pages (ngoài home)
  const SERVICE_PAGES = ['ivoice', 'sm', 'mes', 'idc', 'll'];

  function showPageFor(pathname) {
    const active = SERVICE_PAGES.find(s => pathname === '/' + s) || null;
    if (active === 'ivoice') renderIvoicePage();

    // Hiện/ẩn home
    const isHome = !active;
    document.getElementById('home-hero').hidden = !isHome;
    document.getElementById('catalog').hidden = !isHome;
    document.getElementById('home-activity-log').hidden = !isHome;

    // Hiện/ẩn từng service page
    SERVICE_PAGES.forEach(s => {
      const el = document.getElementById(s + '-page');
      if (el) el.hidden = (active !== s);
    });

    // Active state cho nav links
    SERVICE_PAGES.forEach(s => {
      const link = document.getElementById('nav-' + s);
      if (link) link.classList.toggle('is-active', active === s);
    });
  }

  function navigateTo(pathname) {
    if (location.pathname !== pathname) history.pushState({}, '', pathname);
    showPageFor(pathname);
    window.scrollTo(0, 0);
  }

  function goToHomePage() { navigateTo('/'); }

  // Nav links
  SERVICE_PAGES.forEach(s => {
    const link = document.getElementById('nav-' + s);
    if (link) link.addEventListener('click', (e) => { e.preventDefault(); navigateTo('/' + s); });
  });
  document.getElementById('nav-home').addEventListener('click', (e) => { e.preventDefault(); goToHomePage(); });

  // Back links từ mỗi service page
  document.querySelectorAll('.service-back').forEach(el => {
    el.addEventListener('click', (e) => { e.preventDefault(); goToHomePage(); });
  });
  // legacy ivoice-back (vẫn giữ để không break)
  const ivoiceBack = document.getElementById('ivoice-back');
  if (ivoiceBack) ivoiceBack.addEventListener('click', (e) => { e.preventDefault(); goToHomePage(); });

  window.addEventListener('popstate', () => showPageFor(location.pathname));
  showPageFor(location.pathname);

  // Fixed lookup identity for registration.lookup — any authenticated BMS
  // account can run that search regardless of assignment (confirmed live
  // 2026-08-25), so reuse the same convention account already used for the
  // CM step instead of asking the user to supply yet another login.
  const HDPL_LOOKUP_EMAIL = 'truongnv64@fpt.com';

  /**
   * Card "Duyệt HĐ/PL (BMS + CM)" — user request 2026-08-27: identify by
   * "Mã YC" (regCode) instead of opportunity code, auto-discover who the
   * assigned BGĐ is (no more manual BGĐ input), auto-login as that BGĐ, and
   * run straight through. Chain: registration.lookup (find opportunityId +
   * registrationId + BGĐ username from regCode) → login as that BGĐ →
   * registration.approve-bms → login as the CM account → registration.approve-cm.
   * Hand-built (not the generic ParamSpec renderer) because it needs its own
   * multi-role login orchestration, same spirit as runPostCreateFlow's
   * per-step loginAs().
   */
  function mountHdPlApprovalCard() {
    const regCodeInput = UI.h('input', { class: 'app-input', type: 'text', placeholder: 'vd: CMR2614586' });
    const cmInput = UI.h('input', { class: 'app-input', type: 'text', value: 'truongnv64' });
    const status = UI.h('div', { class: 'autoflow-status' });

    const addLine = () => {
      const line = UI.h('div', { class: 'autoflow-line' }, '');
      status.appendChild(line);
      return line;
    };

    const field = (labelText, input, hint) => UI.h('label', { class: 'd-block' },
      UI.h('div', { class: 'field-label' },
        UI.h('span', { class: 'lbl' }, labelText),
        hint ? UI.h('span', { class: 'hint' }, hint) : null),
      input);

    const button = UI.h('button', {
      class: 'btn-generate',
      type: 'button',
      onclick: async () => {
        status.innerHTML = '';
        const regCode = regCodeInput.value.trim();
        const cmEmail = toLoginEmail(cmInput.value.trim());
        if (!regCode || !cmEmail) {
          const line = addLine();
          line.textContent = '✗ Điền đủ Mã YC và tài khoản CM';
          line.classList.add('is-error');
          return;
        }

        button.disabled = true;
        try {
          const lookupLine = addLine();
          lookupLine.textContent = `⏳ Đang tra Mã YC ${regCode}…`;
          let opportunityId, bgdUsername;
          try {
            const { token } = await getCachedOrFreshToken(HDPL_LOOKUP_EMAIL);
            const resp = await api.generate('registration.lookup', { bearerToken: token, regCode });
            const d = resp.result.data;
            opportunityId = d.opportunityId;
            bgdUsername = d.bgdUsername;
            lookupLine.textContent = `✓ Tìm thấy ${opportunityId} — BGĐ phụ trách: ${bgdUsername}` + (d.alreadyApproved ? ' (đã duyệt BMS từ trước)' : '');
          } catch (err) {
            lookupLine.textContent = '✗ Tra Mã YC lỗi: ' + (err.message || err);
            lookupLine.classList.add('is-error');
            return;
          }

          const bgdEmail = toLoginEmail(bgdUsername);
          const bmsLine = addLine();
          bmsLine.textContent = `⏳ Đăng nhập BGĐ (${bgdEmail})…`;
          let bgdToken;
          try {
            const { token, cached } = await getCachedOrFreshToken(bgdEmail);
            bgdToken = token;
            bmsLine.textContent = cached ? `✓ Dùng lại token BGĐ (${bgdEmail}, còn hạn)` : `✓ Đăng nhập BGĐ thành công (${bgdEmail})`;
          } catch (err) {
            bmsLine.textContent = '✗ Đăng nhập BGĐ lỗi: ' + (err.message || err);
            bmsLine.classList.add('is-error');
            return;
          }

          const approveLine = addLine();
          approveLine.textContent = '⏳ Đang duyệt HĐ/PL bên BMS…';
          try {
            const resp = await api.generate('registration.approve-bms', { bearerToken: bgdToken, opportunityId });
            approveLine.textContent = `✓ BMS duyệt xong (${resp.result.data.registrations.length} registration)`;
          } catch (err) {
            approveLine.textContent = '✗ Duyệt BMS lỗi: ' + (err.message || err);
            approveLine.classList.add('is-error');
            return;
          }

          const cmLoginLine = addLine();
          cmLoginLine.textContent = `⏳ Đăng nhập CM (${cmEmail})…`;
          let cmToken;
          try {
            const { token, cached } = await getCachedOrFreshCmToken(cmEmail);
            cmToken = token;
            cmLoginLine.textContent = cached ? `✓ Dùng lại token CM (${cmEmail}, còn hạn)` : `✓ Đăng nhập CM thành công (${cmEmail})`;
          } catch (err) {
            cmLoginLine.textContent = '✗ Đăng nhập CM lỗi: ' + (err.message || err);
            cmLoginLine.classList.add('is-error');
            return;
          }

          const line = addLine();
          line.textContent = `⏳ Đang duyệt CM cho ${regCode}…`;
          try {
            const resp = await api.generate('registration.approve-cm', { cmToken, regCode });
            const d = resp.result.data;
            line.textContent = d.skipped
              ? `✓ ${regCode} đã hoàn thành từ trước (hợp đồng ${d.contractNo || '?'})`
              : `✓ ${regCode} duyệt CM xong — hợp đồng ${d.contractNo || '?'} / PL ${d.subContractNo || '?'}`;
          } catch (err) {
            line.textContent = `✗ Duyệt CM lỗi cho ${regCode}: ` + (err.message || err);
            line.classList.add('is-error');
          }
        } finally {
          button.disabled = false;
        }
      },
    }, 'Duyệt HĐ/PL');

    return UI.h('article', { class: 'gen-card' },
      UI.h('header', { class: 'gen-header' },
        UI.h('div', { class: 'd-flex justify-content-between align-items-start gap-2' },
          UI.h('div', null,
            UI.h('div', { class: 'gen-eyebrow' }, UI.h('span', { class: 'dot' }), 'Opportunity'),
            UI.h('h3', { class: 'gen-title' }, 'Duyệt HĐ/PL (BMS + CM)')),
          UI.h('span', { class: 'gen-class' }, 'registration.lookup + approve-bms + -cm')),
        UI.h('p', { class: 'gen-signature' }, 'Tra Mã YC → tự tìm BGĐ phụ trách → duyệt BMS → duyệt CM'),
        UI.h('p', { class: 'gen-signature', style: 'color: var(--ink-300); margin-top: 4px;' },
          'Chạy SAU KHI đã tạo yêu cầu đăng ký triển khai (HĐ/PL) bằng tay — tool này chỉ DUYỆT, không tạo.')),
      UI.h('div', { class: 'gen-body' },
        field('Mã YC', regCodeInput, 'mã đăng ký CM, vd CMR2614586'),
        field('Tài khoản CM (duyệt CM)', cmInput, 'mặc định truongnv64'),
        status),
      UI.h('div', { class: 'gen-footer' }, button));
  }

  // Selenium logins are cookie-jar based here (not a single bearer token),
  // so the cache stores the joined "name=value; ..." header string instead
  // of a JWT. Same TTL reasoning as tokenCache/cmTokenCache above.
  const ivoiceSessionCache = new Map(); // email -> { cookieHeader, fetchedAt }

  async function getCachedOrFreshIvoiceSession(email) {
    const cached = ivoiceSessionCache.get(email);
    if (cached && (Date.now() - cached.fetchedAt) < TOKEN_CACHE_TTL_MS) {
      return { cookieHeader: cached.cookieHeader, cached: true };
    }
    const resp = await api.generate('auth.get-ivoice-session-api', { username: email, password: DEFAULT_OTP_PASSWORD });
    const cookieHeader = resp.result.data.cookieHeader;
    ivoiceSessionCache.set(email, { cookieHeader, fetchedAt: Date.now() });
    return { cookieHeader, cached: false };
  }

  // Legacy bms/RegistrationMagt_v12/* area — a SEPARATE cookie jar from
  // ivoiceSessionCache above, confirmed live 2026-09-07: reusing the IVOICE
  // jar against InsertRegistration got a real HTTP 401, and the 2 areas
  // actively wipe each other's cookies within one browser session (user
  // confirmed this matches real browser behavior, not a Selenium artifact —
  // clicking BMS → IVOICE really does cross into a separate auth area). See
  // GetBmsLegacySessionGenerator.java / memory/api-contracts.md.
  // Cache key theo "email|refCode" (KHÔNG chỉ email) — user report 2026-09-07:
  // 2 refCode khác nhau (CH00085443_96442, CH00085444_...) đều fail y hệt
  // "không có quyền thao tác" dù RegistrationId thứ 2 hoàn toàn mới/sạch,
  // và cả 2 lần log đều ghi "Dùng lại session... còn hạn" — tức cookie mint
  // cho refCode ĐẦU TIÊN đang bị TÁI SỬ DỤNG cho refCode SAU, vì cache cũ
  // chỉ khoá theo email. Nếu server gắn phiên FTIBMS-Auth với refCode cụ
  // thể lúc mint (session server-side "đang sửa registration X"), dùng nó
  // cho registration Y khác sẽ bị từ chối đúng như quan sát được — khớp
  // hoàn hảo. Sửa: khoá cache theo cả refCode, luôn re-mint đúng session
  // khi refCode đổi (chấp nhận chậm hơn ~12s mỗi refCode mới, nhưng đúng).
  const bmsLegacySessionCache = new Map(); // "email|refCode" -> { cookieHeader, fetchedAt }

  async function getCachedOrFreshBmsLegacySession(email, refCode) {
    const cacheKey = email + '|' + refCode;
    const cached = bmsLegacySessionCache.get(cacheKey);
    if (cached && (Date.now() - cached.fetchedAt) < TOKEN_CACHE_TTL_MS) {
      return { cookieHeader: cached.cookieHeader, cached: true };
    }
    // Dùng pure API (auth.get-bms-legacy-session-api) — visit CreateRegist 2 lần:
    // lần 1 để lấy FTIBMS-Auth, lần 2 để server set FTIBMS + jwt_token_id.
    // InsertRegistration cần đủ cả 3 cookies — Selenium chỉ poll đến FTIBMS-Auth nên thiếu 2 còn lại → 401.
    const resp = await api.generate('auth.get-bms-legacy-session-api', { username: email, password: DEFAULT_OTP_PASSWORD, refCode });
    const cookieHeader = resp.result.data.cookieHeader;
    bmsLegacySessionCache.set(cacheKey, { cookieHeader, fetchedAt: Date.now() });
    return { cookieHeader, cached: false };
  }

  /**
   * "Ivoice — Tạo yêu cầu HĐ/PL" — MỘT form duy nhất (user request
   * 2026-09-07: "bên dưới điền luôn trong 1 form luôn chứ vậy chi cho
   * lằng nhằng" — trước đó tách 2 card riêng: "Xác định yêu cầu" (Tài
   * khoản Sale/Mã cơ hội/Loại dịch vụ) + "Thêm chi tiết" (mọi thứ còn
   * lại), giao tiếp qua 1 object `identity` với cơ chế `onChange`
   * subscribe. Từ khi 2 card hành động cũ gộp thành 1 nút "Thêm chi tiết"
   * duy nhất, việc tách riêng card định danh không còn cần thiết (không
   * còn 2 card độc lập nào cần "dùng chung" nữa) — gộp thẳng vào đây,
   * dùng biến cục bộ thay vì object/getter/subscribe.
   *
   * 1 nút "Thêm chi tiết" chạy tuần tự CẢ HAI lệnh gọi thật, theo ĐÚNG thứ
   * tự đã xác nhận sống 2026-09-07 (xem walkthrough Chrome đầy đủ trong
   * memory/api-contracts.md):
   *   1. ivoice.create-registration-detail (POST InsertRegistrationIframe
   *      — lưu chi tiết số điện thoại vào IVOICE, tương ứng nút "Thêm chi
   *      tiết" trong Bước 2 "Thông tin dịch vụ")
   *   2. bms.insert-registration (POST InsertRegistration — nút "Tạo" ở
   *      Bước 3 "Xác Nhận Thông Tin" của legacy BMS, đẩy registration sang
   *      "Chờ BGĐ duyệt")
   * (Thứ tự BAN ĐẦU khi mới gộp 2 card hành động là ngược lại — đã sửa
   * sau khi live test cho thấy bmsv2 chặn không cho qua Bước 3 nếu chưa có
   * "Thông tin triển khai", tức bước IVOICE phải chạy trước.)
   *
   * Log hiển thị RAW RESPONSE của cả 2 bước — response thật của cả 2 API
   * này lần đầu quan sát được là qua bài test sống 2026-09-07 (trước đó
   * chỉ có request, vì "Copy as cURL" không chụp response).
   *
   * ListBillingContact/TechRegistrations là form thật (thêm/xoá dòng),
   * không phải JSON thô — khớp đúng modal "Thêm liên hệ thu cước"/"Thêm
   * liên hệ kỹ thuật" đã thao tác trên legacy BMS thật. ListDeployment
   * KHÔNG có input riêng — tự build từ đúng field "chi tiết triển khai
   * IVOICE" đã điền ở trên (không bắt nhập 2 lần). ListMember vẫn để JSON
   * thô vì `RefibbmembersId` chưa rõ nguồn lấy tự động.
   *
   * Requires an EXISTING RegistrationId (the initial "Xác nhận tạo yêu cầu
   * hợp đồng" click that mints one is still unreverse-engineered — see
   * memory/api-contracts.md — mọi thứ ở đây chỉ TIẾP TỤC 1 registration đã
   * tồn tại sẵn, chưa tự tạo mới từ đầu được).
   */
  function mountIvoiceCard() {
    const field = (labelText, input, hint) => UI.h('label', { class: 'd-block' },
      UI.h('div', { class: 'field-label' },
        UI.h('span', { class: 'lbl' }, labelText),
        hint ? UI.h('span', { class: 'hint' }, hint) : null),
      input);

    // serviceGroup/serviceType/typeDocContract + their display labels are
    // the real values captured in the InsertRegistration payload for
    // Điện thoại cố định (see memory/api-contracts.md) — confirmed live by
    // the user 2026-09-07: chọn "Điện thoại cố định" thì Nhóm dịch vụ =
    // "Đấu số thoại", Loại dịch vụ = "Điện thoại cố định", Loại văn bản =
    // "Hợp đồng". The other 3 sub-loại have no captured payload yet, so
    // stay unsupported.
    const SERVICE_TYPES = [
      {
        value: 'phone', label: 'Điện thoại cố định', supported: true,
        serviceGroup: '1', serviceGroupLabel: 'Đấu số thoại',
        serviceType: '1',
        typeDocContract: '1', typeDocContractLabel: 'Hợp đồng',
      },
      { value: '1800', label: '1800', supported: false },
      { value: '1900', label: '1900', supported: false },
      { value: 'cskh', label: 'CSKH', supported: false },
    ];

    const saleInput = UI.h('input', { class: 'app-input', type: 'text', placeholder: 'vd: thitlh' });
    const opportunityCodeInput = UI.h('input', { class: 'app-input', type: 'text', placeholder: 'CH00085410' });
    const typeSelect = UI.h('select', { class: 'app-select' },
      ...SERVICE_TYPES.map((t) => UI.h('option', { value: t.value }, t.label)));
    // Disabled (user request 2026-09-07): purely auto-derived from Mã cơ
    // hội via ivoice.lookup-opportunity — nothing to type by hand. Still
    // settable programmatically (disabled only blocks USER edits, not JS
    // assignment) so the auto-lookup handler below keeps working.
    const customerIdInput = UI.h('input', { class: 'app-input', type: 'text', placeholder: 'FTI260739', disabled: true });
    const refCodeInput = UI.h('input', { class: 'app-input', type: 'text', placeholder: 'CH00085410_96362', disabled: true });
    const taxIdInput = UI.h('input', { class: 'app-input', type: 'text', placeholder: 'P37590260', disabled: true });
    // RegistrationId never rendered (user request 2026-09-07: "cái này ko
    // cần hiện") — kept as a detached input purely as an internal value
    // holder, still readable/settable exactly like a normal field.
    const registrationIdInput = UI.h('input', { type: 'hidden' });
    const oppLookupStatus = UI.h('span', { class: 'hint' }, 'tự động lấy khi rời khỏi ô Mã cơ hội (cần điền Tài khoản Sale trước)');

    function currentSaleEmail() { return toLoginEmail(saleInput.value.trim()); }
    function currentServiceMeta() { return SERVICE_TYPES.find((t) => t.value === typeSelect.value); }
    // Thay cho cơ chế identity.onChange(fn) trước đây (khi còn 2 card
    // riêng, cần subscribe qua lại) — giờ mọi thứ cùng 1 scope nên gọi
    // thẳng các hàm phụ thuộc (renderDerivedSummary/loadWards/searchPhones/
    // renderBodyForType, khai báo bằng `function` nên đã hoisted, gọi được
    // dù đứng trước phần định nghĩa của chúng trong file).
    function onIdentityChange() {
      renderDerivedSummary();
      loadWards();
      searchPhones();
      renderBodyForType();
      refreshListMemberDefault();
    }

    // Auto-derive RefCode + Mã số thuế KH + Mã KH + RegistrationId from Mã
    // cơ hội (user request 2026-09-03: "bạn phải tự lấy được chứ" — don't
    // make them type either by hand). Fires on blur (real input event
    // 'change') rather than every keystroke. See
    // LookupOpportunityForIvoiceGenerator.java for the 2-call chain this
    // wraps.
    opportunityCodeInput.addEventListener('change', async () => {
      const saleEmail = currentSaleEmail();
      const opportunityCode = opportunityCodeInput.value.trim();
      if (!opportunityCode) return;
      if (!saleEmail) {
        oppLookupStatus.textContent = '✗ Điền Tài khoản Sale trước để tự động lấy';
        oppLookupStatus.classList.add('is-error');
        return;
      }
      oppLookupStatus.classList.remove('is-error');
      oppLookupStatus.textContent = `⏳ Đang tra cứu ${opportunityCode}…`;
      const doLookup = async (forceRefresh = false) => {
        const { token } = await getCachedOrFreshToken(saleEmail, { forceRefresh });
        return api.generate('ivoice.lookup-opportunity', { bearerToken: token, opportunityCode });
      };
      try {
        let resp;
        try {
          resp = await doLookup(false);
        } catch (err) {
          // 401 → token hết hạn — clear cache và retry với token mới
          if (err.message && (err.message.includes('401') || err.message.includes('Unauthorized'))) {
            oppLookupStatus.textContent = `⏳ Token hết hạn, đang lấy lại…`;
            resp = await doLookup(true); // forceRefresh = true
          } else {
            throw err;
          }
        }
        const d = resp.result.data;
        customerIdInput.value = d.customerId;
        registrationIdInput.value = d.registrationId;
        refCodeInput.value = d.refCode;
        taxIdInput.value = d.taxId;
        oppLookupStatus.textContent = `✓ RefCode=${d.refCode}, KH=${d.customerId}, Mã số thuế=${d.taxId}`;
        onIdentityChange();
      } catch (err) {
        oppLookupStatus.textContent = '✗ Tra cứu lỗi: ' + (err.message || err);
        oppLookupStatus.classList.add('is-error');
      }
    });
    saleInput.addEventListener('change', onIdentityChange);
    typeSelect.addEventListener('change', onIdentityChange);

    const derivedSummary = UI.h('div', { class: 'hint' }, '');
    function renderDerivedSummary() {
      const meta = currentServiceMeta();
      if (!meta) { derivedSummary.textContent = ''; return; }
      if (!meta.supported) {
        derivedSummary.classList.add('is-error');
        derivedSummary.textContent = `⚠️ "${meta.label}" chưa hỗ trợ — chưa capture được payload cho loại dịch vụ này.`;
      } else {
        derivedSummary.classList.remove('is-error');
        derivedSummary.textContent = `Nhóm dịch vụ: ${meta.serviceGroupLabel} · Loại dịch vụ: ${meta.label} · Loại văn bản: ${meta.typeDocContractLabel}`;
      }
    }
    renderDerivedSummary();

    // ----- Bước 2/3 (BMS) — điều khoản hợp đồng -----
    const typeContractCategoryInput = UI.h('input', { class: 'app-input', type: 'text', value: '1' });
    // Dùng CHUNG 1 locationSelect cho cả Registration.Location (bước 2/3)
    // lẫn locationId (IVOICE) — cùng 1 khái niệm Vùng miền/Tỉnh thành,
    // cùng 1 id space (8=HCM, 4=HN) ở cả 2 hệ thống.
    const locationSelect = UI.h('select', { class: 'app-select' },
      UI.h('option', { value: '8' }, 'Thành phố Hồ Chí Minh'),
      UI.h('option', { value: '4' }, 'Thành phố Hà Nội'));
    const locationNameHidden = { get value() { return locationSelect.selectedOptions[0].textContent; } };
    const paymentMethodInput = UI.h('input', { class: 'app-input', type: 'text', value: '2' });
    const limitCreditDayInput = UI.h('input', { class: 'app-input', type: 'text', value: '30' });
    const feeContractInput = UI.h('input', { class: 'app-input', type: 'text', value: '0' });
    const monthlyFeeContractInput = UI.h('input', { class: 'app-input', type: 'text', value: '10' });
    const dateContractMonthInput = UI.h('input', { class: 'app-input', type: 'text', value: '1' });
    const contractValueInput = UI.h('input', { class: 'app-input', type: 'text', value: '0' });
    const notesInput = UI.h('textarea', { class: 'app-input', rows: 3, value: '' });
    const descriptionInput = UI.h('textarea', { class: 'app-input', rows: 3, value: '' });

    // ----- Phường/Xã (IVOICE) — real dropdown, populated by ivoice.get-ward -----
    const wardSelect = UI.h('select', { class: 'app-select' },
      UI.h('option', { value: '' }, 'Điền Tài khoản Sale để tự tải danh sách'));
    const wardIdHidden = { get value() { return wardSelect.value; } };
    const wardNameHidden = { get value() { return wardSelect.selectedOptions[0] ? wardSelect.selectedOptions[0].textContent : ''; } };
    const wardStatus = UI.h('span', { class: 'hint' }, '');
    // Cached alongside wardSelect's own options so the Liên hệ thu cước rows
    // below (mỗi dòng có Phường/Xã riêng) can reuse the SAME already-fetched
    // list instead of calling ivoice.get-ward again per row — confirmed live
    // 2026-09-07 that this ward-id space is the same one legacy BMS's
    // ListBillingContact/ListDeployment `District` field expects (see
    // memory/api-contracts.md quirk note).
    let currentWards = [];
    function buildWardOptions() {
      if (currentWards.length === 0) {
        return [UI.h('option', { value: '' }, 'Chưa có — tải Phường/Xã ở trên trước')];
      }
      return currentWards.map((w) => UI.h('option', { value: String(w.id) }, w.name));
    }
    async function loadWards() {
      const saleEmail = currentSaleEmail();
      if (!saleEmail) return;
      wardStatus.textContent = 'Đang tải Phường/Xã…';
      wardStatus.classList.remove('is-error');
      try {
        const { cookieHeader } = await getCachedOrFreshIvoiceSession(saleEmail);
        const resp = await api.generate('ivoice.get-ward', { cookieHeader, locationId: locationSelect.value });
        const wards = resp.result.data.wards || [];
        currentWards = wards;
        wardSelect.innerHTML = '';
        wards.forEach((w) => wardSelect.appendChild(UI.h('option', { value: String(w.id) }, w.name)));
        if (wards.length > 0) wardSelect.value = String(wards[0].id);
        wardStatus.textContent = `✓ ${wards.length} Phường/Xã (mặc định: ${wards[0] ? wards[0].name : '(không có)'})`;
      } catch (err) {
        wardStatus.textContent = '✗ Tải Phường/Xã lỗi: ' + (err.message || err);
        wardStatus.classList.add('is-error');
      }
    }
    const loadWardsButton = UI.h('button', {
      type: 'button', class: 'btn-generate',
      style: 'margin-top: 6px; padding: 6px 14px; font-size: 12px; width: auto;',
      onclick: async () => {
        if (!currentSaleEmail()) { UI.toast('error', 'Điền Tài khoản Sale trước'); return; }
        loadWardsButton.disabled = true;
        try { await loadWards(); } finally { loadWardsButton.disabled = false; }
      },
    }, 'Tải lại Phường/Xã');
    locationSelect.addEventListener('change', () => {
      wardSelect.innerHTML = '';
      wardSelect.appendChild(UI.h('option', { value: '' }, 'Điền Tài khoản Sale để tự tải danh sách'));
      loadWards();
    });

    // ----- chi tiết triển khai IVOICE — random-filled -----
    const locationNumberInput = UI.h('input', { class: 'app-input', type: 'text', value: randHouseNumber() });
    const randContactName = randVnName();
    const contactNameInput = UI.h('input', { class: 'app-input', type: 'text', value: randContactName });
    const contactPhoneInput = UI.h('input', { class: 'app-input', type: 'text', value: randVnPhone() });
    const contactEmailInput = UI.h('input', { class: 'app-input', type: 'email', value: randEmail(randContactName) });
    const deployDateInput = UI.h('input', { class: 'app-input', type: 'text', value: todayMMDDYYYY() });
    const autoCallSelect = UI.h('select', { class: 'app-select' },
      UI.h('option', { value: '0' }, 'Không'),
      UI.h('option', { value: '1' }, 'Có'));
    const ipMainInput = UI.h('input', { class: 'app-input', type: 'text', value: randIp() });
    const platformSelect = UI.h('select', { class: 'app-select' },
      UI.h('option', { value: '1' }, 'Thường'),
      UI.h('option', { value: '2' }, 'Voice Brandname'));

    let availablePhones = [];
    const selectedPhoneIds = new Set();
    const phoneListBody = UI.h('div', { class: 'phone-list-body' },
      UI.h('p', { class: 'gen-signature', style: 'color: var(--ink-300); margin: 0;' }, 'Điền Tài khoản Sale + Mã số thuế KH để tự tìm số.'));
    const phoneStatus = UI.h('span', { class: 'hint' }, '');

    async function searchPhones() {
      const saleEmail = currentSaleEmail();
      const taxId = taxIdInput.value.trim();
      if (!saleEmail || !taxId) return;
      phoneStatus.textContent = 'Đang tìm số điện thoại…';
      phoneStatus.classList.remove('is-error');
      try {
        const { cookieHeader } = await getCachedOrFreshIvoiceSession(saleEmail);
        const resp = await api.generate('ivoice.get-phone-booked', {
          cookieHeader, locationId: locationSelect.value, taxId, voiceBrandName: platformSelect.value,
        });
        availablePhones = resp.result.data.phones || [];
        selectedPhoneIds.clear();
        renderPhoneList();
        phoneStatus.textContent = `✓ Tìm thấy ${availablePhones.length} số điện thoại`;
      } catch (err) {
        phoneStatus.textContent = '✗ Tìm số điện thoại lỗi: ' + (err.message || err);
        phoneStatus.classList.add('is-error');
      }
    }
    locationSelect.addEventListener('change', searchPhones);
    platformSelect.addEventListener('change', searchPhones);

    function renderPhoneList() {
      phoneListBody.innerHTML = '';
      if (availablePhones.length === 0) {
        phoneListBody.appendChild(UI.h('p', { class: 'gen-signature', style: 'color: var(--ink-300); margin: 0;' },
          'Không có số nào khả dụng cho Tỉnh thành/Nền tảng đã chọn.'));
        return;
      }
      availablePhones.forEach((row) => {
        const id = row.id;
        const checkbox = UI.h('input', {
          type: 'checkbox',
          onchange: (e) => { if (e.target.checked) selectedPhoneIds.add(id); else selectedPhoneIds.delete(id); },
        });
        const priceText = (row.subcriberCharge != null) ? `${row.subcriberCharge.toLocaleString('vi-VN')}đ/tháng` : '';
        phoneListBody.appendChild(UI.h('label', { class: 'd-flex align-items-center gap-2', style: 'padding: 3px 0;' },
          checkbox,
          UI.h('span', { class: 'mono' }, row.phoneNumber || '(?)'),
          UI.h('span', { class: 'hint' }, priceText)));
      });
    }

    // ----- Bước 2/3 (BMS) — ListMember vẫn để JSON thô (RefibbmembersId chưa
    // rõ nguồn lấy tự động, không có picker) -----
    function defaultListMemberJson() {
      return JSON.stringify([{
        Name: currentSaleEmail() ? currentSaleEmail().split('@')[0] : 'thitlh', Position: 1, ContractRate: 100,
        RefibbmembersId: '1000772803', OpportunityId: opportunityCodeInput.value.trim() || 'CH00085412',
      }], null, 2);
    }
    const listMemberJsonInput = UI.h('textarea', {
      class: 'app-input', rows: 4, style: 'font-family: monospace; font-size: 12px;',
      value: defaultListMemberJson(),
    });
    // ❌ Bug thật 2026-09-07: giá trị trên chỉ tính 1 LẦN lúc mount (Mã cơ
    // hội lúc đó còn trống → luôn fallback về "CH00085412" hardcode) —
    // KHÔNG tự cập nhật khi người dùng gõ Mã cơ hội thật sau đó, dẫn tới
    // `ListMember[].OpportunityId` LỆCH với `OpportunityId` top-level của
    // jsonData (VD gửi CH00085443 nhưng ListMember vẫn ghi CH00085412) —
    // server coi là dữ liệu không nhất quán, từ chối với message chung
    // chung "không có quyền/đã hoàn thành". Fix: tự refresh lại mỗi khi
    // Mã cơ hội/Tài khoản Sale đổi, TRỪ KHI người dùng đã tự tay sửa ô
    // này (tránh ghi đè chỉnh sửa thật, ví dụ RefibbmembersId đúng).
    let listMemberEditedByUser = false;
    listMemberJsonInput.addEventListener('input', () => { listMemberEditedByUser = true; });
    function refreshListMemberDefault() {
      if (!listMemberEditedByUser) listMemberJsonInput.value = defaultListMemberJson();
    }

    // ----- Liên hệ thu cước (ListBillingContact) — real form rows, thay
    // JSON thô, khớp đúng modal "Thêm liên hệ thu cước" đã thao tác thật
    // trên legacy BMS 2026-09-07. Rỗng mặc định (giống UI thật "Không có
    // dữ liệu!" cho tới khi bấm "+ Thêm") — bắt buộc >= 1 dòng khi submit.
    const billingContacts = [];
    const billingListBody = UI.h('div', null,
      UI.h('p', { class: 'gen-signature', style: 'color: var(--ink-300); margin: 0;' }, 'Chưa có liên hệ thu cước nào — bấm "+ Thêm" bên dưới.'));
    function renderBillingEmpty() {
      if (billingContacts.length === 0) {
        billingListBody.innerHTML = '';
        billingListBody.appendChild(UI.h('p', { class: 'gen-signature', style: 'color: var(--ink-300); margin: 0;' }, 'Chưa có liên hệ thu cước nào — bấm "+ Thêm" bên dưới.'));
      }
    }
    function addBillingRow() {
      if (billingContacts.length === 0) billingListBody.innerHTML = '';
      const name = randVnName();
      const nameInput = UI.h('input', { class: 'app-input', type: 'text', value: name });
      const phoneInput = UI.h('input', { class: 'app-input', type: 'text', value: randVnPhone() });
      const emailInput = UI.h('input', { class: 'app-input', type: 'email', value: randEmail(name) });
      const addressInput = UI.h('input', { class: 'app-input', type: 'text', value: randHouseNumber() });
      const wardSel = UI.h('select', { class: 'app-select' }, ...buildWardOptions());
      const rowEl = UI.h('div', { class: 'field-grid-3', style: 'align-items: end; margin-bottom: 10px; padding-bottom: 10px; border-bottom: 1px solid var(--ink-700);' },
        field('Người liên hệ', nameInput),
        field('SĐT', phoneInput),
        field('Email', emailInput),
        field('Số nhà, tên đường', addressInput),
        field('Phường/Xã', wardSel, 'theo Vùng miền hiện tại'),
        UI.h('button', {
          type: 'button', class: 'btn-generate', style: 'padding: 6px 14px; font-size: 12px; width: auto;',
          onclick: () => {
            rowEl.remove();
            const idx = billingContacts.indexOf(entry);
            if (idx >= 0) billingContacts.splice(idx, 1);
            renderBillingEmpty();
          },
        }, 'Xoá'));
      const entry = { nameInput, phoneInput, emailInput, addressInput, wardSel, rowEl };
      billingContacts.push(entry);
      billingListBody.appendChild(rowEl);
    }
    const addBillingButton = UI.h('button', {
      type: 'button', class: 'btn-generate', style: 'padding: 6px 14px; font-size: 12px; width: auto; margin-top: 6px;',
      onclick: () => addBillingRow(),
    }, '+ Thêm liên hệ thu cước');

    // ----- Liên hệ kỹ thuật (TechRegistrations) — real form rows, đơn
    // giản hơn (không có địa chỉ). -----
    const techContacts = [];
    const techListBody = UI.h('div', null,
      UI.h('p', { class: 'gen-signature', style: 'color: var(--ink-300); margin: 0;' }, 'Chưa có liên hệ kỹ thuật nào — bấm "+ Thêm" bên dưới.'));
    function renderTechEmpty() {
      if (techContacts.length === 0) {
        techListBody.innerHTML = '';
        techListBody.appendChild(UI.h('p', { class: 'gen-signature', style: 'color: var(--ink-300); margin: 0;' }, 'Chưa có liên hệ kỹ thuật nào — bấm "+ Thêm" bên dưới.'));
      }
    }
    function addTechRow() {
      if (techContacts.length === 0) techListBody.innerHTML = '';
      const name = randVnName();
      const nameInput = UI.h('input', { class: 'app-input', type: 'text', value: name });
      const phoneInput = UI.h('input', { class: 'app-input', type: 'text', value: randVnPhone() });
      const emailInput = UI.h('input', { class: 'app-input', type: 'email', value: randEmail(name) });
      const rowEl = UI.h('div', { class: 'field-grid-3', style: 'align-items: end; margin-bottom: 10px; padding-bottom: 10px; border-bottom: 1px solid var(--ink-700);' },
        field('Người liên hệ', nameInput),
        field('SĐT', phoneInput),
        field('Email', emailInput),
        UI.h('button', {
          type: 'button', class: 'btn-generate', style: 'padding: 6px 14px; font-size: 12px; width: auto;',
          onclick: () => {
            rowEl.remove();
            const idx = techContacts.indexOf(entry);
            if (idx >= 0) techContacts.splice(idx, 1);
            renderTechEmpty();
          },
        }, 'Xoá'));
      const entry = { nameInput, phoneInput, emailInput, rowEl };
      techContacts.push(entry);
      techListBody.appendChild(rowEl);
    }
    const addTechButton = UI.h('button', {
      type: 'button', class: 'btn-generate', style: 'padding: 6px 14px; font-size: 12px; width: auto; margin-top: 6px;',
      onclick: () => addTechRow(),
    }, '+ Thêm liên hệ kỹ thuật');

    // ----- Duyệt BGĐ + CM tự động sau khi Tạo (user request 2026-09-07:
    // "thêm bước BGĐ duyệt và cm duyệt vô luôn khi người dùng tick đi") —
    // tick mới chạy, mặc định TẮT vì đây là hành động phê duyệt thật, có
    // tác động ngoài (đẩy hợp đồng/PL thật). Tái dùng ĐÚNG chain đã chạy ở
    // card "Duyệt HĐ/PL (BMS + CM)" (registration.lookup → approve-bms →
    // approve-cm) — registration.lookup's `regCode` param tự fallback
    // sang match opportunityId khi chưa có Mã YC thật (đăng ký vừa tạo),
    // nên dùng thẳng opportunityCode làm keyword tra cứu, không cần chờ
    // response Tạo trả về Mã YC (chưa quan sát được response thành công
    // thật của InsertRegistration để biết field đó tên gì).
    const autoApproveCheckbox = UI.h('input', { type: 'checkbox' });
    const cmApproverInput = UI.h('input', { class: 'app-input', type: 'text', value: 'truongnv64', style: 'max-width: 200px; display: inline-block;' });

    const status = UI.h('div', { class: 'autoflow-status' });
    const addLine = () => {
      const line = UI.h('div', { class: 'autoflow-line' }, '');
      status.appendChild(line);
      return line;
    };

    async function runAutoApprove(opportunityCode) {
      const lookupLine = addLine();
      lookupLine.textContent = `⏳ Đang tra Mã YC cho ${opportunityCode}…`;
      let opportunityId, bgdUsername;
      try {
        const { token } = await getCachedOrFreshToken(HDPL_LOOKUP_EMAIL);
        const resp = await api.generate('registration.lookup', { bearerToken: token, regCode: opportunityCode });
        const d = resp.result.data;
        opportunityId = d.opportunityId;
        bgdUsername = d.bgdUsername;
        lookupLine.textContent = `✓ Tìm thấy ${opportunityId} — BGĐ phụ trách: ${bgdUsername}` + (d.alreadyApproved ? ' (đã duyệt BMS từ trước)' : '');
      } catch (err) {
        lookupLine.textContent = '✗ Tra Mã YC lỗi: ' + (err.message || err);
        lookupLine.classList.add('is-error');
        return;
      }

      const bgdEmail = toLoginEmail(bgdUsername);
      const bmsLine = addLine();
      bmsLine.textContent = `⏳ Đăng nhập BGĐ (${bgdEmail})…`;
      let bgdToken;
      try {
        const { token, cached } = await getCachedOrFreshToken(bgdEmail);
        bgdToken = token;
        bmsLine.textContent = cached ? `✓ Dùng lại token BGĐ (${bgdEmail}, còn hạn)` : `✓ Đăng nhập BGĐ thành công (${bgdEmail})`;
      } catch (err) {
        bmsLine.textContent = '✗ Đăng nhập BGĐ lỗi: ' + (err.message || err);
        bmsLine.classList.add('is-error');
        return;
      }

      let regCodes = [];
      const approveLine = addLine();
      approveLine.textContent = '⏳ Đang duyệt HĐ/PL bên BMS…';
      try {
        const resp = await api.generate('registration.approve-bms', { bearerToken: bgdToken, opportunityId });
        regCodes = resp.result.data.regCodes || [];
        approveLine.textContent = `✓ BMS duyệt xong (${resp.result.data.registrations.length} registration)`;
      } catch (err) {
        approveLine.textContent = '✗ Duyệt BMS lỗi: ' + (err.message || err);
        approveLine.classList.add('is-error');
        return;
      }

      if (regCodes.length === 0) {
        const line = addLine();
        line.textContent = '⚠ Không tìm được Mã YC (regCode) nào để duyệt tiếp CM — dừng ở đây';
        line.classList.add('is-error');
        return;
      }

      const cmEmail = toLoginEmail(cmApproverInput.value.trim());
      const cmLoginLine = addLine();
      cmLoginLine.textContent = `⏳ Đăng nhập CM (${cmEmail})…`;
      let cmToken;
      try {
        const { token, cached } = await getCachedOrFreshCmToken(cmEmail);
        cmToken = token;
        cmLoginLine.textContent = cached ? `✓ Dùng lại token CM (${cmEmail}, còn hạn)` : `✓ Đăng nhập CM thành công (${cmEmail})`;
      } catch (err) {
        cmLoginLine.textContent = '✗ Đăng nhập CM lỗi: ' + (err.message || err);
        cmLoginLine.classList.add('is-error');
        return;
      }

      for (const regCode of regCodes) {
        const line = addLine();
        line.textContent = `⏳ Đang duyệt CM cho ${regCode}…`;
        try {
          const resp = await api.generate('registration.approve-cm', { cmToken, regCode });
          const d = resp.result.data;
          line.textContent = d.skipped
            ? `✓ ${regCode} đã hoàn thành từ trước (hợp đồng ${d.contractNo || '?'})`
            : `✓ ${regCode} duyệt CM xong — hợp đồng ${d.contractNo || '?'} / PL ${d.subContractNo || '?'}`;
        } catch (err) {
          line.textContent = `✗ Duyệt CM lỗi cho ${regCode}: ` + (err.message || err);
          line.classList.add('is-error');
        }
      }
    }

    function parseJsonArrayField(name, input, line) {
      const raw = input.value.trim();
      try {
        const parsed = JSON.parse(raw);
        if (!Array.isArray(parsed)) throw new Error('không phải array');
        return raw;
      } catch (err) {
        line.textContent = `✗ ${name} không phải JSON array hợp lệ: ` + (err.message || err);
        line.classList.add('is-error');
        return null;
      }
    }

    const phoneFormBody = UI.h('div', { class: 'gen-body' },
      UI.h('div', { class: 'field-grid-3' },
        field('Phân loại hợp đồng (TypeContract)', typeContractCategoryInput),
        field('Vùng miền / Tỉnh thành', locationSelect, '8 = HCM, 4 = HN — dùng chung cho cả 2 bước'),
        field('Hình thức thanh toán', paymentMethodInput),
        field('Hạn mức công nợ (ngày)', limitCreditDayInput),
        field('Cước trả một lần', feeContractInput),
        field('Cước tháng', monthlyFeeContractInput),
        field('Kỳ hạn thanh toán (tháng)', dateContractMonthInput),
        field('Giá trị HĐ/PL', contractValueInput)),
      UI.h('div', { class: 'field-grid' },
        field('Ghi chú', notesInput),
        field('Mô tả', descriptionInput)),
      UI.h('div', { class: 'field-grid-3' },
        UI.h('label', { class: 'd-block' },
          UI.h('div', { class: 'field-label' }, UI.h('span', { class: 'lbl' }, 'Phường/Xã')),
          wardSelect,
          UI.h('div', { style: 'margin-top: 6px;' }, loadWardsButton, ' ', wardStatus)),
        field('Số nhà, tên đường', locationNumberInput),
        field('Người liên hệ', contactNameInput),
        field('Điện thoại liên hệ', contactPhoneInput),
        field('Email liên hệ', contactEmailInput),
        field('Ngày hẹn triển khai', deployDateInput),
        field('Auto Call', autoCallSelect),
        field('IP kết nối', ipMainInput),
        field('Nền tảng', platformSelect, 'đổi lại sẽ search số điện thoại theo nền tảng đó')),
      UI.h('label', { class: 'd-block' },
        UI.h('div', { class: 'field-label' }, UI.h('span', { class: 'lbl' }, 'Số điện thoại'), phoneStatus),
        UI.h('div', { style: 'margin-top: 8px;' }, phoneListBody)),
      field('ListMember (JSON)', listMemberJsonInput, 'RefibbmembersId chưa rõ nguồn lấy — sửa tay theo Sale thật'),
      UI.h('label', { class: 'd-block' },
        UI.h('div', { class: 'field-label' }, UI.h('span', { class: 'lbl' }, 'Liên hệ thu cước')),
        UI.h('div', { style: 'margin-top: 6px;' }, billingListBody, addBillingButton)),
      UI.h('label', { class: 'd-block' },
        UI.h('div', { class: 'field-label' }, UI.h('span', { class: 'lbl' }, 'Liên hệ kỹ thuật')),
        UI.h('div', { style: 'margin-top: 6px;' }, techListBody, addTechButton)),
      UI.h('label', { class: 'd-flex align-items-center gap-2', style: 'padding: 10px 0; border-top: 1px solid var(--ink-700); margin-top: 6px;' },
        autoApproveCheckbox,
        UI.h('span', { class: 'lbl' }, 'Tự động duyệt BGĐ + CM ngay sau khi Tạo thành công'),
        UI.h('span', { class: 'hint' }, '— hành động phê duyệt thật, mặc định tắt'),
        UI.h('span', null, 'Tài khoản CM duyệt:'),
        cmApproverInput),
      status);

    const unsupportedBody = UI.h('div', { class: 'gen-body' },
      UI.h('p', { class: 'gen-signature', style: 'color: var(--ink-300);' },
        'Chưa hỗ trợ — chưa capture được payload cho loại dịch vụ này. Cần 1 lần "Copy as cURL" thật khi bấm Lưu trên UI (giống cách làm với Điện thoại cố định) để build tiếp.'));

    const submitButton = UI.h('button', {
      class: 'btn-generate',
      type: 'button',
      onclick: async () => {
        status.innerHTML = '';
        const saleEmail = currentSaleEmail();
        const opportunityCode = opportunityCodeInput.value.trim();
        const customerId = customerIdInput.value.trim();
        const registrationId = registrationIdInput.value.trim();
        const refCode = refCodeInput.value.trim();
        const taxId = taxIdInput.value.trim();
        const meta = currentServiceMeta();
        if (!saleEmail || !opportunityCode || !customerId || !registrationId) {
          const line = addLine();
          line.textContent = '✗ Điền đủ Tài khoản Sale + Mã cơ hội ở trên (đợi tự tra cứu xong để có Mã KH/RefCode)';
          line.classList.add('is-error');
          return;
        }
        if (!meta || !meta.supported) {
          const line = addLine();
          line.textContent = `✗ Loại dịch vụ "${meta ? meta.label : '?'}" chưa hỗ trợ`;
          line.classList.add('is-error');
          return;
        }
        if (!wardSelect.value) {
          const line = addLine();
          line.textContent = '✗ Chưa chọn Phường/Xã — đợi danh sách tự tải xong rồi chọn 1 trong dropdown';
          line.classList.add('is-error');
          return;
        }
        const chosenPhones = availablePhones.filter((row) => selectedPhoneIds.has(row.id));
        if (chosenPhones.length === 0) {
          const line = addLine();
          line.textContent = '✗ Chưa chọn số điện thoại nào — đợi danh sách tự tìm xong rồi tick ít nhất 1 số';
          line.classList.add('is-error');
          return;
        }
        if (billingContacts.length === 0) {
          const line = addLine();
          line.textContent = '✗ Chưa có Liên hệ thu cước — bấm "+ Thêm liên hệ thu cước" (bắt buộc, khớp form BMS thật)';
          line.classList.add('is-error');
          return;
        }
        if (techContacts.length === 0) {
          const line = addLine();
          line.textContent = '✗ Chưa có Liên hệ kỹ thuật — bấm "+ Thêm liên hệ kỹ thuật" (bắt buộc, khớp form BMS thật)';
          line.classList.add('is-error');
          return;
        }
        const validateLine = addLine();
        const listMemberJson = parseJsonArrayField('ListMember', listMemberJsonInput, validateLine);
        if (!listMemberJson) return;

        // ListBillingContact — xây từ các dòng form thật (không còn JSON thô).
        const listBillingContactJson = JSON.stringify(billingContacts.map(({ nameInput, phoneInput, emailInput, addressInput, wardSel }) => {
          const wardName = wardSel.selectedOptions[0] ? wardSel.selectedOptions[0].textContent : '';
          const address = addressInput.value.trim();
          return {
            Id: 0, ContactName: nameInput.value.trim(), ContactEmail: emailInput.value.trim(), ContactPhone: phoneInput.value.trim(),
            Address: address, Location: Number(locationSelect.value), District: Number(wardSel.value || 0), Ward: 0,
            Country: 'Việt Nam', FullAddress: `${address}, ${wardName}, ${locationNameHidden.value}, Việt Nam`,
          };
        }));
        // TechRegistrations — xây từ các dòng form thật.
        const techRegistrationsJson = JSON.stringify(techContacts.map(({ nameInput, phoneInput, emailInput }) => ({
          Id: 0, techContact: nameInput.value.trim(), techEmail: emailInput.value.trim(), techNumber: phoneInput.value.trim(),
        })));
        // ListDeployment — tự lấy từ đúng field "chi tiết triển khai IVOICE"
        // đã điền ở trên (Số nhà/Người liên hệ/Điện thoại/Email/Phường/Xã) —
        // không cần nhập lại lần 2, khớp quirk đã xác nhận sống 2026-09-07:
        // Location/District dùng CHUNG ward-id space với IVOICE.
        const deployAddress = locationNumberInput.value.trim();
        const listDeploymentJson = JSON.stringify([{
          ContactName: contactNameInput.value.trim(), ContactEmail: contactEmailInput.value.trim(), ContactPhone: contactPhoneInput.value.trim(),
          Address: deployAddress, Location: Number(locationSelect.value), District: Number(wardIdHidden.value || 0), Ward: 0, Country: '',
          FullAddress: `${deployAddress}, ${wardNameHidden.value}, ${locationNameHidden.value}`,
          Id: 0, refCodeOfIntegrated: 'null', IsSelected: true,
        }]);
        validateLine.textContent = '✓ ListMember hợp lệ · ' + billingContacts.length + ' liên hệ thu cước · ' + techContacts.length + ' liên hệ kỹ thuật';

        submitButton.disabled = true;
        try {
          // 2 cookie jar RIÊNG BIỆT (confirmed live 2026-09-07 — xem javadoc
          // GetBmsLegacySessionGenerator) — bms.insert-registration cần
          // vùng legacy BMS, ivoice.create-registration-detail cần vùng
          // IVOICE, dùng chung 1 jar sẽ bị 401 hoặc bị ghi đè lẫn nhau.
          //
          // THỨ TỰ ĐÃ ĐẢO LẠI (confirmed live 2026-09-07, xem walkthrough
          // đầy đủ trong memory/api-contracts.md): thực tế IVOICE
          // (InsertRegistrationIframe, "Thêm chi tiết") chạy TRƯỚC, rồi mới
          // đến BMS legacy (InsertRegistration, nút "Tạo" ở Bước 3) — bmsv2
          // còn CHẶN không cho qua Bước 3 nếu chưa có "Thông tin triển khai"
          // (tức chưa làm bước IVOICE), nên thứ tự cũ (BMS trước) là SAI so
          // với luồng thật.
          const ivoiceLoginLine = addLine();
          ivoiceLoginLine.textContent = `⏳ Đăng nhập IVOICE (${saleEmail})…`;
          let ivoiceCookieHeader;
          try {
            const c = await getCachedOrFreshIvoiceSession(saleEmail);
            ivoiceCookieHeader = c.cookieHeader;
            ivoiceLoginLine.textContent = c.cached
              ? `✓ Dùng lại session IVOICE (${saleEmail}, còn hạn)`
              : `✓ Đăng nhập IVOICE thành công (${saleEmail})`;
          } catch (err) {
            ivoiceLoginLine.textContent = '✗ Đăng nhập IVOICE lỗi: ' + (err.message || err);
            ivoiceLoginLine.classList.add('is-error');
            return;
          }

          // ----- Bước 1/2: ivoice.create-registration-detail (Thêm chi tiết) -----
          const step1Line = addLine();
          step1Line.textContent = `⏳ [1/3] Đang tạo phiếu triển khai (Thêm chi tiết) cho ${refCode}…`;
          let step1Ok = false;
          try {
            const resp = await api.generate('ivoice.create-registration-detail', {
              cookieHeader: ivoiceCookieHeader,
              refCode,
              opportunityCode,
              taxId,
              locationId: locationSelect.value,
              locationName: locationNameHidden.value,
              wardId: wardIdHidden.value,
              wardName: wardNameHidden.value,
              locationNumber: locationNumberInput.value.trim(),
              contactName: contactNameInput.value.trim(),
              contactPhone: contactPhoneInput.value.trim(),
              contactEmail: contactEmailInput.value.trim(),
              deployDate: deployDateInput.value.trim(),
              autoCall: autoCallSelect.value,
              ipMain: ipMainInput.value.trim(),
              platform: platformSelect.value,
              phonesJson: JSON.stringify(chosenPhones),
            });
            const response = resp.result.data.response;
            if (looksLikeFailureResponse(response)) {
              const msg = responseFailureMessage(response);
              // Case đặc biệt: số điện thoại đã được đăng ký cho chính cơ hội này
              // từ lần chạy trước (bước 1 đã thành công trước đó, bước 2 bị lỗi).
              // Detect bằng "thuộc mã cơ hội: <opportunityCode>" trong message.
              // Unicode normalize trước khi so sánh — server trả NFD, source JS dùng NFC,
              // nên msg.includes('thuộc mã cơ hội') bị false dù nhìn giống nhau.
              const msgNFC = msg.normalize('NFC');
              const alreadyRegisteredForThisOpp =
                msgNFC.includes('thuộc mã cơ hội') && msg.includes(opportunityCode);
              if (alreadyRegisteredForThisOpp) {
                step1Line.textContent = `⚠️ [1/3] Thêm chi tiết đã chạy trước đó (số điện thoại đã thuộc cơ hội này) — bỏ qua, tiếp tục Bước 2 "Tạo"…`;
                step1Ok = true; // treat as already done, continue to step 2
              } else {
                step1Line.textContent = `✗ [1/3] Thêm chi tiết THẤT BẠI (server trả lỗi nghiệp vụ, không phải lỗi mạng) — ${msg} — response: ${JSON.stringify(response)}`;
                step1Line.classList.add('is-error');
                UI.toast('error', 'Thêm chi tiết thất bại: ' + msg);
              }
            } else {
              step1Line.textContent = `✓ [1/3] Thêm chi tiết xong — response: ${JSON.stringify(response)}`;
              step1Ok = true;
            }
          } catch (err) {
            step1Line.textContent = '✗ [1/3] Thêm chi tiết lỗi: ' + (err.message || err);
            step1Line.classList.add('is-error');
            UI.toast('error', 'Thêm chi tiết lỗi: ' + (err.message || err));
          }
          if (!step1Ok) return;

          // ----- Bước 2/2: bms.insert-registration (Bước 3 "Tạo", legacy BMS) -----
          // Cần đăng nhập LẠI (vùng legacy BMS riêng — session bước 1 ở
          // trên KHÔNG dùng chung được, xem javadoc GetBmsLegacySessionGenerator).
          const loginLine = addLine();
          loginLine.textContent = `⏳ Đăng nhập BMS legacy (${saleEmail})…`;
          let bearerToken, bmsCookieHeader;
          try {
            const t = await getCachedOrFreshToken(saleEmail);
            bearerToken = t.token;
            const c = await getCachedOrFreshBmsLegacySession(saleEmail, refCode);
            bmsCookieHeader = c.cookieHeader;
            loginLine.textContent = c.cached
              ? `✓ Dùng lại session BMS legacy (${saleEmail}, còn hạn)`
              : `✓ Đăng nhập BMS legacy thành công (${saleEmail})`;
          } catch (err) {
            loginLine.textContent = '✗ Đăng nhập BMS legacy lỗi: ' + (err.message || err);
            loginLine.classList.add('is-error');
            return;
          }
          // ----- Bước 2/3: bms.check-service-integrate (pre-check mới phát
          // hiện 2026-09-07, gọi ngay trước "Tạo" trong luồng thật — chưa
          // rõ có bắt buộc hay không, nhưng mọi lần gọi thẳng
          // InsertRegistration bỏ qua bước này đều bị "không có quyền",
          // nên thử gọi nó trước xem có giải quyết được không). Không chặn
          // luồng nếu bước này lỗi — chỉ log lại, vì chưa chắc chắn 100%
          // là bắt buộc.
          const checkLine = addLine();
          checkLine.textContent = `⏳ [2/3] Đang gọi CheckServiceIntergrate cho ${refCode}…`;
          try {
            const refKeysJson = JSON.stringify(JSON.parse(listDeploymentJson).map((d) => d.refCodeOfIntegrated));
            const checkResp = await api.generate('ivoice.check-service-integrate', {
              cookieHeader: bmsCookieHeader,
              refCode,
              serviceGroup: meta.serviceGroup,
              serviceType: meta.serviceType,
              totalAmt: contractValueInput.value.trim(),
              paymentTypeCode: paymentMethodInput.value.trim(),
              refKeysJson,
            });
            const checkResponse = checkResp.result.data.response;
            checkLine.textContent = `${looksLikeFailureResponse(checkResponse) ? '✗' : '✓'} [2/3] CheckServiceIntergrate xong — response: ${JSON.stringify(checkResponse)}`;
            if (looksLikeFailureResponse(checkResponse)) checkLine.classList.add('is-error');
          } catch (err) {
            checkLine.textContent = '✗ [2/3] CheckServiceIntergrate lỗi (bỏ qua, vẫn thử Tạo tiếp): ' + (err.message || err);
            checkLine.classList.add('is-error');
          }

          const step2Line = addLine();
          step2Line.textContent = `⏳ [3/3] Đang gọi InsertRegistration (Bước 3 "Tạo") cho ${refCode}…`;
          try {
            const resp = await api.generate('ivoice.insert-registration', {
              cookieHeader: bmsCookieHeader, bearerToken,
              opportunityCode, registrationId, customerId,
              serviceGroup: meta.serviceGroup,
              serviceType: meta.serviceType,
              typeContract: meta.typeDocContract,
              typeContractCategory: typeContractCategoryInput.value.trim(),
              location: locationSelect.value,
              paymentMethod: paymentMethodInput.value.trim(),
              limitCreditDay: limitCreditDayInput.value.trim(),
              feeContract: feeContractInput.value.trim(),
              monthlyFeeContract: monthlyFeeContractInput.value.trim(),
              dateContractMonth: dateContractMonthInput.value.trim(),
              contractValue: contractValueInput.value.trim(),
              notes: notesInput.value.trim(),
              description: descriptionInput.value.trim(),
              listMemberJson, listBillingContactJson, techRegistrationsJson, listDeploymentJson,
            });
            const response = resp.result.data.response;
            if (looksLikeFailureResponse(response)) {
              step2Line.textContent = `✗ [3/3] InsertRegistration THẤT BẠI (server trả lỗi nghiệp vụ) — ${responseFailureMessage(response)} — response: ${JSON.stringify(response)}`;
              step2Line.classList.add('is-error');
              UI.toast('error', 'InsertRegistration thất bại: ' + responseFailureMessage(response));
            } else {
              step2Line.textContent = `✓ [3/3] InsertRegistration xong — response: ${JSON.stringify(response)}`;
              UI.toast('success', `Tạo HĐ/PL ✓ — ${refCode}`);
              if (autoApproveCheckbox.checked) {
                await runAutoApprove(opportunityCode);
              }
            }
          } catch (err) {
            step2Line.textContent = '✗ [3/3] InsertRegistration lỗi: ' + (err.message || err);
            step2Line.classList.add('is-error');
            UI.toast('error', 'InsertRegistration lỗi: ' + (err.message || err));
          }
        } finally {
          submitButton.disabled = false;
        }
      },
    }, 'Thêm chi tiết');

    const body = UI.h('div', null, phoneFormBody);
    const footer = UI.h('div', { class: 'gen-footer' }, submitButton);

    function renderBodyForType() {
      const meta = currentServiceMeta();
      body.innerHTML = '';
      footer.innerHTML = '';
      if (meta && meta.supported) {
        body.appendChild(phoneFormBody);
        footer.appendChild(submitButton);
      } else {
        body.appendChild(unsupportedBody);
      }
    }
    renderBodyForType();

    // Khối định danh (Tài khoản Sale/Mã cơ hội/Loại dịch vụ/Mã KH/RefCode/
    // Mã số thuế) — luôn hiện, kể cả khi Loại dịch vụ đang chọn chưa hỗ
    // trợ (body bên dưới đổi qua unsupportedBody nhưng vẫn phải chọn được
    // lại Loại dịch vụ ở đây), nên tách riêng khỏi `body` (cái đổi qua lại
    // theo currentServiceMeta()).
    const identityBody = UI.h('div', { class: 'gen-body' },
      UI.h('div', { class: 'field-grid' },
        field('Tài khoản Sale', saleInput, 'vd thitlh — tự thêm @fpt.com nếu thiếu'),
        UI.h('label', { class: 'd-block' },
          UI.h('div', { class: 'field-label' }, UI.h('span', { class: 'lbl' }, 'Mã cơ hội')),
          opportunityCodeInput,
          UI.h('div', { style: 'margin-top: 4px;' }, oppLookupStatus))),
      UI.h('div', { class: 'field-grid-3' },
        field('Loại dịch vụ (Đấu số thoại)', typeSelect),
        field('Mã khách hàng', customerIdInput, 'tự động lấy từ Mã cơ hội'),
        field('RefCode', refCodeInput, 'tự động lấy từ Mã cơ hội'),
        field('Mã số thuế KH', taxIdInput, 'tự động lấy từ Mã cơ hội')));

    return UI.h('article', { class: 'gen-card' },
      UI.h('header', { class: 'gen-header' },
        UI.h('div', { class: 'd-flex justify-content-between align-items-start gap-2' },
          UI.h('div', null,
            UI.h('div', { class: 'gen-eyebrow' }, UI.h('span', { class: 'dot' }), 'Opportunity'),
            UI.h('h3', { class: 'gen-title' }, 'Ivoice — Tạo yêu cầu HĐ/PL')),
          UI.h('span', { class: 'gen-class' }, 'ivoice.lookup-opportunity → ivoice.create-registration-detail → ivoice.insert-registration')),
        UI.h('p', { class: 'gen-signature' },
          '✅ Đã xác nhận chạy thật thành công 2026-09-07 (CH00085411) — 1 nút chạy tuần tự Thêm chi tiết (IVOICE) rồi đến Bước 3 "Tạo" (BMS legacy, đẩy sang "Chờ BGĐ duyệt"), log hiện response thô của cả 2. Cần RegistrationId đã tồn tại sẵn (bước "Xác nhận tạo yêu cầu hợp đồng" vẫn thao tác tay).'),
        UI.h('div', { style: 'margin-top: 6px;' }, derivedSummary)),
      identityBody,
      body,
      footer);
  }
  /**
   * Bolts real cascading/async dropdowns onto the opportunity.api card for
   * fields that are just raw-code text inputs otherwise (services, AM, TPP,
   * BGĐ) — all empty/disabled until a bearer token is present, matching how
   * the real BMS UI behaves before login. See ui.js mountServicePicker /
   * mountAsyncPicker for the DOM mechanics; this just wires them to the API.
   */
  function mountOpportunityPickers(card) {
    const getToken = () => {
      const w = card.querySelector('[data-field="bearerToken"] input');
      return w ? w.value.trim() : '';
    };

    mountTokenFetcher(card, getToken);
    mountCreateCustomerButton(card);

    const servicePicker = UI.mountServicePicker(card, {
      getToken,
      loadCatalog: (token) => api.proxy('master-data/service-types/groups', token).then(normalizeServiceCatalog),
    });

    // AM lookup endpoint still not confirmed (nick-names was wrong — see
    // memory/api-contracts.md) — leave saleName as plain text for now.

    // TPP/BGĐ confirmed 2026-08-25 by watching the real "Chọn người duyệt" step:
    // both are keyed by the AM's *email* (not cdCode as first guessed).
    // department-managers = TPP đánh giá, central-managers = BGĐ phê duyệt.
    const amEmail = () => {
      const raw = card.querySelector('[data-field="saleName"] input');
      const v = raw ? raw.value.trim() : '';
      if (!v) return null;
      return v.includes('@') ? v : v + '@fpt.com';
    };

    const tppPicker = UI.mountAsyncPicker(card, 'tppCanApproval', {
      getToken, multi: true,
      loadOptions: (token) => {
        const email = amEmail();
        if (!email) return Promise.reject(new Error('Điền AM (sale phụ trách) trước đã'));
        return api.proxy(`bam/employees/${encodeURIComponent(email)}/department-managers`, token).then(normalizeFlatList);
      },
    });
    const bgdPicker = UI.mountAsyncPicker(card, 'usersCanApproval', {
      getToken, multi: true,
      loadOptions: (token) => {
        const email = amEmail();
        if (!email) return Promise.reject(new Error('Điền AM (sale phụ trách) trước đã'));
        return api.proxy(`bam/employees/${encodeURIComponent(email)}/central-managers`, token).then(normalizeFlatList);
      },
    });

    // Auto-load instead of requiring a manual button click: leaving the
    // Bearer Token field loads the service catalog (and TPP/BGĐ if an AM is
    // already filled in); leaving the AM field (re-)loads TPP/BGĐ for that AM.
    const tokenInput = card.querySelector('[data-field="bearerToken"] input');
    const amInput = card.querySelector('[data-field="saleName"] input');
    const autoLoadApprovers = () => {
      if (!getToken() || !amEmail()) return;
      if (tppPicker) tppPicker.reload();
      if (bgdPicker) bgdPicker.reload();
    };
    // Sale phụ trách (AM) mặc định lấy theo chính người đang login bằng
    // Bearer Token — TRỪ KHI người đó không phải role Sale (NV), lúc đó họ
    // đang tạo hộ cho một Sale khác nên ô này phải mở lại cho chọn tay
    // (2026-08-26, user request). Role Sale rank-and-file nhận diện qua JWT:
    // claim "role" chứa "SALESMAN" (vd "FTI-SAL SALESMAN") — khác với
    // "... SALE MANAGER"/"... CENTER MANAGER" của TPP/BGĐ.
    // Real AM directory endpoint confirmed 2026-08-26 by walking the actual
    // "Tạo cơ hội" wizard step 3 as a non-Sale account and reading its network
    // calls: GET bam/sales — returns ALL ~1100 Sale accounts company-wide
    // (userName/fullName/email/...), but comes back EMPTY when called with a
    // plain Sale's own token (server-side role gate — matches BR: a Sale IS
    // the AM, doesn't need to browse the directory). So: fetch it only for
    // the non-Sale branch below, and offer it as a native <datalist> on the
    // AM input — keeps it a plain text field (no UI restructuring) while
    // still letting the user pick from the real list instead of guessing a
    // username.
    const amDatalistId = 'am-sales-datalist';
    function ensureAmDatalist() {
      let dl = document.getElementById(amDatalistId);
      if (!dl) {
        dl = document.createElement('datalist');
        dl.id = amDatalistId;
        document.body.appendChild(dl);
      }
      return dl;
    }
    function populateAmDatalist(sales) {
      const dl = ensureAmDatalist();
      dl.innerHTML = '';
      sales.forEach((s) => {
        const opt = document.createElement('option');
        opt.value = s.id;
        opt.label = `${s.label} (${s.id})`;
        dl.appendChild(opt);
      });
    }
    // Native <datalist> gives zero visual feedback while loading (unlike the
    // custom mountAsyncPicker dropdowns used for TPP/BGĐ) — a user just sees a
    // plain text field and can't tell whether the Sale list ever arrived. Add
    // an explicit status line under the field so "loaded 14 sales" vs
    // "load failed" vs "locked to your own account" is visible instead of
    // silent (2026-08-26, reported as "chưa load ds sale").
    let amHint = card.querySelector('[data-field="saleName"] .am-hint');
    if (!amHint && amInput) {
      amHint = document.createElement('div');
      amHint.className = 'am-hint';
      amInput.insertAdjacentElement('afterend', amHint);
    }
    function setAmHint(text) {
      if (amHint) amHint.textContent = text;
    }

    function applySaleDefaultFromToken() {
      const token = getToken();
      if (!amInput) return;
      const payload = token ? decodeJwt(token) : null;
      const role = (jwtClaim(payload, 'role') || '').toString().toUpperCase();
      const username = jwtClaim(payload, 'UserName');
      const isSale = role.includes('SALESMAN');
      if (isSale && username) {
        amInput.value = username;
        amInput.readOnly = true;
        amInput.classList.add('is-locked');
        amInput.removeAttribute('list');
        amInput.dispatchEvent(new Event('input', { bubbles: true }));
        setAmHint('Tự động điền theo tài khoản đăng nhập (khoá, vì tài khoản này là Sale).');
      } else {
        amInput.readOnly = false;
        amInput.classList.remove('is-locked');
        if (token) {
          amInput.setAttribute('list', amDatalistId);
          setAmHint('Đang tải danh sách Sale...');
          api.proxy('bam/sales', token).then(normalizeFlatList).then((sales) => {
            populateAmDatalist(sales);
            setAmHint(sales.length
              ? `Đã tải ${sales.length} Sale thuộc phạm vi tài khoản này — gõ tên/username để chọn.`
              : 'Tài khoản này không quản lý Sale nào — nhập username thủ công.');
          }).catch(() => setAmHint('Không tải được danh sách Sale — nhập username thủ công.'));
        }
      }
    }

    if (tokenInput) {
      tokenInput.addEventListener('blur', () => {
        if (!getToken()) return;
        if (servicePicker) servicePicker.reload();
        applySaleDefaultFromToken();
        autoLoadApprovers();
      });
    }
    if (amInput) {
      amInput.addEventListener('blur', autoLoadApprovers);
    }

    // baseUrl rarely needs overriding (dev default is fine) and the raw
    // "services" text input is now just the picker's write-target — both stay
    // functional (still part of `values`, still filled by the picker) but
    // don't need to clutter the form.
    // NOTE: renderField() gives every field wrapper Bootstrap's `.d-block`,
    // which is `!important` — a plain `style.display='none'` (no !important)
    // loses that cascade fight and silently does nothing visible. Use `.d-none`
    // (also Bootstrap, also !important) instead of inline style so it actually
    // wins.
    ['baseUrl', 'services'].forEach((name) => {
      const wrap = card.querySelector(`[data-field="${name}"]`);
      if (wrap) wrap.classList.add('d-none');
    });

    mountAutoFlowCheckboxes(card);
  }

  /**
   * "Tự động Đánh giá / Phê duyệt" checkboxes — the follow-up step confirmed
   * 2026-08-25 (see memory/api-contracts.md). Đánh giá (TPP) is required by
   * BR03 before an opportunity can move forward at all, so it defaults ON;
   * Phê duyệt (BGĐ) only applies when expectedRevenue >= 300tr, but is also
   * left ON by default since skipping it below the threshold is a no-op
   * (handled in runPostCreateFlow), not an error — same bearer token is
   * reused for both calls, so no extra login step is needed.
   */
  function mountAutoFlowCheckboxes(card) {
    const footer = card.querySelector('.gen-footer');
    if (!footer) return;

    const evalCb = UI.h('input', { type: 'checkbox', dataset: { autoflow: 'evaluate' } });
    evalCb.checked = true;
    const apprCb = UI.h('input', { type: 'checkbox', dataset: { autoflow: 'approve' } });
    apprCb.checked = true;

    // Stage-advance is opt-in (unchecked by default) — unlike evaluate/approve
    // this isn't part of the normal creation flow, it's an extra the user
    // explicitly asks for. The text input stays enabled either way so a value
    // typed before ticking the box isn't lost, but is only sent when checked.
    // Calls opportunity.stage-advance — a REAL "Chuyển giai đoạn" through every
    // remaining stage (progress -> 100%), not just an in-place note edit (that
    // was the original version; the user pointed out it didn't actually move
    // the stage forward, so this now does the real transition, still carrying
    // the same note on each step).
    const noteCb = UI.h('input', { type: 'checkbox', dataset: { autoflow: 'stage-note' } });
    const noteInput = UI.h('input', {
      type: 'text', class: 'app-input', dataset: { autoflow: 'stage-note-text' },
      value: 'Chuyển giai đoạn tự động qua tool', style: 'margin-top:6px;'
    });

    // Quotation — calls opportunity.quotation, same creator (Sale/AM) token as
    // the opportunity itself, no extra login needed (unlike evaluate/approve).
    const quoteCb = UI.h('input', { type: 'checkbox', dataset: { autoflow: 'quotation' } });
    const quotePriceInput = UI.h('input', {
      type: 'text', class: 'app-input', dataset: { autoflow: 'quotation-price' },
      value: '1', style: 'margin-top:6px;max-width:160px;', placeholder: 'Đơn giá mỗi dịch vụ (VND)'
    });

    const row = UI.h('div', { class: 'autoflow-options' },
      UI.h('label', { class: 'autoflow-check' }, evalCb, ' Tự động Đánh giá (TPP) sau khi tạo'),
      UI.h('label', { class: 'autoflow-check' }, apprCb, ' Tự động Phê duyệt (BGĐ) nếu doanh thu ≥ 300 triệu'),
      UI.h('label', { class: 'autoflow-check' }, noteCb, ' Tự động chuyển hết Vòng đời cơ hội (tiến độ 100%)'),
      noteInput,
      UI.h('label', { class: 'autoflow-check' }, quoteCb, ' Tự động tạo Báo giá (đính kèm dummy.pdf)'),
      quotePriceInput
    );
    footer.insertAdjacentElement('beforebegin', row);
  }

  /**
   * "Loại khách hàng" dropdown + "Tạo KH mới" button next to the Customer ID
   * field — for when the user doesn't have a real customerId handy. Per
   * user request 2026-08-27 ("hiện field bắt buộc cho người dùng chọn muốn
   * tạo loại nào xong mới click button"): the required "loại khách hàng"
   * choice is a real select the user picks BEFORE clicking, not silently
   * hardcoded — matches customer.create-random's `customerKind` param
   * (see CreateRandomCustomerGenerator.java for the two supported shapes:
   * Tổ chức nước ngoài / Cá nhân nước ngoài — both skip the Tỉnh/Phường
   * cascading pickers, simplest to auto-fill correctly).
   *
   * Logs in as the CM account (truongnv64, same convention as
   * mountHdPlApprovalCard), calls customer.create-random with the chosen
   * kind, then fills the returned customerCode straight into the field —
   * overwrites whatever was already typed, user opted in by clicking.
   */
  function mountCreateCustomerButton(card) {
    const wrap = card.querySelector('[data-field="customerId"]');
    const input = wrap ? wrap.querySelector('input') : null;
    if (!wrap || !input) return;

    const kindSelect = UI.h('select', { class: 'app-select', style: 'margin-top: 6px; width: auto; display: inline-block; margin-right: 6px;' },
      UI.h('option', { value: 'to-chuc-nuoc-ngoai' }, 'Tổ chức nước ngoài'),
      UI.h('option', { value: 'ca-nhan-nuoc-ngoai' }, 'Cá nhân nước ngoài'));

    const btn = UI.h('button', {
      type: 'button',
      class: 'btn-generate',
      style: 'margin-top: 6px; padding: 6px 14px; font-size: 12px; width: auto;',
      onclick: async () => {
        btn.disabled = true;
        const original = btn.textContent;
        btn.textContent = 'Đang tạo KH…';
        try {
          const { token } = await getCachedOrFreshCmToken('truongnv64@fpt.com');
          const resp = await api.generate('customer.create-random', { cmToken: token, customerKind: kindSelect.value });
          input.value = resp.result.data.customerCode;
          input.dispatchEvent(new Event('input', { bubbles: true }));
          UI.toast('success', `Đã tạo KH mới: ${resp.result.data.customerCode}`);
        } catch (err) {
          UI.toast('error', 'Tạo KH mới lỗi: ' + (err.message || err));
        } finally {
          btn.disabled = false;
          btn.textContent = original;
        }
      },
    }, 'Tạo KH mới');
    wrap.appendChild(kindSelect);
    wrap.appendChild(btn);
  }

  function mountTokenFetcher(card) {
    const tokenWrap = card.querySelector('[data-field="bearerToken"]');
    if (!tokenWrap) return;
    const tokenInput = tokenWrap.querySelector('input');
    if (!tokenInput) return;

    const userInput = UI.h('input', { class: 'app-input', type: 'text', placeholder: 'vd: thitlh@fpt.com' });
    const passInput = UI.h('input', { class: 'app-input', type: 'password', value: 'ISC22QC', style: 'max-width:140px;' });
    const fetchBtn = UI.h('button', { class: 'icon-btn', type: 'button' }, 'Lấy Token');
    const statusEl = UI.h('span', { class: 'hint' });

    fetchBtn.addEventListener('click', async () => {
      const username = userInput.value.trim();
      const password = passInput.value.trim();
      if (!username) { statusEl.textContent = 'Điền username/email trước đã.'; return; }
      statusEl.textContent = 'Đang đăng nhập (API, ~1-2s)…';
      fetchBtn.disabled = true;
      try {
        const resp = await api.generate('auth.get-token-api', { username, password });
        tokenInput.value = resp.result.data.accessToken;
        tokenInput.dispatchEvent(new Event('input', { bubbles: true }));
        tokenInput.dispatchEvent(new Event('blur', { bubbles: true }));
        statusEl.textContent = `Đã lấy token cho ${username} ✓`;
      } catch (err) {
        statusEl.textContent = 'Lỗi: ' + (err.message || err);
      } finally {
        fetchBtn.disabled = false;
      }
    });

    tokenWrap.insertAdjacentElement('afterend', UI.h('div', { class: 'service-picker' },
      UI.h('div', { class: 'field-grid' }, userInput, UI.h('div', null, passInput, fetchBtn)),
      statusEl
    ));
  }

  /**
   * `/master-data/service-types/groups` returns a FLAT list of service TYPE
   * rows, each carrying its own group id/name inline — confirmed 2026-08-25
   * from a real response (see memory/api-contracts.md):
   *   { data: [ { id, serviceTypeName, serviceGroupId, serviceGroupName,
   *               servicePackCode, isActive }, ... ] }
   * Group them client-side into { groups: [{id, label, types:[{id,label}]}] }.
   * Rows with isActive===0 are dropped (seen in real data, e.g. a disabled
   * "Voice Brandname" type) — inactive types shouldn't be offered for new
   * opportunities.
   */
  function normalizeServiceCatalog(raw) {
    const rows = (raw && raw.data) || [];
    const byGroup = new Map();
    rows.forEach((row) => {
      if (row.isActive === 0) return;
      const gid = row.serviceGroupId;
      if (!byGroup.has(gid)) {
        byGroup.set(gid, { id: gid, label: row.serviceGroupName ?? String(gid), types: [] });
      }
      byGroup.get(gid).types.push({ id: row.id, label: row.serviceTypeName ?? String(row.id) });
    });
    return { groups: [...byGroup.values()] };
  }

  /**
   * For nick-names/deputy. Envelope confirmed 2026-08-25:
   * `{ data: [...], success, errors, meta }` — same shape both endpoints use.
   * Item field names NOT yet confirmed (both returned `data: []` empty in
   * testing — `deputy` likely needs a `cdCode` param we're not passing yet,
   * matching how the real UI's own TPP dropdown was also empty for some AMs;
   * see memory/api-contracts.md). Keeping a flexible fallback chain until a
   * populated response is seen.
   */
  function normalizeFlatList(raw) {
    const list = Array.isArray(raw) ? raw : (raw && (raw.data || raw.result || raw.items)) || [];
    return list.map((o) => ({
      id: o.username ?? o.userName ?? o.code ?? o.id ?? o.value,
      label: o.fullName ?? o.name ?? o.label ?? o.text ?? String(o.username ?? o.id ?? ''),
    }));
  }

  function buildEmpty(msg) {
    const el = document.createElement('div');
    el.className = 'empty-state';
    el.textContent = msg;
    return el;
  }

  /* ---------- bootstrap ---------- */
  async function checkHealth() {
    try {
      const h = await api.health();
      UI.setHealth('up', `up · ${h.generators} generators`);
    } catch (err) {
      UI.setHealth('down', 'API down');
      UI.toast('error', 'Backend unreachable. Start it on ' + API_BASE);
    }
  }

  async function loadAll() {
    try {
      const [generators, services] = await Promise.all([
        api.listGenerators(),
        api.listServices(),
      ]);
      state.masterMeta = (generators || []).find((g) => g.type === MASTER_TYPE) || null;
      state.apiMeta = (generators || []).find((g) => g.type === API_GEN_TYPE) || null;
      state.servicesConfig = services || { group: {} };
      renderMaster();
    } catch (err) {
      document.getElementById('catalog').innerHTML =
        `<div class="empty-state">Could not load: ${escapeHtml(err.message || String(err))}.</div>`;
    }
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({
      '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
    })[c]);
  }

  /* ---------- activity log ---------- */
  const LOG_PAGE_SIZE = 10;
  const logState = { entries: [], page: 0 };

  async function loadLog() {
    const host = document.getElementById('log-table-host');
    if (!host) return;
    try {
      // Backend already returns newest-first (AuditService.recent reads the
      // file backwards) — fetch a bigger chunk once, paginate 10/page client-side.
      const entries = await api.listLog(200);
      logState.entries = entries || [];
      renderLogTable(host); // keeps current page across the 30s auto-refresh; renderLogTable clamps if it's now out of range
    } catch {
      host.innerHTML = '<p class="no-params">Could not load log.</p>';
    }
  }

  function renderLogTable(host) {
    host.innerHTML = '';
    const entries = logState.entries;
    if (entries.length === 0) {
      host.innerHTML = '<p class="no-params" style="color:var(--ink-300);">Chưa có hoạt động nào.</p>';
      return;
    }
    const totalPages = Math.max(1, Math.ceil(entries.length / LOG_PAGE_SIZE));
    logState.page = Math.min(logState.page, totalPages - 1);
    const start = logState.page * LOG_PAGE_SIZE;
    const pageEntries = entries.slice(start, start + LOG_PAGE_SIZE);

    const table = document.createElement('table');
    table.style.cssText = 'width:100%;border-collapse:collapse;font-size:0.8125rem;font-family:var(--font-mono,monospace);';
    const thead = document.createElement('thead');
    thead.innerHTML = `<tr style="border-bottom:2px solid var(--border-subtle,#e5e7eb);text-align:left;">
      <th style="padding:6px 10px;white-space:nowrap;">Time</th>
      <th style="padding:6px 10px;">IP</th>
      <th style="padding:6px 10px;">Type</th>
      <th style="padding:6px 10px;">ServiceGroup</th>
      <th style="padding:6px 10px;">Customer</th>
      <th style="padding:6px 10px;">Services</th>
      <th style="padding:6px 10px;">StopAt</th>
      <th style="padding:6px 10px;">Result</th>
      <th style="padding:6px 10px;text-align:right;">Duration</th>
    </tr>`;
    table.appendChild(thead);
    const tbody = document.createElement('tbody');
    pageEntries.forEach((e, i) => {
      const p = e.params || {};
      const svcs = Array.isArray(p.selectedServices) && p.selectedServices.length
        ? p.selectedServices.join(', ')
        : '(all)';
      const ts = e.timestamp
        ? new Date(e.timestamp).toLocaleString('vi-VN', { hour12: false })
        : '';
      const tr = document.createElement('tr');
      tr.style.cssText = `border-bottom:1px solid var(--border-subtle,#f3f4f6);background:${i % 2 === 0 ? 'transparent' : 'rgba(0,0,0,.02)'};`;
      tr.innerHTML = `
        <td style="padding:5px 10px;white-space:nowrap;">${escapeHtml(ts)}</td>
        <td style="padding:5px 10px;">${escapeHtml(e.ip || '')}</td>
        <td style="padding:5px 10px;">${escapeHtml(e.type || '')}</td>
        <td style="padding:5px 10px;">${escapeHtml(p.serviceGroup || '')}</td>
        <td style="padding:5px 10px;max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${escapeHtml(p.customerName || '')}">${escapeHtml(p.customerName || '')}</td>
        <td style="padding:5px 10px;max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${escapeHtml(svcs)}">${escapeHtml(svcs)}</td>
        <td style="padding:5px 10px;">${escapeHtml(p.stopAfter || '')}</td>
        <td style="padding:5px 10px;font-weight:600;color:${e.success ? '#16a34a' : '#dc2626'};max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${escapeHtml(e.summary || '')}">
          ${e.success ? '✓' : '✗'} ${escapeHtml((e.summary || '').length > 60 ? e.summary.substring(0, 60) + '…' : (e.summary || ''))}
        </td>
        <td style="padding:5px 10px;text-align:right;">${e.durationMs ? (e.durationMs / 1000).toFixed(1) + 's' : ''}</td>
      `;
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    host.appendChild(table);

    if (totalPages > 1) {
      const pager = document.createElement('div');
      pager.style.cssText = 'display:flex;align-items:center;justify-content:flex-end;gap:10px;margin-top:8px;font-size:0.8125rem;';
      const prevBtn = document.createElement('button');
      prevBtn.textContent = '‹ Mới hơn';
      prevBtn.className = 'icon-btn';
      prevBtn.disabled = logState.page === 0;
      prevBtn.addEventListener('click', () => { logState.page--; renderLogTable(host); });

      const nextBtn = document.createElement('button');
      nextBtn.textContent = 'Cũ hơn ›';
      nextBtn.className = 'icon-btn';
      nextBtn.disabled = logState.page >= totalPages - 1;
      nextBtn.addEventListener('click', () => { logState.page++; renderLogTable(host); });

      const label = document.createElement('span');
      label.style.color = 'var(--ink-300)';
      label.textContent = `Trang ${logState.page + 1}/${totalPages} · ${entries.length} bản ghi`;

      pager.appendChild(label);
      pager.appendChild(prevBtn);
      pager.appendChild(nextBtn);
      host.appendChild(pager);
    }
  }

  // Hide the search + category filter — only one card now.
  document.addEventListener('DOMContentLoaded', () => {
    const search = document.getElementById('search');
    if (search) search.style.display = 'none';
    const cats = document.getElementById('categories');
    if (cats) cats.style.display = 'none';
    checkHealth();
    loadAll();
    loadLog();

    const refreshBtn = document.getElementById('btn-refresh-log');
    if (refreshBtn) refreshBtn.addEventListener('click', loadLog);

    // auto-refresh log every 30s
    setInterval(loadLog, 30_000);
  });
})();
