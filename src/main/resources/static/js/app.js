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
  const TOKEN_CACHE_TTL_MS = 50 * 60 * 1000;
  const tokenCache = new Map(); // email -> { token, fetchedAt }

  async function getCachedOrFreshToken(email) {
    const cached = tokenCache.get(email);
    if (cached && (Date.now() - cached.fetchedAt) < TOKEN_CACHE_TTL_MS) {
      return { token: cached.token, cached: true };
    }
    const resp = await api.generate('auth.get-token', { username: email, password: DEFAULT_OTP_PASSWORD });
    const token = resp.result.data.token;
    tokenCache.set(email, { token, fetchedAt: Date.now() });
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
   * "Lấy Token" button — calls the auth.get-token generator (real Selenium
   * login, see memory/api-contracts.md "Login flow") and writes the result
   * straight into the Bearer Token field, dispatching `input` so the existing
   * auto-load-on-blur wiring picks it up the same as if it'd been pasted by
   * hand. Username is free-text since different steps need different roles
   * (AM/TPP/BGĐ each have their own account); password defaults to the known
   * STG bypass code but stays editable in case an account needs a different one.
   */
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
      statusEl.textContent = 'Đang đăng nhập (Selenium, có thể mất ~10-15s)…';
      fetchBtn.disabled = true;
      try {
        const resp = await api.generate('auth.get-token', { username, password });
        tokenInput.value = resp.result.data.token;
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
        <td style="padding:5px 10px;font-weight:600;color:${e.success ? '#16a34a' : '#dc2626'};">
          ${e.success ? '✓' : '✗'} ${escapeHtml(e.summary || '')}
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
