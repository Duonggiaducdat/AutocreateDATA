/* =========================================================================
 *  api.js — thin client for the Spring Boot backend.
 *  Single responsibility: HTTP. No DOM access, no UI logic.
 * ========================================================================= */
(function (global) {
  'use strict';

  // Dynamic base URL — use the same origin the page is served from.
  // No more hard-coded IP; works for localhost, LAN IP, or any host.
  const DEFAULT_BASE = (typeof window !== 'undefined' && window.location)
    ? window.location.origin
    : 'http://localhost:8080';
  class HttpError extends Error {
    constructor(message, status, payload) {
      super(message);
      this.name = 'HttpError';
      this.status = status;
      this.payload = payload;
    }
  }

  class ApiClient {
    /**
     * @param {{ baseUrl?: string, timeoutMs?: number }} [opts]
     */
    constructor(opts = {}) {
      this.baseUrl  = (opts.baseUrl  || DEFAULT_BASE).replace(/\/$/, '');
      this.timeoutMs = opts.timeoutMs || 600_000;
    }

    /** Liveness ping. Resolves with `{status, generators}` or rejects on network. */
    health() {
      return this._fetchJson('/api/health', { method: 'GET' });
    }

    /** Returns the catalog: `[{ type, displayName, category, params, ... }]`. */
    listGenerators() {
      return this._fetchJson('/api/generators', { method: 'GET' });
    }

    /** Returns Services.json: `{ group: { "name": { services, allowsRackOption, rackOptions } } }`. */
    listServices() {
      return this._fetchJson('/api/services', { method: 'GET' });
    }

    /**
     * Execute a single generator.
     * @param {string} type
     * @param {Object} params
     * @returns {Promise<{ result: object }>}
     */
    generate(type, params) {
      return this._fetchJson('/api/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type, params: params || {} }),
      });
    }

    /* ---------- private ---------- */
    async _fetchJson(path, init) {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), this.timeoutMs);
      try {
        const resp = await fetch(this.baseUrl + path, { ...init, signal: ctrl.signal });
        const text = await resp.text();
        const json = text ? safeParse(text) : null;
        if (!resp.ok) {
          const msg = (json && (json.message || json.error)) || resp.statusText;
          throw new HttpError(msg, resp.status, json);
        }
        return json;
      } catch (e) {
        if (e.name === 'AbortError') throw new HttpError('Request timed out', 0, null);
        if (e instanceof HttpError) throw e;
        throw new HttpError(e.message || 'Network error', 0, null);
      } finally {
        clearTimeout(t);
      }
    }
  }

  function safeParse(text) {
    try { return JSON.parse(text); } catch { return { raw: text }; }
  }

  global.AutoDataApi = { ApiClient, HttpError };
})(window);
