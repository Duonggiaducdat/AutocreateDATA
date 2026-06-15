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

  /* ---------- state ---------- */
  const state = {
    masterMeta: null,
    servicesConfig: null,
  };

  const handlers = {
    /** Card "Generate" click. */
    async onGenerate(meta, values, card) {
      card._setBusy(true);
      try {
        const resp = await api.generate(meta.type, values);
        card._setResult(resp.result, null);
        UI.toast('success', `${meta.displayName} ✓ (stopped after ${values.stopAfter || 'export'})`);
      } catch (err) {
        card._setResult(null, err);
        UI.toast('error', `${meta.displayName} failed: ${err.message || err}`);
      } finally {
        card._setBusy(false);
      }
    },
  };

  /* ---------- rendering ---------- */
  function renderMaster() {
       const host = document.getElementById('catalog');
       host.innerHTML = '';
       if (!state.masterMeta) {
         host.appendChild(buildEmpty(`Master generator "${MASTER_TYPE}" not found in catalog.`));
         return;
       }
       const card = UI.renderMasterCard(state.masterMeta, state.servicesConfig, handlers);
       const wrapper = document.createElement('div');
       wrapper.className = 'master-grid';
       wrapper.appendChild(card);
       host.appendChild(wrapper);
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

  // Hide the search + category filter — only one card now.
  document.addEventListener('DOMContentLoaded', () => {
    const search = document.getElementById('search');
    if (search) search.style.display = 'none';
    const cats = document.getElementById('categories');
    if (cats) cats.style.display = 'none';
    checkHealth();
    loadAll();
  });
})();
