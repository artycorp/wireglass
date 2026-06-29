'use strict';

const state = {
    packets: [],          // all received packets
    seen: new Set(),      // packet ids already added (dedupe history vs. live SSE)
    selectedId: null,
    currentRunId: null,
    filter: {
        text: '',
        searchBodies: false,
        type: '',
        method: '',
        statusClasses: new Set(),  // numeric class buckets: 2,3,4,5
        failedOnly: false,
        minLatency: 0,
    },
    es: null,             // EventSource
    maxElapsed: 1,        // running max elapsedMs, drives the waterfall scale
    sort: { key: null, dir: 'asc' },  // null key = insertion order
};

const el = {
    form: document.getElementById('run-form'),
    runToggle: document.getElementById('run-toggle'),
    runPanel: document.getElementById('run-panel'),
    url: document.getElementById('f-url'),
    method: document.getElementById('f-method'),
    body: document.getElementById('f-body'),
    bodyField: document.getElementById('f-body-field'),
    threads: document.getElementById('f-threads'),
    iterations: document.getElementById('f-iterations'),
    contentType: document.getElementById('f-contentType'),
    demo: document.getElementById('demo-btn'),
    stop: document.getElementById('stop-btn'),
    clear: document.getElementById('clear-btn'),
    status: document.getElementById('run-status'),
    tbody: document.getElementById('packet-body'),
    count: document.getElementById('packet-count'),
    filterText: document.getElementById('filter-text'),
    filterBodies: document.getElementById('filter-bodies'),
    filterMethod: document.getElementById('filter-method'),
    filterLatency: document.getElementById('filter-latency'),
    chipFailed: document.getElementById('chip-failed'),
    activeFilters: document.getElementById('active-filters'),
    activeTags: document.getElementById('active-tags'),
    resetFilters: document.getElementById('reset-filters'),
    detail: document.getElementById('detail-content'),
    detailPane: document.getElementById('detail-pane'),
    detailClose: document.getElementById('detail-close'),
    rowTpl: document.getElementById('row-template'),
};

function ensureStream() {
    if (state.es) return;
    state.es = new EventSource('/api/traffic/stream');
    state.es.onopen = () => console.log('[SSE] connected');
    state.es.addEventListener('packet', (ev) => {
        addPacket(JSON.parse(ev.data));
    });
    state.es.onerror = () => {
        // EventSource auto-reconnects; nothing to do here
    };
}

function addPacket(packet) {
    if (packet && packet.id != null && state.seen.has(packet.id)) {
        return; // already shown (history loaded after a live SSE delivery, or vice versa)
    }
    if (packet && packet.id != null) {
        state.seen.add(packet.id);
    }
    state.packets.push(packet);
    if (packet && packet.elapsedMs > state.maxElapsed) {
        state.maxElapsed = packet.elapsedMs;
        rescaleWaterfalls();
    }
    if (state.sort.key) {
        scheduleRebuild();  // sorted view: a plain append would land out of order
        return;
    }
    if (matchesFilter(packet)) {
        removeEmptyRow();
        appendRow(packet);
    }
    updateCount(el.tbody.querySelectorAll('tr.pkt').length);
}

// Coalesce bursty SSE updates into one re-render per frame while a sort is active.
let rebuildScheduled = false;
function scheduleRebuild() {
    if (rebuildScheduled) return;
    rebuildScheduled = true;
    requestAnimationFrame(() => { rebuildScheduled = false; rebuildList(); });
}

// Load packets already captured by the backend (e.g. from an external jmeter-dsl test that ran
// before the page was opened). Live packets arrive afterwards via SSE and are deduped by id.
async function loadRecent() {
    try {
        const r = await fetch('/api/packets?limit=500');
        if (!r.ok) return;
        const recent = await r.json();
        for (const p of recent) {
            addPacket(p);
        }
        rebuildList();  // normalize numbering/count + show empty-state if restored filters hide all
    } catch (e) {
        // ignore: live SSE will still carry new packets
    }
}

