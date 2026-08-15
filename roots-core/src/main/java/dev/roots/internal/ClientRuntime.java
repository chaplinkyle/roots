package dev.roots.internal;

final class ClientRuntime {
    private ClientRuntime() {
    }

    static final String SOURCE = """
            (() => {
              'use strict';

              const currentRoot = () => document.getElementById('roots');
              let actionQueue = Promise.resolve();
              let stream = null;

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

              const syncAttributes = (current, next) => {
                for (const attribute of Array.from(current.attributes)) {
                  if (!next.hasAttribute(attribute.name)) current.removeAttribute(attribute.name);
                }
                for (const attribute of Array.from(next.attributes)) {
                  if (current.getAttribute(attribute.name) !== attribute.value) {
                    current.setAttribute(attribute.name, attribute.value);
                  }
                }
                if (current instanceof HTMLInputElement) {
                  if (current.type === 'checkbox' || current.type === 'radio') current.checked = next.checked;
                  if (current.value !== next.value) current.value = next.value;
                } else if (current instanceof HTMLTextAreaElement && current.value !== next.value) {
                  current.value = next.value;
                }
              };

              const morphNode = (current, next) => {
                if (!sameKind(current, next)) {
                  const replacement = next.cloneNode(true);
                  current.replaceWith(replacement);
                  return replacement;
                }
                if (current.nodeType === Node.TEXT_NODE || current.nodeType === Node.COMMENT_NODE) {
                  if (current.nodeValue !== next.nodeValue) current.nodeValue = next.nodeValue;
                  return current;
                }
                syncAttributes(current, next);
                morphChildren(current, next);
                if (current instanceof HTMLSelectElement && current.value !== next.value) current.value = next.value;
                return current;
              };

              const morphChildren = (current, next) => {
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
                    const actual = morphNode(match, desired);
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
                  if (!used.has(node) && node.parentNode === current) node.remove();
                }
              };

              const formValues = (element) => {
                const form = element.matches('form') ? element : element.closest('form');
                const values = form ? new URLSearchParams(new FormData(form)) : new URLSearchParams();
                if (element.name && !values.has(element.name)) values.append(element.name, element.value ?? '');
                return values;
              };

              const rememberFocus = () => {
                const active = document.activeElement;
                if (!active || !active.name) return null;
                return { name: active.name, start: active.selectionStart, end: active.selectionEnd };
              };

              const restoreFocus = (focus) => {
                if (!focus) return;
                const next = currentRoot()?.querySelector(`[name="${CSS.escape(focus.name)}"]`);
                if (!next) return;
                next.focus({ preventScroll: true });
                if (typeof next.setSelectionRange === 'function' && focus.start !== null) {
                  next.setSelectionRange(focus.start, focus.end);
                }
              };

              const applyPatch = (payload) => {
                const runEffects = () => {
                  for (const effect of payload.effects || []) {
                    const target = effect.target
                      ? currentRoot()?.querySelector(`[data-roots-ref="${CSS.escape(effect.target)}"]`)
                      : null;
                    if (effect.type === 'FOCUS') target?.focus({ preventScroll: true });
                    if (effect.type === 'SCROLL_INTO_VIEW') target?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                    if (effect.type === 'COPY_TO_CLIPBOARD' && navigator.clipboard) navigator.clipboard.writeText(effect.value ?? '');
                  }
                };
                if (payload.redirect) {
                  runEffects();
                  window.location.assign(payload.redirect);
                  return;
                }
                const root = currentRoot();
                if (!root) return;
                const currentRevision = Number(root.dataset.rootsRevision || 0);
                if (payload.revision && payload.revision <= currentRevision) return;
                const template = document.createElement('template');
                template.innerHTML = payload.html;
                const focus = rememberFocus();
                morphChildren(root, template.content);
                root.dataset.rootsRevision = String(payload.revision || currentRevision + 1);
                document.title = payload.title;
                const description = document.querySelector('meta[name="description"]');
                if (description) description.content = payload.description ?? '';
                restoreFocus(focus);
                runEffects();
                window.dispatchEvent(new CustomEvent('roots:render', { detail: { revision: payload.revision } }));
              };

              const connectStream = () => {
                stream?.close();
                const root = currentRoot();
                if (!root || !window.EventSource) return;
                const query = new URLSearchParams({ view: root.dataset.rootsView, csrf: root.dataset.rootsCsrf });
                stream = new EventSource(`/_roots/stream?${query}`);
                stream.addEventListener('patch', (event) => {
                  try { applyPatch(JSON.parse(event.data)); }
                  catch (error) { console.error('Invalid Roots live patch', error); }
                });
              };

              const invoke = async (element, eventType, action) => {
                const root = currentRoot();
                if (!root) return;
                const values = formValues(element);
                values.set('_view', root.dataset.rootsView);
                values.set('_csrf', root.dataset.rootsCsrf);
                values.set('_action', action);
                values.set('_event', eventType);
                root.setAttribute('aria-busy', 'true');
                document.documentElement.dataset.rootsPending = 'true';
                try {
                  const response = await fetch('/_roots/action', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                      'Accept': 'application/json',
                      'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                      'X-Roots-Request': 'action'
                    },
                    body: values
                  });
                  if (response.status === 409) {
                    window.location.reload();
                    return;
                  }
                  const payload = await response.json();
                  if (!response.ok) throw new Error(payload.error || `Roots action failed (${response.status})`);
                  applyPatch(payload);
                } catch (error) {
                  console.error(error);
                  window.dispatchEvent(new CustomEvent('roots:error', { detail: error }));
                } finally {
                  currentRoot()?.removeAttribute('aria-busy');
                  delete document.documentElement.dataset.rootsPending;
                }
              };

              const enqueue = (element, eventType, action) => {
                actionQueue = actionQueue.then(() => invoke(element, eventType, action));
              };

              document.addEventListener('click', (event) => {
                const element = event.target.closest('[data-roots-on-click]');
                if (!element) return;
                event.preventDefault();
                enqueue(element, 'click', element.dataset.rootsOnClick);
              });

              document.addEventListener('submit', (event) => {
                const element = event.target.closest('[data-roots-on-submit]');
                if (!element) return;
                event.preventDefault();
                enqueue(element, 'submit', element.dataset.rootsOnSubmit);
              });

              document.addEventListener('change', (event) => {
                const element = event.target.closest('[data-roots-on-change]');
                if (!element) return;
                enqueue(element, 'change', element.dataset.rootsOnChange);
              });

              const navigate = async (url, push) => {
                try {
                  const response = await fetch(url, {
                    credentials: 'same-origin',
                    headers: { 'X-Roots-Request': 'navigation' }
                  });
                  if (!response.ok) throw new Error(`Navigation failed (${response.status})`);
                  const nextDocument = new DOMParser().parseFromString(await response.text(), 'text/html');
                  const nextRoot = nextDocument.getElementById('roots');
                  if (!nextRoot) throw new Error('Response is not a Roots page');
                  currentRoot().replaceWith(nextRoot);
                  document.title = nextDocument.title;
                  const currentDescription = document.querySelector('meta[name="description"]');
                  const nextDescription = nextDocument.querySelector('meta[name="description"]');
                  if (currentDescription) currentDescription.content = nextDescription?.content ?? '';
                  if (push) history.pushState({}, '', url);
                  window.scrollTo({ top: 0, behavior: 'instant' });
                  connectStream();
                  window.dispatchEvent(new CustomEvent('roots:navigate'));
                } catch (error) {
                  window.location.assign(url);
                }
              };

              document.addEventListener('click', (event) => {
                const link = event.target.closest('a[data-roots-link]');
                if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                const url = new URL(link.href, window.location.href);
                if (url.origin !== window.location.origin) return;
                event.preventDefault();
                navigate(url.href, true);
              });

              window.addEventListener('popstate', () => navigate(window.location.href, false));
              window.addEventListener('beforeunload', () => stream?.close());
              connectStream();
            })();
            """;
}
