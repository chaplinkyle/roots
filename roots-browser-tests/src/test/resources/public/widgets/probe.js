export async function mount(host, {props, signal, setDirty}) {
  window.widgetTrace ??= [];
  const id = host.id;
  const instance = (window.widgetInstance = (window.widgetInstance || 0) + 1);
  const trace = (event, value = null) => window.widgetTrace.push({id, instance, event, value, connected: host.isConnected});
  trace('start');
  signal.addEventListener('abort', () => trace('abort'), {once: true});
  if (props['wait-mount'] === 'true') await new Promise(resolve => { window.releaseWidgetMount = resolve; });
  const output = document.createElement('output');
  output.id = id + '-value';
  output.textContent = props.value;
  const editor = document.createElement('div');
  editor.id = id + '-editor';
  editor.contentEditable = 'true';
  editor.setAttribute('role', 'textbox');
  editor.setAttribute('aria-label', 'Widget draft');
  editor.textContent = 'draft';
  editor.addEventListener('input', () => {
    setDirty(true);
    if (id === 'widget-first') {
      const field = document.getElementById('widget-bridge');
      field.value = editor.textContent;
      field.dispatchEvent(new Event('input', {bubbles: true}));
    }
  }, {signal});
  host.replaceChildren(output, editor);
  trace('mount', props.value);
  return {
    async update(next) {
      trace('update', next.value);
      if (next.fail === '') throw new Error('Injected widget update failure');
      if (next.slow === '' && !window.widgetReleased) {
        await new Promise(resolve => { window.releaseWidgetUpdate = () => { window.widgetReleased = true; resolve(); }; });
      }
      if (!signal.aborted) output.textContent = next.value;
    },
    destroy() {
      trace('destroy');
      host.replaceChildren();
    }
  };
}