// Facets combine with AND across groups; within the status group selected classes are OR'd.
function matchesFilter(p) {
    const f = state.filter;
    if (f.type && p.type !== f.type) return false;
    if (f.method && p.method !== f.method) return false;
    if (f.failedOnly && p.success) return false;
    if (f.statusClasses.size > 0) {
        const cls = p.status ? Math.floor(p.status / 100) : 0;
        if (!f.statusClasses.has(cls)) return false;
    }
    if (f.minLatency > 0 && (p.elapsedMs || 0) < f.minLatency) return false;
    if (f.text) {
        let hay = (p.url || '') + ' ' + (p.label || '') + ' ' + (p.method || '');
        if (f.searchBodies) hay += ' ' + (p.requestBody || '') + ' ' + (p.responseBody || '');
        if (!hay.toLowerCase().includes(f.text.toLowerCase())) return false;
    }
    return true;
}

function appendRow(packet) {
    const idx = el.tbody.querySelectorAll('tr.pkt').length + 1;
    const row = el.rowTpl.content.firstElementChild.cloneNode(true);
    row.dataset.id = packet.id;
    row.querySelector('.c-idx').textContent = idx;
    row.querySelector('.c-time').textContent = formatTime(packet.timestamp);
    const typeCell = row.querySelector('.c-type');
    typeCell.textContent = packet.type;
    typeCell.classList.add(packet.type.toLowerCase());
    row.querySelector('.c-method').textContent = packet.method;
    row.querySelector('.c-url').textContent = packet.url || packet.label;
    const statusCell = row.querySelector('.c-status');
    statusCell.textContent = packet.status || (packet.success ? 'OK' : 'ERR');
    const cls = statusClass(packet.status);
    if (cls) statusCell.classList.add(cls);
    const latencyCell = row.querySelector('.c-latency');
    latencyCell.textContent = '';
    latencyCell.appendChild(buildWaterfall(packet));
    const size = packet.responseBody ? packet.responseBody.length : 0;
    row.querySelector('.c-size').textContent = (size / 1024).toFixed(2);
    if (!packet.success) row.classList.add('err');
    row.addEventListener('click', () => selectPacket(packet.id, row));
    el.tbody.appendChild(row);
}

// HTTP status -> semantic class (s2/s3/s4/s5); empty for non-HTTP/unknown.
function statusClass(status) {
    if (!status || status < 100) return '';
    return 's' + Math.floor(status / 100);
}

// Inline timing waterfall: connect | wait (to first byte) | processing.
// Each segment is sized as a fraction of the running max elapsedMs so rows compare visually.
function buildWaterfall(packet) {
    const connect = Math.max(0, packet.connectMs || 0);
    const wait = Math.max(0, (packet.latencyMs || 0) - connect);
    const proc = Math.max(0, (packet.elapsedMs || 0) - (packet.latencyMs || 0));
    const wf = document.createElement('div');
    wf.className = 'wf';
    const bar = document.createElement('div');
    bar.className = 'wf-bar';
    bar.dataset.connect = connect;
    bar.dataset.wait = wait;
    bar.dataset.proc = proc;
    bar.appendChild(seg('connect'));
    bar.appendChild(seg('wait'));
    bar.appendChild(seg('proc'));
    const ms = document.createElement('span');
    ms.className = 'wf-ms';
    ms.textContent = (packet.elapsedMs || 0) + ' ms';
    wf.appendChild(bar);
    wf.appendChild(ms);
    scaleBar(bar);
    return wf;
}

function seg(kind) {
    const s = document.createElement('span');
    s.className = 'wf-seg ' + kind;
    return s;
}

function scaleBar(bar) {
    const scale = Math.max(state.maxElapsed, 1);
    const w = (ms) => (Math.min(100, (Number(ms) / scale) * 100)) + '%';
    bar.children[0].style.width = w(bar.dataset.connect);
    bar.children[1].style.width = w(bar.dataset.wait);
    bar.children[2].style.width = w(bar.dataset.proc);
}

function rescaleWaterfalls() {
    document.querySelectorAll('.wf-bar').forEach(scaleBar);
}

function formatTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return d.toLocaleTimeString(undefined, { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

function selectPacket(id, row) {
    state.selectedId = id;
    document.querySelectorAll('tr.pkt.selected').forEach(r => r.classList.remove('selected'));
    if (row) row.classList.add('selected');
    renderDetail(state.packets.find(p => p.id === id));
    el.detailPane.classList.add('open');
}

function closeDetail() {
    el.detailPane.classList.remove('open');
}

function renderDetail(packet) {
    if (!packet) { el.detail.innerHTML = '<div class="empty">Select a packet to inspect request &amp; response bodies.</div>'; return; }
    const statusBadge = packet.success
        ? '<span class="badge ok">' + esc(packet.status || 'OK') + '</span>'
        : '<span class="badge err">' + esc(packet.status || 'ERR') + '</span>';
    el.detail.innerHTML =
        '<div class="detail">'
        + '<h2>' + esc(packet.method) + ' ' + esc(packet.url || packet.label) + '</h2>'
        + '<div class="meta">' + statusBadge + ' &middot; ' + packet.elapsedMs + ' ms (connect ' + packet.connectMs
          + ' / latency ' + packet.latencyMs + ') &middot; ' + esc(packet.threadName)
          + (packet.bodyTruncated ? ' &middot; <span style="color:var(--warn)">body truncated</span>' : '')
          + '</div>'
        + sectionHeaders('Request headers', packet.requestHeaders)
        + bodyBlock(reqTitle(packet), packet.requestBody, false, false)
        + sectionHeaders('Response headers', packet.responseHeaders)
        + bodyBlock(respTitle(packet), packet.responseBody, packet.bodyBinary, packet.bodyTruncated)
        + (packet.failureMessage ? '<h3>Failure</h3><pre>' + esc(packet.failureMessage) + '</pre>' : '')
        + '</div>';
}

function sectionHeaders(title, headers) {
    if (!headers || Object.keys(headers).length === 0) return '';
    let rows = '';
    for (const [k, v] of Object.entries(headers)) {
        rows += '<tr><td class="k">' + esc(k) + '</td><td>' + esc(v) + '</td></tr>';
    }
    return '<h3>' + title + '</h3><table class="kv">' + rows + '</table>';
}

function bodyBlock(title, body, binary, truncated) {
    if (body == null || body === '') return '';
    let pretty = body;
    let note = '';
    if (!binary) {
        const trimmed = body.trimStart();
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            try { pretty = JSON.stringify(JSON.parse(body), null, 2); } catch (e) { /* keep raw */ }
        }
    } else {
        note = ' (binary — hex preview)';
    }
    if (truncated) note += ' (truncated)';
    return '<h3>' + title + note + '</h3><pre>' + esc(pretty) + '</pre>';
}

// For WebSocket samples the request body is the frame the client sent (↑) and the response body is
// what it received (↓); for HTTP keep the plain labels. (Per-frame WS timelines need a direction
// field on CapturedPacket — a backend change — so this is the honest view from current data.)
function reqTitle(p) {
    return p.type === 'WEBSOCKET'
        ? '<span class="ws-dir up">↑</span>Sent frame'
        : 'Request body';
}
function respTitle(p) {
    return p.type === 'WEBSOCKET'
        ? '<span class="ws-dir down">↓</span>Received frame'
        : 'Response body';
}

function esc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function setStatus(text, kind) {
    el.status.textContent = text;
    el.status.style.color = kind === 'ok' ? 'var(--ok)' : kind === 'err' ? 'var(--err)' : kind === 'run' ? 'var(--accent)' : 'var(--muted)';
}

// ---- run form ----
el.form.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    ensureStream();
    const payload = {
        url: el.url.value.trim(),
        method: el.method.value,
        body: el.body.value || '',
        contentType: el.contentType.value.trim(),
        threads: parseInt(el.threads.value, 10),
        iterations: parseInt(el.iterations.value, 10),
    };
    await startRun(payload);
    setRunPanel(false);  // collapse so the live list takes the screen
});

