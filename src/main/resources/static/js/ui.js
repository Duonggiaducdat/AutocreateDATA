/* =========================================================================
 *  ui.js — pure DOM rendering helpers.
 *  Knows nothing about HTTP. Receives data, mutates the DOM.
 * ========================================================================= */
(function (global) {
  'use strict';

  /* ---------- tiny DOM helper ---------- */
  function h(tag, attrs = {}, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k in el) { try { el[k] = v; } catch { el.setAttribute(k, v); } }
      else el.setAttribute(k, v);
    }
    for (const child of children.flat()) {
      if (child == null || child === false) continue;
      el.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return el;
  }

  /* ---------- field renderers (auto-pick by ParamType) ---------- */
  const READ_TYPES = new Set(['detail.get-mach', 'detail.get-mayc', 'detail.get-href']); // optional read-style accent

  function renderField(spec, values, onChange) {
    const wrap = h('label', { class: 'd-block' });
    wrap.appendChild(h('div', { class: 'field-label' },
      h('span', { class: 'lbl' }, spec.label || spec.name),
      spec.hint ? h('span', { class: 'hint' }, spec.hint) : null
    ));

    const v = values[spec.name];
    let input;

    switch (spec.type) {
      case 'TEXT':
      case 'PASSWORD': {
        input = h('input', {
          class: 'app-input',
          type: spec.type === 'PASSWORD' ? 'password' : 'text',
          value: v ?? '',
          placeholder: spec.placeholder || '',
          oninput: (e) => onChange(spec.name, e.target.value),
        });
        break;
      }
      case 'NUMBER': {
        const min = spec.min ?? 1, max = spec.max ?? 10;
        const cur = v ?? min;
        const valSpan = h('span', { class: 'range-val' }, String(cur));
        input = h('div', { class: 'range-row' },
          h('input', {
            type: 'range', min, max,
            value: cur,
            oninput: (e) => { valSpan.textContent = e.target.value; onChange(spec.name, Number(e.target.value)); },
          }),
          valSpan
        );
        break;
      }
      case 'BOOLEAN': {
        const row = h('div', { class: 'toggle-row' + (v ? ' on' : '') },
          h('span', { class: 'toggle-track' }, h('span', { class: 'toggle-thumb' })),
          h('span', null, spec.placeholder || spec.label || spec.name)
        );
        row.addEventListener('click', () => {
          const nv = !row.classList.contains('on');
          row.classList.toggle('on', nv);
          onChange(spec.name, nv);
        });
        input = row;
        break;
      }
      case 'ENUM': {
        const sel = h('select', {
          class: 'app-select',
          onchange: (e) => onChange(spec.name, e.target.value),
        });
        (spec.options || []).forEach((o) => {
          const opt = h('option', { value: o }, o);
          if ((v ?? spec.defaultValue) === o) opt.selected = true;
          sel.appendChild(opt);
        });
        input = sel;
        break;
      }
      case 'MULTI_ENUM': {
        const arr = Array.isArray(v) ? v : (Array.isArray(spec.defaultValue) ? spec.defaultValue : []);
        const cur = new Set(arr);
        const row = h('div', { class: 'chips' });
        (spec.options || []).forEach((o) => {
          const chip = h('span', { class: 'chip' + (cur.has(o) ? ' on' : '') }, (cur.has(o) ? '✓ ' : '') + o);
          chip.addEventListener('click', () => {
            if (cur.has(o)) cur.delete(o); else cur.add(o);
            chip.classList.toggle('on', cur.has(o));
            chip.textContent = (cur.has(o) ? '✓ ' : '') + o;
            onChange(spec.name, [...cur]);
          });
          row.appendChild(chip);
        });
        input = row;
        // initialise on first render
        onChange(spec.name, arr);
        break;
      }
      default:
        input = h('div', { class: 'no-params' }, 'unsupported field type: ' + spec.type);
    }

    wrap.appendChild(input);
    return wrap;
  }

  /* ---------- card ---------- */
  function renderCard(meta, handlers) {
    const isRead = /detail\.get-/.test(meta.type) || READ_TYPES.has(meta.type);
    const values = {};
    (meta.params || []).forEach((p) => {
      if (p.defaultValue !== undefined && p.defaultValue !== null) values[p.name] = p.defaultValue;
    });

    const setVal = (k, v) => { values[k] = v; };

    const fieldsHost = h('div', { class: 'gen-body' });
    if (!meta.params || meta.params.length === 0) {
      fieldsHost.appendChild(h('p', { class: 'no-params' }, 'No parameters · pure action.'));
    } else {
      meta.params.forEach((p) => fieldsHost.appendChild(renderField(p, values, setVal)));
    }

    const button = h('button', {
      class: 'btn-generate',
      type: 'button',
      onclick: () => handlers.onGenerate(meta, values, card),
    }, 'Generate');

    const card = h('article', {
      class: 'gen-card' + (isRead ? ' is-read' : ''),
      dataset: { type: meta.type, category: meta.category }
    },
      h('header', { class: 'gen-header' },
        h('div', { class: 'd-flex justify-content-between align-items-start gap-2' },
          h('div', null,
            h('div', { class: 'gen-eyebrow' },
              h('span', { class: 'dot' }), meta.category
            ),
            h('h3', { class: 'gen-title' }, meta.displayName)
          ),
          h('span', { class: 'gen-class' }, meta.type)
        ),
        h('p', { class: 'gen-signature' }, meta.signature || ''),
        meta.description ? h('p', { class: 'gen-signature', style: 'color: var(--ink-300); margin-top: 4px;' }, meta.description) : null
      ),
      fieldsHost,
      h('div', { class: 'gen-footer' }, button),
    );

    // expose helpers for the controller layer
    card._setBusy = (busy) => {
      button.disabled = busy;
      button.innerHTML = busy
        ? '<span class="spinner-dot"></span>Generating…'
        : 'Generate';
    };
    card._setResult = (result, error) => {
      // remove existing result panel
      const old = card.querySelector('.gen-result');
      if (old) old.remove();

      const pre = h('pre', null, error
        ? `error: ${error.message || error}`
        : JSON.stringify(result, null, 2));

      const copyBtn = h('button', { class: 'icon-btn', type: 'button' }, 'copy');
      copyBtn.addEventListener('click', () => {
        navigator.clipboard.writeText(pre.textContent).then(
          () => { copyBtn.textContent = 'copied ✓'; setTimeout(() => copyBtn.textContent = 'copy', 1200); },
          () => { copyBtn.textContent = 'failed'; }
        );
      });

      const clearBtn = h('button', { class: 'icon-btn danger', type: 'button' }, 'clear');
      clearBtn.addEventListener('click', () => card.querySelector('.gen-result').remove());

      const panel = h('div', { class: 'gen-result' + (error ? ' is-error' : '') },
        h('div', { class: 'gen-result-header' },
          h('span', { class: 'label' }, error ? 'Error' : 'Result'),
          h('div', { class: 'gen-result-actions' }, copyBtn, clearBtn)
        ),
        pre
      );
      card.appendChild(panel);
    };

    return card;
  }

  /* ---------- MASTER FLOW CARD (special: reactive, JSON-driven) ----------
   * Reads the services config from /api/services and rewires the form when
   * serviceGroup changes:
   *   - serviceTypes chips repopulate from servicesConfig.group[name].services
   *   - options field hides unless allowsRackOption=true
   * Adds a horizontal step ladder for "run pipeline up to which phase".
   */
  const PHASE_LABELS = {
    'login':         '1 · Đăng nhập',
    'create-order':  '2 · Lên cơ hội',
    'review':        '3 · Đánh giá',
    'note':          '4 · Ghi chú',
    'quote':         '5 · Báo giá',
    'loop':          '6 · Loop dịch vụ',
    'export':        '7 · Xuất Excel',
  };
  const PHASE_ORDER = Object.keys(PHASE_LABELS);

  function renderMasterCard(meta, servicesConfig, handlers) {
    // Map params by name for quick lookup of defaults.
    const paramByName = {};
    (meta.params || []).forEach((p) => { paramByName[p.name] = p; });
    const def = (name, fallback) =>
      paramByName[name] && paramByName[name].defaultValue != null
        ? paramByName[name].defaultValue : fallback;

    // Pick first group if none chosen.
    const groups = Object.keys((servicesConfig && servicesConfig.group) || {});
    const initialGroup = def('serviceGroup', groups[0] || '');

    const values = {
      serviceGroup:     initialGroup,
      customerName:     def('customerName', ''),
      name:             def('name', ''),
      selectedServices: [],                                    // populated reactively below
      optionsService:   def('optionsService', ''),             // csv override (loop only)
      options:          def('options', 'Nhiều hơn hoặc bằng 1 tủ'),
      userNameSale:     def('userNameSale', 'salesale@fpt.com'),
      passSale:         def('passSale', 'REDACTED_OTP_CODE'),
      truongPhong:      def('truongPhong', 'Tpp03'),
      banGiamDoc:       def('banGiamDoc', 'Bgd01'),
      stopAfter:        def('stopAfter', 'export'),
    };

    const setVal = (k, v) => { values[k] = v; };

    /* ----- reactive panes -----
     * We mount placeholder hosts, then re-render their innards when
     * serviceGroup changes. No virtual DOM — just mutate.                */
    const servicesHost = h('div');
    const optionsHost  = h('div');

    function rerenderGroupDependent() {
      const groupName = values.serviceGroup;
      const entry = servicesConfig && servicesConfig.group && servicesConfig.group[groupName];
      const services = (entry && entry.services) || [];
      const allowsRack = !!(entry && entry.allowsRackOption);
      const rackOpts = (entry && entry.rackOptions) || ['Ít hơn 1 tủ', 'Nhiều hơn hoặc bằng 1 tủ'];

      // selectedServices: select-all by default when the group changes
      values.selectedServices = [];

      // Render the chips (services to create in the order)
      servicesHost.innerHTML = '';
      servicesHost.appendChild(buildLabel('selectedServices', 'dịch vụ thêm vào cơ hội'));
      const cur = new Set(values.selectedServices);
      const chipRow = h('div', { class: 'chips' });
      services.forEach((s) => {
        const chip = h('span', { class: 'chip' + (cur.has(s) ? ' on' : '') }, (cur.has(s) ? '✓ ' : '') + s);
        chip.addEventListener('click', () => {
          if (cur.has(s)) cur.delete(s); else cur.add(s);
          chip.classList.toggle('on', cur.has(s));
          chip.textContent = (cur.has(s) ? '✓ ' : '') + s;
          values.selectedServices = [...cur];
        });
        chipRow.appendChild(chip);
      });
      if (services.length === 0) {
        chipRow.appendChild(h('span', { class: 'no-params' }, 'Nhóm này chưa có dịch vụ trong Services.json.'));
      }
      servicesHost.appendChild(chipRow);

      // options field: only visible if allowsRack
      optionsHost.innerHTML = '';
      if (allowsRack) {
        optionsHost.appendChild(buildLabel('options', 'rack qty — Data Center only'));
        const sel = h('select', {
          class: 'app-select',
          onchange: (e) => setVal('options', e.target.value),
        });
        rackOpts.forEach((o) => {
          const opt = h('option', { value: o }, o);
          if (values.options === o) opt.selected = true;
          sel.appendChild(opt);
        });
        optionsHost.appendChild(sel);
      } else {
        // value is sent as "n/a" — backend already ignores it for these groups
        values.options = 'n/a';
      }
    }

    function buildLabel(name, hint) {
      return h('div', { class: 'field-label' },
        h('span', { class: 'lbl' }, name),
        hint ? h('span', { class: 'hint' }, hint) : null
      );
    }

    // SVG eye icons — one for "show" (currently hidden), one for "hide" (currently shown).
    const EYE_OPEN  = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
    const EYE_CLOSED = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10 10 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><path d="M14.12 14.12a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

    function buildTextField(name, label, options) {
      options = options || {};
      const isPassword = !!options.password;
      const wrap = h('label', { class: 'd-block' },
        buildLabel(label || name, options.hint)
      );
      const input = h('input', {
        class: 'app-input' + (isPassword ? ' has-toggle' : ''),
        type: isPassword ? 'password' : 'text',
        value: values[name] ?? '',
        placeholder: options.placeholder || '',
        oninput: (e) => setVal(name, e.target.value),
      });

      if (!isPassword) {
        wrap.appendChild(input);
        return wrap;
      }

      // Password field: wrap with toggle eye button.
      const toggle = h('button', {
        type: 'button',
        class: 'password-toggle',
        title: 'Hiện / ẩn mật khẩu',
        'aria-label': 'Hiện / ẩn mật khẩu',
      });
      toggle.innerHTML = EYE_CLOSED;
      toggle.addEventListener('click', () => {
        const showing = input.type === 'text';
        input.type = showing ? 'password' : 'text';
        toggle.innerHTML = showing ? EYE_CLOSED : EYE_OPEN;
      });

      const passWrap = h('div', { class: 'password-wrap' }, input, toggle);
      wrap.appendChild(passWrap);
      return wrap;
    }

    function buildGroupSelect() {
      const wrap = h('label', { class: 'd-block' },
        buildLabel('serviceGroup', 'drives service types')
      );
      const sel = h('select', {
        class: 'app-select',
        onchange: (e) => {
          setVal('serviceGroup', e.target.value);
          rerenderGroupDependent();
        },
      });
      groups.forEach((g) => {
        const opt = h('option', { value: g }, g);
        if (values.serviceGroup === g) opt.selected = true;
        sel.appendChild(opt);
      });
      wrap.appendChild(sel);
      return wrap;
    }

    function buildStepLadder() {
      const wrap = h('div');
      wrap.appendChild(buildLabel('stopAfter', 'chạy pipeline tới phase nào'));
      const row = h('div', { class: 'step-ladder' });
      PHASE_ORDER.forEach((phase) => {
        const node = h('button', {
          type: 'button',
          class: 'step-chip',
          onclick: () => {
            setVal('stopAfter', phase);
            // re-highlight
            row.querySelectorAll('.step-chip').forEach((el) => {
              const ph = el.dataset.phase;
              el.classList.toggle('active', PHASE_ORDER.indexOf(ph) <= PHASE_ORDER.indexOf(phase));
              el.classList.toggle('terminal', ph === phase);
            });
          },
          dataset: { phase },
        }, PHASE_LABELS[phase]);
        // initial paint
        if (PHASE_ORDER.indexOf(phase) <= PHASE_ORDER.indexOf(values.stopAfter)) node.classList.add('active');
        if (phase === values.stopAfter) node.classList.add('terminal');
        row.appendChild(node);
      });
      wrap.appendChild(row);
      return wrap;
    }

    const button = h('button', {
      class: 'btn-generate',
      type: 'button',
    }, 'Run Master Flow');

    const card = h('article', { class: 'gen-card master-card' },
      h('header', { class: 'gen-header' },
        h('div', { class: 'd-flex justify-content-between align-items-start gap-2' },
          h('div', null,
            h('div', { class: 'gen-eyebrow' }, h('span', { class: 'dot' }), 'Master'),
            h('h3', { class: 'gen-title' }, meta.displayName || 'Master Flow')
          ),
          h('span', { class: 'gen-class' }, meta.type)
        ),
        h('p', { class: 'gen-signature' }, meta.signature || ''),
        meta.description
          ? h('p', { class: 'gen-signature', style: 'color: var(--ink-300); margin-top: 4px;' }, meta.description)
          : null
      ),
      h('div', { class: 'gen-body' },
        h('div', { class: 'field-grid' },
          buildGroupSelect(),
          buildTextField('customerName', 'customerName', { placeholder: 'name / mã KH / MST / CCCD' }),
          buildTextField('name', 'name (opportunity)',{ placeholder: 'Tên cơ hội' }),
          buildTextField('userNameSale', 'userNameSale'),
          buildTextField('passSale', 'passSale', { password: true }),
          buildTextField('truongPhong', 'truongPhong', { hint: 'reviewer' }),
          buildTextField('banGiamDoc', 'banGiamDoc', { hint: 'approver' })
        ),
        servicesHost,
        buildTextField('optionsService', 'optionsService', {
          placeholder: 'csv override · để trống = dùng selectedServices',
          hint: 'chỉ ảnh hưởng phase Loop',
        }),
        optionsHost,
        buildStepLadder()
      ),
      h('div', { class: 'gen-footer' }, button),
    );

    button.addEventListener('click', () => handlers.onGenerate(meta, values, card));

    card._setBusy = (busy) => {
      button.disabled = busy;
      button.innerHTML = busy ? '<span class="spinner-dot"></span>Running pipeline…' : 'Run Master Flow';
    };
    card._setResult = (result, error) => {
      const old = card.querySelector('.gen-result');
      if (old) old.remove();
      const pre = h('pre', null, error
        ? `error: ${error.message || error}`
        : JSON.stringify(result, null, 2));
      const copyBtn = h('button', { class: 'icon-btn', type: 'button' }, 'copy');
      copyBtn.addEventListener('click', () => {
        navigator.clipboard.writeText(pre.textContent).then(
          () => { copyBtn.textContent = 'copied ✓'; setTimeout(() => copyBtn.textContent = 'copy', 1200); },
          () => { copyBtn.textContent = 'failed'; }
        );
      });
      const clearBtn = h('button', { class: 'icon-btn danger', type: 'button' }, 'clear');
      clearBtn.addEventListener('click', () => card.querySelector('.gen-result').remove());
      const panel = h('div', { class: 'gen-result' + (error ? ' is-error' : '') },
        h('div', { class: 'gen-result-header' },
          h('span', { class: 'label' }, error ? 'Error' : 'Result'),
          h('div', { class: 'gen-result-actions' }, copyBtn, clearBtn)
        ),
        pre
      );
      card.appendChild(panel);
    };

    // initial reactive paint
    rerenderGroupDependent();

    return card;
  }

  /* ---------- catalog grouping & rendering ---------- */
  function renderCatalog(host, generators, handlers) {
    host.innerHTML = '';
    if (!generators || generators.length === 0) {
      host.appendChild(h('div', { class: 'empty-state' }, 'No generators match.'));
      return;
    }

    // group by category, preserve backend order
    const groups = new Map();
    generators.forEach((g) => {
      if (!groups.has(g.category)) groups.set(g.category, []);
      groups.get(g.category).push(g);
    });

    for (const [cat, items] of groups) {
      const section = h('section', null,
        h('div', { class: 'cat-header' },
          h('div', null,
            h('div', { class: 'eyebrow' }, 'Category'),
            h('h2', null, cat)
          ),
          h('div', { class: 'count' }, `${items.length} module${items.length > 1 ? 's' : ''}`)
        ),
        h('div', { class: 'card-grid' }, ...items.map((m) => renderCard(m, handlers)))
      );
      host.appendChild(section);
    }
  }

  function renderCategoryRow(host, categories, current, onPick) {
    host.innerHTML = '';
    ['All', ...categories].forEach((c) => {
      const chip = h('button', {
        class: 'category-chip' + (c === current ? ' active' : ''),
        type: 'button',
        onclick: () => onPick(c),
      }, c);
      host.appendChild(chip);
    });
  }

  /* ---------- toasts ---------- */
  function toast(level, message, timeoutMs = 3500) {
    const stack = document.getElementById('toast-stack');
    if (!stack) return;
    const node = h('div', { class: 'app-toast ' + level + ' toast show', role: 'alert' },
      h('div', { class: 'toast-body' }, message)
    );
    stack.appendChild(node);
    setTimeout(() => node.remove(), timeoutMs);
  }

  /* ---------- health pill ---------- */
  function setHealth(state, label) {
    const pill = document.getElementById('health-pill');
    if (!pill) return;
    pill.dataset.state = state;
    pill.textContent = label;
  }

  global.AutoDataUI = { h, renderCard, renderMasterCard, renderCatalog, renderCategoryRow, toast, setHealth };
})(window);
//      const section = h('section', null,
//        h('div', { class: 'cat-header' },
//          h('div', null,
//            h('div', { class: 'eyebrow' }, 'Category'),
//            h('h2', null, cat)
//          ),
//          h('div', { class: 'count' }, items.length + ' module' + (items.length > 1 ? 's' : ''))
//        ),
//        h('div', { class: 'card-grid' }, ...items.map((m) => renderCard(m, handlers)))
//      );
//      host.appendChild(section);
//    }
//  }
//
//  function renderCategoryRow(host, categories, current, onPick) {
//    host.innerHTML = '';
//    ['All', ...categories].forEach((c) => {
//      const chip = h('button', {
//        class: 'category-chip' + (c === current ? ' active' : ''),
//        type: 'button',
//        onclick: () => onPick(c),
//      }, c);
//      host.appendChild(chip);
//    });
//  }
//
//  /* ---------- toasts ---------- */
//  function toast(level, message, timeoutMs = 3500) {
//    const stack = document.getElementById('toast-stack');
//    if (!stack) return;
//    const node = h('div', { class: 'app-toast ' + level + ' toast show', role: 'alert' },
//      h('div', { class: 'toast-body' }, message)
//    );
//    stack.appendChild(node);
//    setTimeout(() => node.remove(), timeoutMs);
//  }
//
//  /* ---------- health pill ---------- */
//  function setHealth(state, label) {
//    const pill = document.getElementById('health-pill');
//    if (!pill) return;
//    pill.dataset.state = state;
//    pill.textContent = label;
//  }
//
//  global.AutoDataUI = { h, renderCard, renderMasterCard, renderCatalog, renderCategoryRow, toast, setHealth };
//})(window);
