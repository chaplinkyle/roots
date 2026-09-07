(() => {
  'use strict';

  const currentRoot = () => document.getElementById('roots');
  const applicationUrl = (value) => {
    const mount = currentRoot()?.dataset.rootsMountPath || '';
    if (!mount || typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')
        || value === mount || value.startsWith(`${mount}/`)) return value;
    return `${mount}${value}`;
  };
  const actionStates = new WeakMap();
  const actionState = (root) => {
    if (!actionStates.has(root)) {
      actionStates.set(root, { queue: Promise.resolve(), queued: 0, blocked: false });
    }
    return actionStates.get(root);
  };
  const maxQueuedActions = 128;
  const delayedInputs = new Map();
  let activeResourceUrl = window.location.href;
  let renderQueue = Promise.resolve();
  let stream = null;
  let developmentStream = null;
  let inspectorPanel = null;
  let inspectorOpen = false;
  let renderGeneration = 0;
  let navigationSequence = 0;
  let scrollFrame = null;
  let suppressActionEvents = false;
  let validationSequence = 0;
  const portalHosts = new Map();
  const modalStates = new Map();
  const validationStates = new WeakMap();
  const controlEdits = new WeakMap();
  let editSequence = 0;

  const editableControl = (element) => element instanceof HTMLInputElement
    || element instanceof HTMLTextAreaElement || element instanceof HTMLSelectElement;

  const rememberEdit = (event) => {
    if (!suppressActionEvents && editableControl(event.target)) {
      controlEdits.set(event.target, ++editSequence);
    }
  };

  // An action may acknowledge only the values captured for that action. A newer
  // local edit, or a control outside its form, must survive the server response.
  const retainEdit = (element, acknowledged) => controlEdits.has(element)
    && controlEdits.get(element) !== acknowledged?.get(element);

  const widgets = new Map();
  const widgetModules = new Map();
  const widgetProps = (host) => Object.freeze(Object.fromEntries(Array.from(host.attributes)
    .filter(attribute => attribute.name.startsWith('data-widget-'))
    .map(attribute => [attribute.name.substring(12), attribute.value])));
  const widgetSignature = (props) => JSON.stringify(Object.entries(props).sort(([a], [b]) => a.localeCompare(b)));
  const widgetEdited = (state) => state.dirty || Array.from(state.host.querySelectorAll('input,textarea,select'))
    .some(control => controlEdits.has(control));
  const widgetCurrent = (state) => widgets.get(state.host) === state && state.host.isConnected && !state.failed;
  const widgetError = (state, phase, error) => {
    console.warn(`Roots widget ${phase} failed`, error);
    window.dispatchEvent(new CustomEvent('roots:widget-error', {
      detail: { host: state.host, module: state.spec, phase, error }
    }));
  };
  const widgetInvoke = (callback) => {
    const previous = suppressActionEvents;
    suppressActionEvents = true;
    try { return callback(); } finally { suppressActionEvents = previous; }
  };
  const widgetDestroyHandle = (state, handle) => {
    if (typeof handle?.destroy !== 'function') return;
    try {
      Promise.resolve(widgetInvoke(() => handle.destroy())).catch(error => widgetError(state, 'destroy', error));
    } catch (error) { widgetError(state, 'destroy', error); }
  };
  const stopWidget = (state) => {
    clearTimeout(state.timer);
    state.controller.abort();
    if (state.handle) {
      const handle = state.handle;
      state.handle = null;
      widgetDestroyHandle(state, handle);
    }
  };
  const failWidget = (state, phase, error) => {
    if (!widgetCurrent(state)) return;
    const edited = widgetEdited(state);
    const frozen = edited ? state.host.cloneNode(true) : null;
    if (frozen) {
      const originals = state.host.querySelectorAll('input,textarea,select');
      const copies = frozen.querySelectorAll('input,textarea,select');
      originals.forEach((control, index) => {
        const copy = copies[index];
        if (control instanceof HTMLInputElement && control.type === 'file') copy.files = control.files;
        else if (control instanceof HTMLSelectElement) {
          Array.from(control.options).forEach((option, i) => { copy.options[i].selected = option.selected; });
        } else { copy.value = control.value; if ('checked' in control) copy.checked = control.checked; }
      });
    }
    state.failed = true;
    state.dirty = edited;
    stopWidget(state);
    // A failed editor must leave unsaved content available for copying.
    state.host.replaceChildren(...(frozen ? Array.from(frozen.childNodes) : state.fallback.map(node => node.cloneNode(true))));
    if (edited) {
      const notice = document.createElement('span');
      notice.setAttribute('role', 'status');
      notice.textContent = 'This widget stopped updating. Keep a copy of unsaved content before reloading.';
      state.host.append(notice);
    }
    widgetError(state, phase, error);
  };
  const destroyWidgetsWithin = (node) => {
    for (const [host, state] of widgets) {
      if (host === node || node.contains?.(host)) {
        widgets.delete(host);
        stopWidget(state);
      }
    }
  };
  const widgetDeadline = (state, phase) => {
    clearTimeout(state.timer);
    state.timer = setTimeout(() => failWidget(state, phase, new Error('Widget lifecycle exceeded 30 seconds')), 30000);
  };
  const updateWidget = (state) => {
    if (!widgetCurrent(state) || !state.handle || state.updating || state.delivered === state.signature) return;
    const props = state.props;
    state.delivered = state.signature;
    state.updating = true;
    widgetDeadline(state, 'update');
    Promise.resolve().then(() => widgetCurrent(state)
      ? widgetInvoke(() => state.handle.update(props)) : undefined)
      .catch(error => failWidget(state, 'update', error))
      .finally(() => {
        clearTimeout(state.timer);
        state.updating = false;
        // Only the latest pending props are retained while an async update runs.
        updateWidget(state);
      });
  };
  const syncWidget = (host) => {
    const props = widgetProps(host);
    const signature = widgetSignature(props);
    let state = widgets.get(host);
    if (state) {
      state.props = props;
      state.signature = signature;
      updateWidget(state);
      return;
    }
    state = { host, spec: host.dataset.rootsWidget, props, signature, delivered: signature,
      controller: new AbortController(), handle: null, failed: false, dirty: false, updating: false,
      fallback: Array.from(host.childNodes, node => node.cloneNode(true)), timer: null };
    widgets.set(host, state);
    widgetDeadline(state, 'mount');
    Promise.resolve().then(async () => {
      const url = new URL(applicationUrl(state.spec), window.location.href);
      if (!state.spec?.startsWith('/') || state.spec.startsWith('//') || url.origin !== window.location.origin || url.hash) {
        throw new Error('Widget modules must use a same-origin application path');
      }
      if (!widgetModules.has(url.href)) {
        if (widgetModules.size >= 64) throw new Error('A document may load at most 64 distinct widget module URLs');
        widgetModules.set(url.href, import(url.href));
      }
      const module = await widgetModules.get(url.href);
      if (!widgetCurrent(state)) return;
      if (typeof module.mount !== 'function') throw new Error('Widget module must export mount(host, context)');
      state.delivered = state.signature;
      const handle = await widgetInvoke(() => module.mount(host, Object.freeze({
        props: state.props, signal: state.controller.signal,
        setDirty: (dirty) => { if (widgetCurrent(state)) state.dirty = Boolean(dirty); }
      })));
      if (!widgetCurrent(state)) {
        widgetDestroyHandle(state, handle);
        return;
      }
      state.handle = handle;
      if (typeof handle?.update !== 'function' || typeof handle?.destroy !== 'function') {
        throw new Error('Widget mount must return update(props) and destroy() functions');
      }
      clearTimeout(state.timer);
      updateWidget(state);
    }).catch(error => failWidget(state, 'mount', error));
  };
  const syncWidgets = (scope = currentRoot(), includePortals = true) => {
    for (const [host, state] of widgets) {
      if (!host.isConnected) {
        widgets.delete(host);
        stopWidget(state);
      }
    }
    const roots = [scope, ...(includePortals ? portalHosts.values() : [])].filter(Boolean);
    for (const root of roots) {
      const hosts = [...(root.matches?.('[data-roots-widget]') ? [root] : []), ...root.querySelectorAll('[data-roots-widget]')];
      for (const host of hosts) {
        if (!host.parentElement?.closest('[data-roots-widget]')) syncWidget(host);
      }
    }
  };

  const publishStale = (kind, view) => {
    window.dispatchEvent(new CustomEvent('roots:stale', {
      detail: { kind, view: typeof view === 'string' ? view : null }
    }));
  };

  const sameDocumentResource = (left, right) => {
    const first = new URL(left, window.location.href);
    const second = new URL(right, window.location.href);
    return first.origin === second.origin
      && first.pathname === second.pathname
      && first.search === second.search;
  };

  const savedScrollPosition = (state) => {
    const value = state && typeof state === 'object' ? state.__rootsScroll : null;
    if (!value || !Number.isFinite(value.x) || !Number.isFinite(value.y)
        || value.x < 0 || value.y < 0) return null;
    return { x: value.x, y: value.y };
  };

  const historyStateAt = (x, y) => {
    const current = history.state;
    const state = current && typeof current === 'object' && !Array.isArray(current)
      ? { ...current }
      : {};
    state.__rootsScroll = { x, y };
    return state;
  };

  const saveScrollPosition = () => {
    try {
      history.replaceState(historyStateAt(window.scrollX, window.scrollY), '', window.location.href);
    } catch (error) {
      console.warn('Roots could not save browser scroll position', error);
    }
  };

  const fragmentTarget = (url) => {
    if (!url.hash || url.hash === '#' || url.hash.startsWith('#:~:text=')) return null;
    let name = url.hash.substring(1);
    try { name = decodeURIComponent(name); } catch (error) { /* use the literal fragment */ }
    return document.getElementById(name)
      || document.querySelector(`[name="${CSS.escape(name)}"]`);
  };

  const restoreNavigationScroll = (value, savedPosition = null) => {
    const url = new URL(value, window.location.href);
    if (savedPosition) {
      window.scrollTo(savedPosition.x, savedPosition.y);
      return;
    }
    const target = fragmentTarget(url);
    if (target) target.scrollIntoView({ block: 'start' });
    else window.scrollTo(0, 0);
  };

  const keyOf = (node) => node.nodeType === Node.ELEMENT_NODE
    ? node.getAttribute('data-roots-key')
    : null;

  const sameKind = (current, next) => {
    if (!current || current.nodeType !== next.nodeType) return false;
    if (current.nodeType === Node.ELEMENT_NODE && current.tagName !== next.tagName) return false;
    const currentKey = keyOf(current);
    const nextKey = keyOf(next);
    return currentKey === null && nextKey === null || currentKey !== null && currentKey === nextKey;
  };

  const syncAttributes = (current, next, acknowledged) => {
    const retain = retainEdit(current, acknowledged);
    const value = retain && editableControl(current) ? current.value : null;
    const checked = retain && current instanceof HTMLInputElement ? current.checked : null;
    for (const attribute of Array.from(current.attributes)) {
      if (!next.hasAttribute(attribute.name)) current.removeAttribute(attribute.name);
    }
    for (const attribute of Array.from(next.attributes)) {
      if (current.getAttribute(attribute.name) !== attribute.value) {
        current.setAttribute(attribute.name, attribute.value);
      }
    }
    if (current instanceof HTMLInputElement) {
      if (current.type === 'checkbox' || current.type === 'radio') {
        current.checked = retain ? checked : next.checked;
      }
      const desired = retain ? value : next.value;
      if (current.value !== desired) current.value = desired;
    } else if (current instanceof HTMLTextAreaElement && current.value !== next.value) {
      current.value = retain ? value : next.value;
    }
  };

  const morphNode = (current, next, acknowledged) => {
    if (!sameKind(current, next)) {
      const replacement = next.cloneNode(true);
      destroyWidgetsWithin(current);
      current.replaceWith(replacement);
      return replacement;
    }
    if (current.nodeType === Node.TEXT_NODE || current.nodeType === Node.COMMENT_NODE) {
      if (current.nodeValue !== next.nodeValue) current.nodeValue = next.nodeValue;
      return current;
    }
    const retainedOptions = current instanceof HTMLSelectElement && retainEdit(current, acknowledged)
      ? Array.from(current.selectedOptions, option => option.value) : null;
    const state = widgets.get(current);
    const sameWidget = state && state.spec === next.getAttribute('data-roots-widget');
    if (state && !sameWidget) destroyWidgetsWithin(current);
    syncAttributes(current, next, acknowledged);
    if (sameWidget) {
      state.fallback = Array.from(next.childNodes, node => node.cloneNode(true));
      if (state.failed && !widgetEdited(state)) current.replaceChildren(...state.fallback.map(node => node.cloneNode(true)));
    } else {
      if (state) current.replaceChildren();
      morphChildren(current, next, acknowledged);
    }
    if (current instanceof HTMLSelectElement) {
      const selected = new Set(retainedOptions ?? Array.from(next.selectedOptions, option => option.value));
      for (const option of current.options) option.selected = selected.has(option.value);
    }
    return current;
  };

  const morphChildren = (current, next, acknowledged) => {
    const existing = Array.from(current.childNodes);
    const keyed = new Map(existing.filter(node => keyOf(node) !== null).map(node => [keyOf(node), node]));
    const used = new Set();
    let cursor = current.firstChild;

    for (const desired of Array.from(next.childNodes)) {
      const key = keyOf(desired);
      let match = key === null ? null : keyed.get(key);
      if (!match && cursor && !used.has(cursor) && sameKind(cursor, desired)) match = cursor;
      if (!match && key === null) {
        match = existing.find(node => !used.has(node) && keyOf(node) === null && sameKind(node, desired));
      }
      if (match) {
        if (match !== cursor) current.insertBefore(match, cursor);
        const actual = morphNode(match, desired, acknowledged);
        used.add(actual);
        cursor = actual.nextSibling;
      } else {
        const inserted = desired.cloneNode(true);
        current.insertBefore(inserted, cursor);
        used.add(inserted);
        cursor = inserted.nextSibling;
      }
    }
    for (const node of existing) {
      if (!used.has(node) && node.parentNode === current) {
        destroyWidgetsWithin(node);
        node.remove();
      }
    }
  };

  const focusModal = (dialog) => {
    const initialRef = dialog.dataset.rootsModalInitialRef;
    const requested = initialRef
      ? dialog.querySelector(`[data-roots-ref="${CSS.escape(initialRef)}"]`)
      : null;
    const fallback = dialog.querySelector(
      '[autofocus], button:not([disabled]), [href], input:not([disabled]), '
        + 'select:not([disabled]), textarea:not([disabled]), '
        + '[tabindex]:not([tabindex="-1"])'
    );
    const target = requested || fallback || dialog;
    const previousSuppression = suppressActionEvents;
    suppressActionEvents = true;
    try {
      target.focus({ preventScroll: true });
    } finally {
      suppressActionEvents = previousSuppression;
    }
  };

  const modalFocusable = (dialog) => Array.from(dialog.querySelectorAll(
    'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), '
      + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )).filter(element => element.getClientRects().length > 0);

  const trapModalTab = (event) => {
    if (event.key !== 'Tab' || event.altKey || event.controlKey || event.metaKey) return;
    const states = Array.from(modalStates.values());
    const dialog = states.length ? states[states.length - 1].dialog : null;
    if (!dialog?.open) return;
    const focusable = modalFocusable(dialog);
    const active = document.activeElement;
    let target = null;
    if (!focusable.length) {
      target = dialog;
    } else if (event.shiftKey && (active === focusable[0] || !dialog.contains(active))) {
      target = focusable[focusable.length - 1];
    } else if (!event.shiftKey && (active === focusable[focusable.length - 1]
        || active === dialog || !dialog.contains(active))) {
      target = focusable[0];
    }
    if (!target) return;
    event.preventDefault();
    const previousSuppression = suppressActionEvents;
    suppressActionEvents = true;
    try {
      target.focus({ preventScroll: true });
    } finally {
      suppressActionEvents = previousSuppression;
    }
  };

  const deactivateModal = (id) => {
    const state = modalStates.get(id);
    if (!state) return;
    modalStates.delete(id);
    const previousSuppression = suppressActionEvents;
    suppressActionEvents = true;
    try {
      if (state.dialog.open && typeof state.dialog.close === 'function') state.dialog.close();
    } catch (error) {
      console.warn('Roots modal close unavailable', error);
    }
    const opener = state.opener;
    try {
      if (opener?.isConnected) {
        opener.focus({ preventScroll: true });
      }
    } finally {
      suppressActionEvents = previousSuppression;
    }
  };

  const syncModal = (id, host) => {
    const dialog = host.querySelector('dialog[data-roots-modal]');
    const previous = modalStates.get(id);
    if (!dialog) {
      deactivateModal(id);
      return;
    }
    if (previous?.dialog === dialog) return;
    if (previous) deactivateModal(id);
    const active = document.activeElement instanceof Element ? document.activeElement : null;
    modalStates.set(id, { dialog, opener: active });
    const previousSuppression = suppressActionEvents;
    suppressActionEvents = true;
    try {
      if (typeof dialog.showModal === 'function') {
        try {
          dialog.removeAttribute('open');
          dialog.showModal();
        } catch (error) {
          dialog.setAttribute('open', '');
          console.warn('Roots native modal unavailable; using open dialog fallback', error);
        }
      } else {
        dialog.setAttribute('open', '');
      }
      focusModal(dialog);
    } finally {
      suppressActionEvents = previousSuppression;
    }
  };

  const syncPortals = (root, acknowledged) => {
    const active = new Set();
    for (const template of Array.from(root?.querySelectorAll('template[data-roots-portal]') || [])) {
      const id = template.dataset.rootsPortal;
      if (!id || active.has(id)) {
        template.remove();
        continue;
      }
      active.add(id);
      let host = portalHosts.get(id);
      if (!host?.isConnected) {
        host = document.createElement('div');
        host.dataset.rootsPortalHost = id;
        document.body.append(host);
        portalHosts.set(id, host);
      }
      morphChildren(host, template.content, acknowledged);
      syncModal(id, host);
      template.remove();
    }
    for (const [id, host] of portalHosts) {
      if (!active.has(id)) {
        deactivateModal(id);
        destroyWidgetsWithin(host);
        host.remove();
        portalHosts.delete(id);
      }
    }
  };

  const captureForm = (element) => {
    const form = element.matches('form') ? element : element.form || element.closest('form');
    const values = form ? new FormData(form) : new FormData();
    if (element.name && !values.has(element.name)) values.append(element.name, element.value ?? '');
    const edits = new Map();
    for (const control of form ? form.elements : [element]) {
      if (editableControl(control) && control.name && !control.matches(':disabled')) {
        edits.set(control, controlEdits.get(control));
      }
    }
    return { values, edits };
  };

  const applyOptimistic = (element) => {
    const encoded = element.getAttribute('data-roots-optimistic');
    if (!encoded) return () => {};
    const generation = renderGeneration;
    let effects;
    try {
      effects = JSON.parse(encoded);
    } catch (error) {
      console.error('Invalid Roots optimistic effects', error);
      return () => {};
    }
    if (!Array.isArray(effects)) return () => {};
    const rollbacks = [];
    for (const effect of effects) {
      if (!effect || typeof effect.target !== 'string') continue;
      const target = document.querySelector(
        `[data-roots-ref="${CSS.escape(effect.target)}"]`
      );
      if (!target) continue;
      if (effect.type === 'HIDE') {
        const hidden = target.hidden;
        target.hidden = true;
        rollbacks.push(() => { if (target.isConnected) target.hidden = hidden; });
      } else if (effect.type === 'TEXT') {
        const content = target.textContent;
        target.textContent = effect.value ?? '';
        rollbacks.push(() => { if (target.isConnected) target.textContent = content; });
      } else if (effect.type === 'VALUE' && 'value' in target) {
        const value = target.value;
        target.value = effect.value ?? '';
        rollbacks.push(() => { if (target.isConnected) target.value = value; });
      } else if (effect.type === 'DISABLE' && 'disabled' in target) {
        const disabled = target.disabled;
        target.disabled = true;
        rollbacks.push(() => { if (target.isConnected) target.disabled = disabled; });
      }
    }
    return () => {
      if (renderGeneration !== generation) return;
      for (let index = rollbacks.length - 1; index >= 0; index--) rollbacks[index]();
    };
  };

  const beginPending = (element, root) => {
    const scope = element.closest('[data-roots-pending-scope]');
    const previousScopePending = scope?.getAttribute('data-roots-pending') ?? null;
    const previousScopeBusy = scope?.getAttribute('aria-busy') ?? null;
    root.setAttribute('aria-busy', 'true');
    document.documentElement.dataset.rootsPending = 'true';
    if (scope) {
      scope.setAttribute('data-roots-pending', 'true');
      scope.setAttribute('aria-busy', 'true');
    }
    return () => {
      root.removeAttribute('aria-busy');
      delete document.documentElement.dataset.rootsPending;
      if (!scope?.isConnected) return;
      if (previousScopePending === null) scope.removeAttribute('data-roots-pending');
      else scope.setAttribute('data-roots-pending', previousScopePending);
      if (previousScopeBusy === null) scope.removeAttribute('aria-busy');
      else scope.setAttribute('aria-busy', previousScopeBusy);
    };
  };

  const validationScope = (element) => element.closest('form')
    || element.closest('[data-roots-portal-host]')
    || currentRoot();

  const clearValidation = (scope, field = null) => {
    const entries = validationStates.get(scope) || [];
    const retained = [];
    for (const entry of entries) {
      if (field === null || entry.field === field) entry.restore();
      else retained.push(entry);
    }
    if (retained.length) validationStates.set(scope, retained);
    else validationStates.delete(scope);
  };

  const clearFieldValidation = (target) => {
    if (!(target instanceof Element) || !target.name) return;
    const scope = validationScope(target);
    if (scope) clearValidation(scope, target.name);
  };

  const validValidationPayload = (payload) => {
    if (!payload || typeof payload.error !== 'string'
        || !payload.error.trim() || payload.error.length > 2048
        || !payload.fields || typeof payload.fields !== 'object'
        || Array.isArray(payload.fields)) return false;
    const fields = Object.entries(payload.fields);
    if (!fields.length || fields.length > 128) return false;
    let totalMessages = 0;
    for (const [field, messages] of fields) {
      if (!field.trim() || field.length > 128 || /[\x00-\x1f\x7f-\x9f]/.test(field)
          || !Array.isArray(messages) || !messages.length || messages.length > 16) return false;
      totalMessages += messages.length;
      if (totalMessages > 256 || messages.some(message => typeof message !== 'string'
          || !message.trim() || message.length > 2048)) return false;
    }
    return true;
  };

  const applyValidation = (element, payload) => {
    const scope = validationScope(element);
    if (!scope) return;
    clearValidation(scope);
    const entries = [];
    for (const summary of scope.querySelectorAll('[data-roots-validation-summary]')) {
      const previousChildren = Array.from(summary.childNodes);
      const previousHidden = summary.hidden;
      summary.replaceChildren(document.createTextNode(payload.error));
      summary.hidden = false;
      entries.push({
        field: null,
        restore: () => {
          if (!summary.isConnected) return;
          summary.replaceChildren(...previousChildren);
          summary.hidden = previousHidden;
        }
      });
    }
    for (const [field, messages] of Object.entries(payload.fields)) {
      const messageIds = [];
      for (const message of scope.querySelectorAll(
        `[data-roots-validation-for="${CSS.escape(field)}"]`
      )) {
        const previousId = message.getAttribute('id');
        const previousChildren = Array.from(message.childNodes);
        const previousHidden = message.hidden;
        if (!message.id) message.id = `roots-validation-${++validationSequence}`;
        messageIds.push(message.id);
        message.replaceChildren(document.createTextNode(messages.join(' ')));
        message.hidden = false;
        entries.push({
          field,
          restore: () => {
            if (!message.isConnected) return;
            if (previousId === null) message.removeAttribute('id');
            else message.setAttribute('id', previousId);
            message.replaceChildren(...previousChildren);
            message.hidden = previousHidden;
          }
        });
      }
      for (const control of scope.querySelectorAll(`[name="${CSS.escape(field)}"]`)) {
        const previousInvalid = control.getAttribute('aria-invalid');
        const previousDescribedBy = control.getAttribute('aria-describedby');
        control.setAttribute('aria-invalid', 'true');
        if (messageIds.length) {
          const ids = new Set((previousDescribedBy || '').split(/\s+/).filter(Boolean));
          messageIds.forEach(id => ids.add(id));
          control.setAttribute('aria-describedby', Array.from(ids).join(' '));
        }
        entries.push({
          field,
          restore: () => {
            if (!control.isConnected) return;
            if (previousInvalid === null) control.removeAttribute('aria-invalid');
            else control.setAttribute('aria-invalid', previousInvalid);
            if (previousDescribedBy === null) control.removeAttribute('aria-describedby');
            else control.setAttribute('aria-describedby', previousDescribedBy);
          }
        });
      }
    }
    if (entries.length) validationStates.set(scope, entries);
  };

  const rememberFocus = () => {
    const active = document.activeElement;
    if (!active || !active.name) return null;
    return { name: active.name, start: active.selectionStart, end: active.selectionEnd };
  };

  const restoreFocus = (focus) => {
    if (!focus) return;
    const next = document.querySelector(`[name="${CSS.escape(focus.name)}"]`);
    if (!next) return;
    suppressActionEvents = true;
    try {
      next.focus({ preventScroll: true });
    } finally {
      suppressActionEvents = false;
    }
    if (typeof next.setSelectionRange === 'function' && focus.start !== null) {
      next.setSelectionRange(focus.start, focus.end);
    }
  };

  const commitViewTransition = (update, requested) => {
    const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
    if (requested && !reducedMotion && typeof document.startViewTransition === 'function') {
      try {
        const transition = document.startViewTransition(update);
        if (transition?.updateCallbackDone?.then) return transition.updateCallbackDone;
      } catch (error) {
        console.warn('Roots view transition unavailable; applying update immediately', error);
      }
    }
    update();
    return Promise.resolve();
  };

  const announce = (message, priority) => {
    if (typeof message !== 'string') return;
    const region = document.querySelector(
      `[data-roots-announcer="${priority === 'assertive' ? 'assertive' : 'polite'}"]`
    );
    if (!region) return;
    region.replaceChildren();
    window.setTimeout(() => {
      if (!region.isConnected) return;
      region.replaceChildren(document.createTextNode(message));
      window.dispatchEvent(new CustomEvent('roots:announce', {
        detail: { message, priority: priority === 'assertive' ? 'assertive' : 'polite' }
      }));
    }, 0);
  };

  const selectText = (target) => {
    if (!target) return;
    const previousSuppression = suppressActionEvents;
    suppressActionEvents = true;
    try {
      target.focus?.({ preventScroll: true });
      if (typeof target.select === 'function') {
        target.select();
      } else if (target.isContentEditable) {
        const selection = window.getSelection?.();
        const range = document.createRange();
        range.selectNodeContents(target);
        selection?.removeAllRanges();
        selection?.addRange(range);
      }
    } finally {
      suppressActionEvents = previousSuppression;
    }
  };

  const applyPatch = (payload, acknowledged) => {
    const root = currentRoot();
    if (root && actionState(root).blocked) return;
    if (!root || String(payload.protocol) !== root.dataset.rootsProtocol) {
      recoverView('protocol');
      return;
    }
    if (typeof payload.view !== 'string') {
      recoverView('protocol');
      return;
    }
    if (payload.view !== root.dataset.rootsView) {
      publishStale('patch', payload.view);
      return;
    }
    const runEffects = () => {
      for (const effect of payload.effects || []) {
        const target = effect.target
          ? document.querySelector(`[data-roots-ref="${CSS.escape(effect.target)}"]`)
          : null;
        if (effect.type === 'FOCUS' && target) {
          suppressActionEvents = true;
          try {
            target.focus({ preventScroll: true });
          } finally {
            suppressActionEvents = false;
          }
        }
        if (effect.type === 'BLUR' && target === document.activeElement) {
          const previousSuppression = suppressActionEvents;
          suppressActionEvents = true;
          try {
            target.blur?.();
          } finally {
            suppressActionEvents = previousSuppression;
          }
        }
        if (effect.type === 'SELECT_TEXT') selectText(target);
        if (effect.type === 'SCROLL_INTO_VIEW') target?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        if (effect.type === 'COPY_TO_CLIPBOARD' && navigator.clipboard) {
          Promise.resolve(navigator.clipboard.writeText(effect.value ?? '')).catch(error => {
            console.warn('Roots clipboard effect unavailable', error);
          });
        }
        if (effect.type === 'ANNOUNCE_POLITE') announce(effect.value, 'polite');
        if (effect.type === 'ANNOUNCE_ASSERTIVE') announce(effect.value, 'assertive');
      }
    };
    if (payload.redirect) {
      runEffects();
      saveScrollPosition();
      const transitionRequested = (payload.effects || [])
        .some(effect => effect?.type === 'VIEW_TRANSITION');
      return navigate(applicationUrl(payload.redirect), true, transitionRequested);
    }
    const currentRevision = Number(root.dataset.rootsRevision || 0);
    if (payload.revision && payload.revision <= currentRevision) return;
    const domChanged = payload.html !== null && payload.html !== undefined;
    const commit = () => {
      const focus = rememberFocus();
      let widgetScope = root;
      if (domChanged) {
        const template = document.createElement('template');
        template.innerHTML = payload.html;
        if (payload.scope) {
          if (Number(payload.baseRevision) !== currentRevision) {
            recoverView('revision');
            return;
          }
          const target = root.querySelector(
            `[data-roots-component="${CSS.escape(payload.scope)}"]`
          );
          const next = template.content.firstElementChild;
          if (!target || !next || template.content.childElementCount !== 1
              || next.getAttribute('data-roots-component') !== payload.scope) {
            recoverView('patch');
            return;
          }
          morphNode(target, next, acknowledged);
          widgetScope = target.isConnected ? target : root.querySelector(`[data-roots-component="${CSS.escape(payload.scope)}"]`);
        } else {
          morphChildren(root, template.content, acknowledged);
          syncPortals(root, acknowledged);
        }
      }
      for (const [control, version] of acknowledged || []) {
        if (controlEdits.get(control) === version) controlEdits.delete(control);
      }
      renderGeneration++;
      root.dataset.rootsRevision = String(payload.revision || currentRevision + 1);
      document.title = payload.title;
      if (typeof payload.head === 'string') syncManagedHeadMarkup(payload.head);
      restoreFocus(focus);
      if (domChanged) syncWidgets(widgetScope, !payload.scope);
      runEffects();
      window.dispatchEvent(new CustomEvent('roots:render', {
        detail: { revision: payload.revision, scope: payload.scope ?? null, domChanged }
      }));
    };
    const transitionRequested = domChanged && (payload.effects || [])
      .some(effect => effect?.type === 'VIEW_TRANSITION');
    return commitViewTransition(commit, transitionRequested);
  };

  const queuePatch = (payload, acknowledged) => {
    const next = renderQueue.then(() => applyPatch(payload, acknowledged));
    renderQueue = next.catch(() => {});
    return next;
  };

  const publishConnectionState = (state, expectedView = null) => {
    const root = currentRoot();
    if (expectedView !== null && root?.dataset.rootsView !== expectedView) return;
    if (!root || root.dataset.rootsConnection === state) return;
    root.dataset.rootsConnection = state;
    window.dispatchEvent(new CustomEvent('roots:connection', {
      detail: { state }
    }));
  };

  const connectStream = () => {
    stream?.close();
    const root = currentRoot();
    if (!root || !window.EventSource) return;
    const view = root.dataset.rootsView;
    publishConnectionState('connecting', view);
    const query = new URLSearchParams({
      view,
      csrf: root.dataset.rootsCsrf,
      protocol: root.dataset.rootsProtocol
    });
    const connection = new EventSource(`${applicationUrl('/_roots/stream')}?${query}`);
    stream = connection;
    const ownsCurrentView = () => stream === connection
      && currentRoot()?.dataset.rootsView === view;
    connection.addEventListener('open', () => {
      if (ownsCurrentView()) publishConnectionState('connected', view);
    });
    connection.addEventListener('error', () => {
      if (ownsCurrentView()) publishConnectionState('reconnecting', view);
    });
    connection.addEventListener('patch', async (event) => {
      if (!ownsCurrentView()) {
        publishStale('patch', view);
        return;
      }
      try { await queuePatch(JSON.parse(event.data)); }
      catch (error) { console.error('Invalid Roots live patch', error); }
    });
    connection.addEventListener('reload', () => {
      if (ownsCurrentView()) recoverView('expiry');
    });
  };

  const developmentOverlay = () => {
    let overlay = document.getElementById('roots-development-error');
    if (overlay) return overlay;
    overlay = document.createElement('aside');
    overlay.id = 'roots-development-error';
    overlay.className = 'roots-development-error';
    overlay.setAttribute('role', 'alert');
    document.body.append(overlay);
    return overlay;
  };

  const inspectorLine = (label, value) => {
    const line = document.createElement('div');
    line.className = 'roots-inspector__line';
    const name = document.createElement('strong');
    name.textContent = `${label}: `;
    const content = document.createElement('span');
    content.textContent = value;
    line.append(name, content);
    return line;
  };

  const inspectorSection = (title, values, describe) => {
    const section = document.createElement('section');
    const heading = document.createElement('h3');
    heading.textContent = `${title} (${values.length})`;
    section.append(heading);
    if (!values.length) {
      const empty = document.createElement('p');
      empty.textContent = 'None';
      section.append(empty);
      return section;
    }
    const list = document.createElement('ul');
    for (const value of values) {
      const item = document.createElement('li');
      item.textContent = describe(value);
      list.append(item);
    }
    section.append(list);
    return section;
  };

  const actionLocations = (wire) => {
    const locations = [];
    for (const element of document.querySelectorAll('*')) {
      for (const attribute of element.attributes) {
        if (attribute.name.startsWith('data-roots-on-') && attribute.value === wire) {
          locations.push(`${attribute.name.substring('data-roots-on-'.length)} <${element.tagName.toLowerCase()}>`);
        }
      }
    }
    return locations;
  };

  const renderInspection = (payload) => {
    if (!inspectorPanel) return;
    inspectorPanel.replaceChildren();
    const heading = document.createElement('h2');
    heading.textContent = 'Roots inspector';
    const hint = document.createElement('p');
    hint.className = 'roots-inspector__hint';
    hint.textContent = 'Ctrl+Shift+. toggles this panel';
    inspectorPanel.append(
      heading,
      hint,
      inspectorLine('Route', payload.path),
      inspectorLine('Revision', String(payload.revision)),
      inspectorLine('Page', payload.page),
      inspectorSection('Layouts', payload.layouts, value => value),
      inspectorSection('Components', payload.components, component =>
        `${component.name} — ${component.type} @${component.identity}`
        + (component.occurrences > 1 ? ` ×${component.occurrences}` : '')),
      inspectorSection('Actions', payload.actions, action => {
        const locations = actionLocations(action.wire);
        const policies = action.policies.length ? ` [${action.policies.join(', ')}]` : '';
        const bound = locations.length ? ` — ${locations.join(', ')}` : '';
        return `${action.target}#${action.method}${policies}${bound}`;
      })
    );
  };

  const refreshInspector = async () => {
    if (!inspectorOpen || !inspectorPanel) return;
    const root = currentRoot();
    if (!root?.dataset.rootsView || !root?.dataset.rootsCsrf) return;
    const view = root.dataset.rootsView;
    const query = new URLSearchParams({
      view,
      csrf: root.dataset.rootsCsrf,
      protocol: root.dataset.rootsProtocol
    });
    try {
      const response = await fetch(`${applicationUrl('/_roots/inspect')}?${query}`, {
        credentials: 'same-origin',
        headers: { 'Accept': 'application/json' }
      });
      if (!response.ok) throw new Error(`Inspector request failed (${response.status})`);
      const payload = await response.json();
      if (currentRoot()?.dataset.rootsView === view) renderInspection(payload);
    } catch (error) {
      if (currentRoot()?.dataset.rootsView === view) {
        inspectorPanel.replaceChildren(inspectorLine('Inspector error', error.message));
      }
    }
  };

  const toggleInspector = () => {
    if (!inspectorPanel) return;
    inspectorOpen = !inspectorOpen;
    const button = document.getElementById('roots-inspector-toggle');
    if (button) button.setAttribute('aria-expanded', String(inspectorOpen));
    if (inspectorPanel) inspectorPanel.hidden = !inspectorOpen;
    if (inspectorOpen) refreshInspector();
  };

  const mountInspector = () => {
    const root = currentRoot();
    if (root?.dataset.rootsDevelopmentGeneration === undefined
        || document.getElementById('roots-inspector-toggle')) return;
    const button = document.createElement('button');
    button.id = 'roots-inspector-toggle';
    button.className = 'roots-inspector-toggle';
    button.type = 'button';
    button.textContent = 'Roots';
    button.title = 'Open the Roots component and action inspector (Ctrl+Shift+.)';
    button.setAttribute('aria-controls', 'roots-inspector');
    button.setAttribute('aria-expanded', 'false');
    button.addEventListener('click', toggleInspector);
    inspectorPanel = document.createElement('aside');
    inspectorPanel.id = 'roots-inspector';
    inspectorPanel.className = 'roots-inspector';
    inspectorPanel.hidden = true;
    document.body.append(button, inspectorPanel);
  };

  const connectDevelopmentStream = () => {
    developmentStream?.close();
    const root = currentRoot();
    const version = root?.dataset.rootsDevelopmentGeneration;
    if (version === undefined) return;
    mountInspector();
    if (!window.EventSource) return;
    const connection = new EventSource(
      `${applicationUrl('/_roots/development')}?since=${encodeURIComponent(version)}`
    );
    developmentStream = connection;
    const ownsCurrentGeneration = () => developmentStream === connection && root.isConnected;
    connection.addEventListener('compile-error', (event) => {
      if (!ownsCurrentGeneration()) return;
      try {
        const payload = JSON.parse(event.data);
        root.dataset.rootsDevelopmentGeneration = String(payload.version);
        const overlay = developmentOverlay();
        overlay.replaceChildren();
        const heading = document.createElement('strong');
        heading.textContent = 'Roots could not compile the latest changes';
        const diagnostic = document.createElement('div');
        diagnostic.className = 'roots-development-error__diagnostic';
        diagnostic.textContent = payload.message || 'The build failed without a diagnostic.';
        overlay.append(heading, diagnostic);
      } catch (error) {
        console.error('Invalid Roots development event', error);
      }
    });
    connection.addEventListener('reload', () => {
      if (ownsCurrentGeneration()) recoverView('development');
    });
  };

  const disposeView = (root) => {
    if (!root?.dataset.rootsView || !root?.dataset.rootsCsrf) return;
    const values = new URLSearchParams({
      _view: root.dataset.rootsView,
      _csrf: root.dataset.rootsCsrf,
      _protocol: root.dataset.rootsProtocol
    });
    const body = values.toString();
    if (navigator.sendBeacon) {
      navigator.sendBeacon(applicationUrl('/_roots/dispose'), new Blob([body], {
        type: 'application/x-www-form-urlencoded;charset=UTF-8'
      }));
      return;
    }
    fetch(applicationUrl('/_roots/dispose'), {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body,
      keepalive: true
    }).catch(() => {});
  };

  const syncStylesheets = (nextDocument, nextUrl) => {
    const selector = 'link[data-roots-style], link[data-roots-font-preload]';
    const current = Array.from(document.head.querySelectorAll(selector));
    const desired = Array.from(nextDocument.head.querySelectorAll(selector));
    const desiredHrefs = new Set(desired.map(link => new URL(link.getAttribute('href'), nextUrl).href));
    for (const link of current) {
      if (!desiredHrefs.has(link.href)) link.remove();
    }
    const currentHrefs = new Set(
      Array.from(document.head.querySelectorAll(selector)).map(link => link.href)
    );
    for (const link of desired) {
      const href = new URL(link.getAttribute('href'), nextUrl).href;
      if (!currentHrefs.has(href)) {
        const clone = link.cloneNode(true);
        clone.href = href;
        document.head.append(clone);
      }
    }
  };

  const replaceManagedHead = (desired) => {
    for (const element of document.head.querySelectorAll('[data-roots-head]')) {
      element.remove();
    }
    for (const element of desired) {
      document.head.append(element.cloneNode(true));
    }
  };

  const syncManagedHead = (nextDocument) => {
    replaceManagedHead(nextDocument.head.querySelectorAll('[data-roots-head]'));
  };

  const syncManagedHeadMarkup = (markup) => {
    const template = document.createElement('template');
    template.innerHTML = markup;
    replaceManagedHead(template.content.querySelectorAll('[data-roots-head]'));
  };

  const eventDetails = (event) => ({
    key: typeof event.key === 'string' ? event.key : null,
    code: typeof event.code === 'string' ? event.code : null,
    altKey: event.altKey === true,
    controlKey: event.ctrlKey === true,
    metaKey: event.metaKey === true,
    shiftKey: event.shiftKey === true,
    button: Number.isInteger(event.button) ? event.button : null,
    clientX: Number.isInteger(event.clientX) ? event.clientX : null,
    clientY: Number.isInteger(event.clientY) ? event.clientY : null
  });

  const addEventDetails = (values, details) => {
    if (details.key !== null) values.set('_event_key', details.key);
    if (details.code !== null) values.set('_event_code', details.code);
    if (details.altKey) values.set('_event_alt', 'true');
    if (details.controlKey) values.set('_event_control', 'true');
    if (details.metaKey) values.set('_event_meta', 'true');
    if (details.shiftKey) values.set('_event_shift', 'true');
    if (details.button !== null) values.set('_event_button', String(details.button));
    if (details.clientX !== null) values.set('_event_client_x', String(details.clientX));
    if (details.clientY !== null) values.set('_event_client_y', String(details.clientY));
  };

  const reportActionBlocked = (view, action) => {
    window.dispatchEvent(new CustomEvent('roots:action-blocked', { detail: { view, action } }));
  };

  const showRecovery = (text) => {
    let notice = document.getElementById('roots-action-recovery');
    if (!notice) {
      notice = document.createElement('aside');
      notice.id = 'roots-action-recovery';
      notice.setAttribute('role', 'alert');
      const message = document.createElement('p');
      const reload = document.createElement('button');
      reload.type = 'button';
      reload.textContent = 'Reload page';
      reload.addEventListener('click', () => window.location.reload());
      notice.append(message, reload);
      document.body.append(notice);
    }
    notice.querySelector('p').textContent = text;
  };

  // Never discard newer typing because an old view expired or a rollout changed the protocol.
  // Values remain only in this document; application-owned durable drafts recover confirmed saves.
  const recoverView = (reason) => {
    const root = currentRoot();
    if (root && actionState(root).blocked) return;
    const hasEdits = [...document.querySelectorAll('input, textarea, select')]
      .some(control => controlEdits.has(control)) || Array.from(widgets.values()).some(widgetEdited);
    if (!root || (!hasEdits && actionState(root).queued === 0 && delayedInputs.size === 0)) {
      window.location.reload();
      return;
    }
    actionState(root).blocked = true;
    root.dataset.rootsActionState = 'expired';
    stream?.close();
    stream = null;
    developmentStream?.close();
    developmentStream = null;
    discardInputs();
    showRecovery('This page needs a fresh connection. Your current edits are still here, and further actions are paused. '
      + 'Keep a copy of unsaved edits before reloading. Check saved drafts and operation status before repeating a change.');
    window.dispatchEvent(new CustomEvent('roots:view-recovery', {
      detail: { view: root.dataset.rootsView, reason }
    }));
  };

  const uncertainAction = (root, view, action, error) => {
    actionState(root).blocked = true;
    root.dataset.rootsActionState = 'uncertain';
    stream?.close();
    stream = null;
    discardInputs();
    showRecovery('We could not confirm the last action. Further actions on this page are paused. '
      + 'Keep a copy of unsaved edits, then reload and check whether the operation completed before trying it again.');
    window.dispatchEvent(new CustomEvent('roots:action-uncertain', {
      detail: { view, action, reason: error.name === 'RootsActionTimeoutError' ? 'timeout'
        : error.name === 'RootsActionOutcomeError' ? 'server' : 'transport', error }
    }));
  };

  const fetchAction = async (options, timeout) => {
    const controller = new AbortController();
    let timer;
    try {
      return await Promise.race([
        fetch(applicationUrl('/_roots/action'), { ...options, signal: controller.signal }).then(async response => ({
          response, payload: response.status === 409 ? null : await response.json()
        })),
        new Promise((resolve, reject) => {
          timer = setTimeout(() => {
            const error = new Error('The action response deadline elapsed; the server operation may still complete');
            error.name = 'RootsActionTimeoutError';
            reject(error);
            controller.abort();
          }, timeout);
        })
      ]);
    } finally {
      clearTimeout(timer);
    }
  };

  const invoke = async (element, eventType, action, details, root, view, captured, timeout) => {
    let stalePublished = false;
    const staleAction = () => {
      if (!stalePublished) {
        stalePublished = true;
        publishStale('action', view);
      }
    };
    const actionIsCurrent = () => currentRoot()?.dataset.rootsView === view;
    if (!root || !view) return;
    if (!actionIsCurrent()) {
      staleAction();
      return;
    }
    if (actionState(root).blocked) {
      reportActionBlocked(view, action);
      return;
    }
    const scope = validationScope(element);
    if (scope) clearValidation(scope);
    const values = captured.values;
    values.set('_view', root.dataset.rootsView);
    values.set('_csrf', root.dataset.rootsCsrf);
    values.set('_protocol', root.dataset.rootsProtocol);
    values.set('_action', action);
    values.set('_event', eventType);
    addEventDetails(values, details);
    const multipart = Array.from(values.values())
      .some(value => value instanceof File && (value.name || value.size));
    const headers = {
      'Accept': 'application/json',
      'X-Roots-Request': 'action'
    };
    const body = multipart
      ? values
      : new URLSearchParams(Array.from(values.entries())
          .filter(([, value]) => !(value instanceof File)));
    if (!multipart) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
    const rollbackOptimistic = applyOptimistic(element);
    const endPending = beginPending(element, root);
    let committed = false;
    let responseReceived = false;
    try {
      const { response, payload } = await fetchAction({
        method: 'POST',
        credentials: 'same-origin',
        headers,
        body
      }, timeout);
      responseReceived = true;
      if (!actionIsCurrent()) {
        committed = true;
        staleAction();
        return;
      }
      if (response.headers?.get('X-Roots-Action-Outcome') === 'uncertain' || response.status >= 500) {
        const error = new Error('The server could not confirm the action outcome; check business status before retrying');
        error.name = 'RootsActionOutcomeError';
        throw error;
      }
      if (response.status === 409) {
        committed = true;
        recoverView('expiry');
        return;
      }
      if (!actionIsCurrent()) {
        committed = true;
        staleAction();
        return;
      }
      if (response.status === 422 && validValidationPayload(payload)) {
        rollbackOptimistic();
        applyValidation(element, payload);
        window.dispatchEvent(new CustomEvent('roots:validation', { detail: payload }));
        return;
      }
      if (!response.ok) throw new Error(payload.error || `Roots action failed (${response.status})`);
      await queuePatch(payload, captured.edits);
      committed = true;
    } catch (error) {
      if (!actionIsCurrent()) {
        committed = true;
        staleAction();
        return;
      }
      if (!committed) rollbackOptimistic();
      if (!responseReceived || error.name === 'RootsActionOutcomeError') uncertainAction(root, view, action, error);
      console.error(error);
      window.dispatchEvent(new CustomEvent('roots:error', { detail: error }));
    } finally {
      endPending();
    }
  };

  const rejectBusyAction = (task) => {
    const error = new Error('Roots action queue is full; wait for pending work before trying again');
    error.name = 'RootsQueueFullError';
    window.dispatchEvent(new CustomEvent('roots:backpressure', {
      detail: { view: task.view, action: task.action, limit: maxQueuedActions }
    }));
    window.dispatchEvent(new CustomEvent('roots:error', { detail: error }));
  };

  const queueAction = (task) => {
    const state = actionState(task.root);
    if (state.blocked) {
      reportActionBlocked(task.view, task.action);
      return;
    }
    if (state.queued + delayedInputs.size >= maxQueuedActions) {
      rejectBusyAction(task);
      return;
    }
    state.queued++;
    state.queue = state.queue.then(() => invoke(
      task.element, task.eventType, task.action, task.details, task.root, task.view, task.captured, task.timeout
    )).catch(error => {
      console.error(error);
      window.dispatchEvent(new CustomEvent('roots:error', { detail: error }));
    }).finally(() => { state.queued--; });
  };

  const flushInputs = () => {
    const pending = Array.from(delayedInputs.values());
    delayedInputs.clear();
    for (const task of pending) {
      clearTimeout(task.timer);
      queueAction(task);
    }
  };

  const discardInputs = () => {
    for (const task of delayedInputs.values()) {
      clearTimeout(task.timer);
      publishStale('action', task.view);
    }
    delayedInputs.clear();
  };

  const enqueue = (element, eventType, action, details) => {
    const root = currentRoot();
    const view = root?.dataset.rootsView;
    if (!view) return;
    const state = actionState(root);
    if (state.blocked) {
      reportActionBlocked(view, action);
      return;
    }
    const captured = captureForm(element);
    const requestedTimeout = Number(element.dataset.rootsActionTimeout || 30000);
    const timeout = Number.isInteger(requestedTimeout) && requestedTimeout >= 100 && requestedTimeout <= 300000
      ? requestedTimeout : 30000;
    const task = { element, eventType, action, details, root, view, captured, timeout };
    const delay = Number(element.dataset.rootsInputDebounce || 0);
    if (eventType === 'input' && Number.isInteger(delay) && delay > 0 && delay <= 60000) {
      const previous = delayedInputs.get(element);
      if (previous) {
        clearTimeout(previous.timer);
        delayedInputs.delete(element);
      }
      else if (state.queued + delayedInputs.size >= maxQueuedActions) {
        rejectBusyAction(task);
        return;
      }
      task.timer = setTimeout(() => {
        delayedInputs.delete(element);
        queueAction(task);
      }, delay);
      delayedInputs.set(element, task);
    } else {
      flushInputs();
      queueAction(task);
    }
  };

  const actionEventTypes = [
    'click', 'dblclick', 'submit', 'change', 'input', 'keydown',
    'keyup', 'focus', 'blur', 'pointerdown', 'pointerup'
  ];
  document.addEventListener('input', rememberEdit);
  document.addEventListener('change', rememberEdit);
  document.addEventListener('input', (event) => clearFieldValidation(event.target));
  document.addEventListener('change', (event) => clearFieldValidation(event.target));
  for (const eventType of actionEventTypes) {
    const capture = eventType === 'focus' || eventType === 'blur';
    document.addEventListener(eventType, (event) => {
      if (suppressActionEvents) return;
      const origin = event.target instanceof Element ? event.target : null;
      const element = origin?.closest(`[data-roots-on-${eventType}]`);
      if (!element) return;
      if (eventType === 'click' && element.matches('dialog[data-roots-modal]')) {
        if (origin !== element || element.dataset.rootsModalBackdrop !== 'true') return;
      }
      if (eventType === 'click' || eventType === 'submit') event.preventDefault();
      const action = element.getAttribute(`data-roots-on-${eventType}`);
      enqueue(element, eventType, action, eventDetails(event));
    }, capture);
  }
  document.addEventListener('cancel', (event) => {
    const dialog = event.target instanceof HTMLDialogElement
      ? event.target.closest('dialog[data-roots-modal]')
      : null;
    if (!dialog) return;
    event.preventDefault();
    const action = dialog.dataset.rootsModalDismiss;
    if (!action) return;
    enqueue(dialog, 'keydown', action, {
      ...eventDetails(event), key: 'Escape', code: 'Escape'
    });
  }, true);
  document.addEventListener('keydown', trapModalTab, true);

  const navigate = async (
    url, push, transitionRequested = false, savedPosition = null
  ) => {
    url = new URL(url, window.location.href).href;
    const sequence = ++navigationSequence;
    let nextRoot = null;
    const discard = () => {
      disposeView(nextRoot);
      publishStale('navigation', nextRoot?.dataset.rootsView);
    };
    try {
      const response = await fetch(url, {
        credentials: 'same-origin',
        headers: { 'X-Roots-Request': 'navigation' }
      });
      const nextDocument = new DOMParser().parseFromString(await response.text(), 'text/html');
      nextRoot = nextDocument.getElementById('roots');
      if (sequence !== navigationSequence) {
        discard();
        return;
      }
      if (!response.ok && !((response.status === 404 || response.status === 500) && nextRoot)) {
        throw new Error(`Navigation failed (${response.status})`);
      }
      if (!nextRoot) throw new Error('Response is not a Roots page');
      const previousRoot = currentRoot();
      if (nextRoot.dataset.rootsProtocol !== previousRoot?.dataset.rootsProtocol) {
        window.location.assign(url);
        return;
      }
      await commitViewTransition(() => {
        if (sequence !== navigationSequence) {
          discard();
          return;
        }
        syncStylesheets(nextDocument, url);
        syncManagedHead(nextDocument);
        discardInputs();
        document.getElementById('roots-action-recovery')?.remove();
        destroyWidgetsWithin(previousRoot);
        // A new view owns new portal widgets even when the portal ID is reused.
        for (const host of portalHosts.values()) destroyWidgetsWithin(host);
        previousRoot.replaceWith(nextRoot);
        syncPortals(nextRoot);
        syncWidgets();
        disposeView(previousRoot);
        document.title = nextDocument.title;
        if (push) history.pushState({ __rootsScroll: { x: 0, y: 0 } }, '', url);
        activeResourceUrl = url;
        restoreNavigationScroll(url, savedPosition);
        connectStream();
        connectDevelopmentStream();
        window.dispatchEvent(new CustomEvent('roots:navigate', {
          detail: { status: response.status }
        }));
      }, transitionRequested);
    } catch (error) {
      if (sequence !== navigationSequence) {
        discard();
        return;
      }
      window.location.assign(url);
    }
  };

  document.addEventListener('click', (event) => {
    const link = event.target.closest('a[data-roots-link]');
    if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const url = new URL(link.href, window.location.href);
    if (url.origin !== window.location.origin) return;
    const currentUrl = new URL(window.location.href);
    if (sameDocumentResource(url, currentUrl) && (url.hash || currentUrl.hash)) {
      saveScrollPosition();
      return;
    }
    if (url.hash.startsWith('#:~:text=')) {
      saveScrollPosition();
      return;
    }
    event.preventDefault();
    saveScrollPosition();
    navigate(url.href, true, link.hasAttribute('data-roots-view-transition'));
  });

  window.addEventListener('popstate', (event) => {
    const savedPosition = savedScrollPosition(event.state);
    if (sameDocumentResource(window.location.href, activeResourceUrl)) {
      window.requestAnimationFrame(() => restoreNavigationScroll(
        window.location.href, savedPosition
      ));
      return;
    }
    navigate(window.location.href, false, false, savedPosition);
  });
  window.addEventListener('scroll', () => {
    if (scrollFrame !== null) return;
    scrollFrame = window.requestAnimationFrame(() => {
      scrollFrame = null;
      saveScrollPosition();
    });
  }, { passive: true });
  window.addEventListener('offline', () => {
    stream?.close();
    stream = null;
    publishConnectionState('reconnecting');
  });
  window.addEventListener('online', () => connectStream());
  window.addEventListener('roots:render', () => refreshInspector());
  document.addEventListener('keydown', (event) => {
    if (event.ctrlKey && event.shiftKey && event.key === '.') {
      event.preventDefault();
      toggleInspector();
    }
  });
  window.addEventListener('beforeunload', () => {
    discardInputs();
    saveScrollPosition();
    stream?.close();
    developmentStream?.close();
    disposeView(currentRoot());
  });
  syncPortals(currentRoot());
  syncWidgets();
  window.addEventListener('pagehide', event => {
    if (event.persisted) return; // Preserve editor state while the browser freezes a back/forward-cache entry.
    for (const state of widgets.values()) stopWidget(state);
    widgets.clear();
  });
  window.addEventListener('pageshow', event => { if (event.persisted) syncWidgets(); });
  if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
  saveScrollPosition();
  if (navigator.onLine) connectStream();
  else publishConnectionState('reconnecting');
  connectDevelopmentStream();
})();
