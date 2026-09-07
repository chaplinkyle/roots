// No build step. Wrap a chart library using this same lifecycle when needed.
export function mount(host, { props, signal }) {
  const label = document.createElement('label');
  label.textContent = 'Samples shown ';
  const range = document.createElement('input');
  range.type = 'range'; range.min = '4'; range.max = '24'; range.value = '24';
  range.id = 'latency-range';
  const count = document.createElement('output');
  count.htmlFor = range.id;
  label.append(range, count);
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 720 180');
  svg.setAttribute('role', 'img');
  const path = document.createElementNS(svg.namespaceURI, 'path');
  svg.append(path);
  const summary = document.createElement('p');
  summary.className = 'latency-caption';
  host.replaceChildren(label, svg, summary);
  let current = props;
  function draw() {
    const values = current.values.split(',').map(Number).slice(-Number(range.value));
    if (values.some(value => !Number.isFinite(value) || value < 0)) throw new Error('Invalid latency sample');
    count.value = String(values.length);
    path.setAttribute('d', values.map((value, i) => `${i ? 'L' : 'M'}${12 + i * 696 / (values.length - 1)},${168 - Math.min(value, 150)}`).join(' '));
    const description = `${values.length} samples, ${Math.min(...values)} to ${Math.max(...values)} milliseconds`;
    svg.setAttribute('aria-label', description);
    summary.textContent = description;
  }
  range.addEventListener('input', draw, { signal });
  draw();
  return {
    update(next) { current = next; draw(); },
    destroy() { host.replaceChildren(); }
  };
}