el.demo.addEventListener('click', async () => {
    ensureStream();
    const r = await fetch('/api/runs/demo', { method: 'POST' });
    afterStart(await r.json());
    setRunPanel(false);
});

el.stop.addEventListener('click', async () => {
    if (!state.currentRunId) return;
    await fetch('/api/runs/' + state.currentRunId + '/stop', { method: 'POST' });
    setStatus('stopping…');
});

el.clear.addEventListener('click', async () => {
    state.packets = [];
    state.seen = new Set();
    state.selectedId = null;
    state.maxElapsed = 1;
    el.tbody.innerHTML = '';
    el.count.textContent = '0 packets';
    renderDetail(null);
    closeDetail();
    try { await fetch('/api/packets', { method: 'DELETE' }); } catch (e) { /* ignore */ }
});

// ---- detail drawer + keyboard navigation ----
el.detailClose.addEventListener('click', closeDetail);

function visibleRows() {
    return Array.from(el.tbody.querySelectorAll('tr.pkt'));
}

function moveSelection(delta) {
    const rows = visibleRows();
    if (rows.length === 0) return;
    const current = rows.findIndex(r => r.dataset.id === String(state.selectedId));
    let next = current + delta;
    if (current === -1) next = delta > 0 ? 0 : rows.length - 1;
    next = Math.max(0, Math.min(rows.length - 1, next));
    const row = rows[next];
    selectPacket(row.dataset.id, row);
    row.scrollIntoView({ block: 'nearest' });
}

document.addEventListener('keydown', (ev) => {
    const tag = (document.activeElement && document.activeElement.tagName) || '';
    const typing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';

    if (ev.key === 'Escape') {
        if (el.detailPane.classList.contains('open')) { closeDetail(); ev.preventDefault(); }
        else if (typing) document.activeElement.blur();
        return;
    }
    if (ev.key === '/' && !typing) { ev.preventDefault(); el.filterText.focus(); return; }
    if (typing) return;
    if (ev.key === 'j' || ev.key === 'ArrowDown') { ev.preventDefault(); moveSelection(1); }
    else if (ev.key === 'k' || ev.key === 'ArrowUp') { ev.preventDefault(); moveSelection(-1); }
});

