// Progressive enhancement: task links and the server-rendered editor remain usable without this module.
export function mount(host, {props, signal}) {
  host.querySelectorAll('[data-edit-id]').forEach(title => {
    const link = document.createElement('a');
    const url = new URL(location.href);
    url.search = '?edit=' + encodeURIComponent(title.dataset.editId);
    link.href = url.href;
    link.className = title.className;
    link.textContent = title.textContent;
    title.replaceWith(link);
  });
  host.dataset.ready = 'true';
  if (props.editable !== 'true') return {update() {}, destroy() {}};
  let dragged = null;
  host.addEventListener('dragstart', event => {
    const card = event.target.closest('[data-task-id]');
    if (!card || !host.contains(card)) return;
    dragged = {id: card.dataset.taskId, version: card.dataset.version};
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', card.dataset.taskId);
    card.classList.add('dragging');
  }, {signal});
  const clear = () => host.querySelectorAll('.drop-target,.dragging').forEach(el => el.classList.remove('drop-target','dragging'));
  host.addEventListener('dragover', event => {
    if (!dragged) return;
    const column = event.target.closest('[data-status]');
    if (!column || !host.contains(column)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
    host.querySelectorAll('.drop-target').forEach(el => el.classList.remove('drop-target'));
    column.classList.add('drop-target');
  }, {signal});
  host.addEventListener('drop', event => {
    if (!dragged) return;
    const column = event.target.closest('[data-status]');
    if (!column || !host.contains(column)) return;
    event.preventDefault();
    const form = document.getElementById('move-task');
    if (form) {
      for (const [name,value] of Object.entries({taskId:dragged.id,version:dragged.version,status:column.dataset.status})) {
        const input = form.elements.namedItem(name);
        input.value = value;
        input.dispatchEvent(new Event('input',{bubbles:true}));
      }
      form.requestSubmit();
    }
    dragged = null;
    clear();
  }, {signal});
  host.addEventListener('dragend', () => { dragged = null; clear(); }, {signal});
  return {update() {}, destroy: clear};
}