async function startRun(payload) {
    const r = await fetch('/api/runs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    afterStart(await r.json());
}

function afterStart(status) {
    state.currentRunId = status.id;
    setStatus('running…', 'run');
    el.stop.disabled = false;
    pollStatus(status.id);
}

function pollStatus(id) {
    const handle = setInterval(async () => {
        const r = await fetch('/api/runs/' + id);
        if (!r.ok) return;
        const s = await r.json();
        if (s.state === 'RUNNING') {
            setStatus('running… ' + s.capturedSamples + ' samples', 'run');
            return;
        }
        clearInterval(handle);
        el.stop.disabled = true;
        if (s.state === 'FINISHED') setStatus('finished: ' + s.capturedSamples + ' samples, ' + s.errorSamples + ' errors', 'ok');
        else if (s.state === 'FAILED') setStatus('failed', 'err');
        else setStatus(s.state, 'muted');
    }, 1000);
}

// ---- run panel (collapsible) ----
function setRunPanel(open) {
    el.runPanel.classList.toggle('open', open);
    el.runToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (open) el.url.focus();
}
el.runToggle.addEventListener('click', () => setRunPanel(!el.runPanel.classList.contains('open')));

function updateBodyVisibility() {
    const m = el.method.value;
    el.bodyField.hidden = !(m === 'POST' || m === 'PUT' || m === 'PATCH');
}
el.method.addEventListener('change', updateBodyVisibility);

// ---- filters ----
function debounce(fn, ms) {
    let h;
    return (...a) => { clearTimeout(h); h = setTimeout(() => fn(...a), ms); };
}
function onFilterChange() { rebuildList(); renderActiveFilters(); saveFilters(); }

// state setters that also sync the matching control (used by clicks, tag removal, reset, restore)
function setType(v) {
    state.filter.type = v;
    document.querySelectorAll('.seg[data-type]').forEach(b => b.classList.toggle('active', b.dataset.type === v));
}
// NB: named setStatusClass (not setStatus) to avoid colliding with the run-status setter below.
function setStatusClass(c, on) {
    if (on) state.filter.statusClasses.add(c); else state.filter.statusClasses.delete(c);
    const btn = document.querySelector('.chip[data-status="' + c + '"]');
    if (btn) btn.setAttribute('aria-pressed', on ? 'true' : 'false');
}
function setFailed(on) {
    state.filter.failedOnly = on;
    el.chipFailed.setAttribute('aria-pressed', on ? 'true' : 'false');
}
function setMethod(v) { state.filter.method = v; el.filterMethod.value = v; }
function setLatency(v) { state.filter.minLatency = v; el.filterLatency.value = v || ''; }
function setText(v, bodies) {
    state.filter.text = v;
    el.filterText.value = v;
    if (bodies !== undefined) { state.filter.searchBodies = bodies; el.filterBodies.checked = bodies; }
}

document.querySelectorAll('.seg[data-type]').forEach(btn => {
    btn.addEventListener('click', () => { setType(btn.dataset.type); onFilterChange(); });
});
document.querySelectorAll('.chip[data-status]').forEach(btn => {
    btn.addEventListener('click', () => {
        const c = Number(btn.dataset.status);
        setStatusClass(c, !state.filter.statusClasses.has(c));
        onFilterChange();
    });
});
el.chipFailed.addEventListener('click', () => { setFailed(!state.filter.failedOnly); onFilterChange(); });
el.filterMethod.addEventListener('change', () => { state.filter.method = el.filterMethod.value; onFilterChange(); });
el.filterLatency.addEventListener('input', debounce(() => {
    state.filter.minLatency = Number(el.filterLatency.value) || 0;
    onFilterChange();
}, 150));
el.filterText.addEventListener('input', debounce(() => {
    state.filter.text = el.filterText.value.trim();
    onFilterChange();
}, 150));
el.filterBodies.addEventListener('change', () => { state.filter.searchBodies = el.filterBodies.checked; onFilterChange(); });
el.resetFilters.addEventListener('click', resetFilters);

function resetFilters() {
    setType('');
    Array.from(state.filter.statusClasses).forEach(c => setStatusClass(c, false));
    setFailed(false);
    setMethod('');
    setLatency(0);
    setText('', false);
    onFilterChange();
}

function renderActiveFilters() {
    const f = state.filter;
    el.activeTags.innerHTML = '';
    const tags = [];
    if (f.type) tags.push(afTag('type=' + f.type, () => { setType(''); onFilterChange(); }));
    if (f.method) tags.push(afTag('method=' + f.method, () => { setMethod(''); onFilterChange(); }));
    Array.from(f.statusClasses).sort().forEach(c =>
        tags.push(afTag(c + 'xx', () => { setStatusClass(c, false); onFilterChange(); })));
    if (f.failedOnly) tags.push(afTag('failed', () => { setFailed(false); onFilterChange(); }));
    if (f.minLatency > 0) tags.push(afTag('latency≥' + f.minLatency + 'ms', () => { setLatency(0); onFilterChange(); }));
    if (f.text) tags.push(afTag((f.searchBodies ? 'body~' : '~') + f.text, () => { setText('', false); onFilterChange(); }));
    tags.forEach(t => el.activeTags.appendChild(t));
    el.activeFilters.hidden = tags.length === 0;
}

function afTag(text, onRemove) {
    const span = document.createElement('span');
    span.className = 'af-tag';
    span.appendChild(document.createTextNode(text + ' '));
    const x = document.createElement('button');
    x.type = 'button';
    x.textContent = '✕';
    x.setAttribute('aria-label', 'remove filter ' + text);
    x.addEventListener('click', onRemove);
    span.appendChild(x);
    return span;
}

const FILTER_KEY = 'jmlv.filter';
function saveFilters() {
    try {
        const f = state.filter;
        localStorage.setItem(FILTER_KEY, JSON.stringify({
            text: f.text, searchBodies: f.searchBodies, type: f.type, method: f.method,
            statusClasses: Array.from(f.statusClasses), failedOnly: f.failedOnly, minLatency: f.minLatency,
        }));
    } catch (e) { /* storage unavailable; filters just won't persist */ }
}
function loadFilters() {
    try {
        const raw = localStorage.getItem(FILTER_KEY);
        if (!raw) return;
        const d = JSON.parse(raw);
        setType(d.type || '');
        (d.statusClasses || []).map(Number).filter(n => n >= 1 && n <= 5).forEach(c => setStatusClass(c, true));
        setFailed(!!d.failedOnly);
        setMethod(d.method || '');
        setLatency(Number(d.minLatency) || 0);
        setText(d.text || '', !!d.searchBodies);
    } catch (e) { /* ignore malformed saved state */ }
}

function rebuildList() {
    el.tbody.innerHTML = '';
    let rows = state.packets.filter(matchesFilter);
    if (state.sort.key) {
        const dir = state.sort.dir === 'desc' ? -1 : 1;
        rows = rows.slice().sort((a, b) => comparePackets(a, b, state.sort.key) * dir);
    }
    for (const p of rows) appendRow(p);
    updateCount(rows.length);
    if (rows.length === 0 && state.packets.length > 0) showEmptyRow();
}

function comparePackets(a, b, key) {
    switch (key) {
        case 'time': return (Date.parse(a.timestamp) || 0) - (Date.parse(b.timestamp) || 0);
        case 'type': return (a.type || '').localeCompare(b.type || '');
        case 'method': return (a.method || '').localeCompare(b.method || '');
        case 'url': return (a.url || a.label || '').localeCompare(b.url || b.label || '');
        case 'status': return (a.status || 0) - (b.status || 0);
        case 'latency': return (a.elapsedMs || 0) - (b.elapsedMs || 0);
        case 'size': return bodyLen(a) - bodyLen(b);
        default: return 0;
    }
}
function bodyLen(p) { return p.responseBody ? p.responseBody.length : 0; }

// ---- column sorting ----
document.querySelectorAll('th.sortable').forEach(th => {
    th.tabIndex = 0;
    th.addEventListener('click', () => toggleSort(th.dataset.sort));
    th.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSort(th.dataset.sort); }
    });
});

// 3-state cycle on a column: ascending -> descending -> off (back to insertion order).
function toggleSort(key) {
    const s = state.sort;
    if (s.key !== key) { s.key = key; s.dir = 'asc'; }
    else if (s.dir === 'asc') { s.dir = 'desc'; }
    else { s.key = null; s.dir = 'asc'; }
    updateSortIndicators();
    rebuildList();
}

function updateSortIndicators() {
    document.querySelectorAll('th.sortable').forEach(th => {
        th.setAttribute('aria-sort',
            th.dataset.sort === state.sort.key
                ? (state.sort.dir === 'desc' ? 'descending' : 'ascending')
                : 'none');
    });
}

function updateCount(shown) {
    const total = state.packets.length;
    el.count.textContent = (shown === total) ? total + ' packets' : shown + ' / ' + total + ' packets';
}

function removeEmptyRow() {
    const er = el.tbody.querySelector('.empty-row');
    if (er) er.remove();
}
function showEmptyRow() {
    const tr = document.createElement('tr');
    tr.className = 'empty-row';
    const td = document.createElement('td');
    td.colSpan = 8;
    td.append('No packets match these filters — ');
    const a = document.createElement('a');
    a.textContent = 'Reset';
    a.addEventListener('click', resetFilters);
    td.appendChild(a);
    tr.appendChild(td);
    el.tbody.appendChild(tr);
}

// ---- init ----
updateBodyVisibility();
loadFilters();
renderActiveFilters();
// open the SSE stream immediately so demo/manual runs are captured
ensureStream();
// backfill any packets captured before the page was opened (e.g. an external jmeter-dsl test)
loadRecent();
