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
    detailCollapsed: false,
    schemaRules: [],
    dashboardLinks: [],
    settingsTab: 'schema',
};

const el = {
    trafficView: document.getElementById('traffic-view'),
    settingsView: document.getElementById('settings-view'),
    bodygrid: document.getElementById('bodygrid'),
    form: document.getElementById('run-form'),
    runToggle: document.getElementById('run-toggle'),
    runPanel: document.getElementById('run-panel'),
    url: document.getElementById('f-url'),
    method: document.getElementById('f-method'),
    body: document.getElementById('f-body'),
    bodyField: document.getElementById('f-body-field'),
    bodyFormat: document.getElementById('f-body-format'),
    bodyErr: document.getElementById('f-body-err'),
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
    settingsToggle: document.getElementById('settings-toggle'),
    settingsPanel: document.getElementById('settings-panel'),
    settingsBack: document.getElementById('settings-back'),
    settingsTabs: document.querySelectorAll('.settings-tab'),
    languageOptions: document.querySelectorAll('.language-option'),
    schemaCount: document.getElementById('schema-count'),
    dashboardCount: document.getElementById('dashboard-count'),
    schemaPanel: document.getElementById('schema-panel'),
    schemaPattern: document.getElementById('schema-pattern'),
    schemaTarget: document.getElementById('schema-target'),
    schemaJson: document.getElementById('schema-json'),
    schemaSave: document.getElementById('schema-save'),
    schemaMessage: document.getElementById('schema-message'),
    schemaList: document.getElementById('schema-list'),
    dashboardPanel: document.getElementById('dashboard-panel'),
    dashName: document.getElementById('dash-name'),
    dashSystem: document.getElementById('dash-system'),
    dashSystemIcon: document.getElementById('dash-system-icon'),
    dashScope: document.getElementById('dash-scope'),
    dashPreset: document.getElementById('dash-preset'),
    dashUrl: document.getElementById('dash-url'),
    dashUrlPreview: document.getElementById('dash-url-preview'),
    dashMatch: document.getElementById('dash-match'),
    dashWindow: document.getElementById('dash-window'),
    dashSave: document.getElementById('dash-save'),
    dashMessage: document.getElementById('dash-message'),
    dashList: document.getElementById('dash-list'),
    globalLinks: document.getElementById('global-links'),
    detail: document.getElementById('detail-content'),
    detailPane: document.getElementById('detail-pane'),
    detailClose: document.getElementById('detail-close'),
    detailRestore: document.getElementById('detail-restore'),
    bodyModal: document.getElementById('body-modal'),
    bmTitle: document.getElementById('bm-title'),
    bmSize: document.getElementById('bm-size'),
    bmToggle: document.getElementById('bm-toggle'),
    bmSearch: document.getElementById('bm-search'),
    bmRegex: document.getElementById('bm-regex'),
    bmCount: document.getElementById('bm-count'),
    bmPrev: document.getElementById('bm-prev'),
    bmNext: document.getElementById('bm-next'),
    bmClose: document.getElementById('bm-close'),
    bmHost: document.getElementById('bm-host'),
    rowTpl: document.getElementById('row-template'),
};

const SCHEMA_RULES_KEY = 'listview.schemaRules';
const SETTINGS_TAB_KEY = 'listview.settingsTab';
const LANGUAGE_KEY = 'listview.language';

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
    typeCell.innerHTML = '<span class="type-pill">' + esc(packet.type) + '</span>';
    typeCell.classList.add(typeClass(packet.type));
    const methodCell = row.querySelector('.c-method');
    methodCell.innerHTML = '<span class="method-pill">' + esc(packet.method) + '</span>';
    const methodCls = methodClass(packet.method);
    if (methodCls) methodCell.classList.add(methodCls);
    row.querySelector('.c-url').textContent = packet.url || packet.label;
    const statusCell = row.querySelector('.c-status');
    statusCell.innerHTML = '<span class="status-pill">' + esc(packet.status || (packet.success ? 'OK' : 'ERR')) + '</span>';
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

// packet.method / packet.type are attacker-controllable (any client can POST a CapturedPacket to
// /api/ingest), so reduce them to a safe CSS-token allowlist before they reach a class attribute or
// classList.add — otherwise a crafted value breaks out of class="..." (XSS) or throws on a space.
function cssToken(s) {
    return String(s).toLowerCase().replace(/[^a-z0-9_-]/g, '');
}

function methodClass(method) {
    return method ? 'm-' + cssToken(method) : '';
}

function typeClass(type) {
    if (type === 'WEBSOCKET') return 'ws';
    return type ? cssToken(type) : '';
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
    restoreDetail();
    state.selectedId = id;
    document.querySelectorAll('tr.pkt.selected').forEach(r => r.classList.remove('selected'));
    if (row) row.classList.add('selected');
    renderDetail(state.packets.find(p => p.id === id));
    el.detailPane.classList.add('open');
}

function closeDetail() {
    teardownDetailScrollSpy();
    collapseDetail();
    el.detailPane.classList.remove('open');
}

function collapseDetail() {
    state.detailCollapsed = true;
    el.bodygrid.classList.add('detail-collapsed');
    el.detailRestore.hidden = false;
}

function restoreDetail() {
    state.detailCollapsed = false;
    el.bodygrid.classList.remove('detail-collapsed');
    el.detailRestore.hidden = true;
}

function renderDetail(packet) {
    detailBodies = [];
    if (!packet) { teardownDetailScrollSpy(); el.detail.innerHTML = '<div class="empty"><span>Pick a packet</span><strong>Request and response details will appear here.</strong><em>Use ↑/↓ or j/k to move through captured traffic.</em></div>'; return; }
    const validation = validatePacket(packet);
    const statusBadge = packet.success
        ? '<span class="badge ok">' + esc(packet.status || 'OK') + '</span>'
        : '<span class="badge err">' + esc(packet.status || 'ERR') + '</span>';
    const title = esc(packet.url || packet.label);
    el.detail.innerHTML =
        '<div class="detail">'
        + '<div class="detail-hero">'
        + '<div class="detail-kicker"><span class="method-chip">' + esc(packet.method) + '</span>' + statusBadge + '<span>' + esc(packet.type) + '</span></div>'
        + '<h2>' + title + '</h2>'
        + '<div class="meta">' + esc(packet.threadName) + (packet.bodyTruncated ? ' &middot; <span style="color:var(--warn)">body truncated</span>' : '') + '</div>'
        + '</div>'
        + '<div class="detail-metrics">'
        + detailMetric('elapsed', packet.elapsedMs, 'ms')
        + detailMetric('connect', packet.connectMs, 'ms')
        + detailMetric('latency', packet.latencyMs, 'ms')
        + detailMetric('response', packet.responseBody ? humanSize(packet.responseBody.length) : '0 B', '')
        + '</div>'
        + '<div class="detail-tabs" role="navigation" aria-label="Packet sections">'
        + '<button type="button" class="detail-tab active" data-jump="overview">Overview</button>'
        + '<button type="button" class="detail-tab" data-jump="headers">Headers</button>'
        + '<button type="button" class="detail-tab" data-jump="bodies">Bodies</button>'
        + '<button type="button" class="detail-tab" data-jump="raw">Raw</button>'
        + '</div>'
        + '<section class="detail-section" id="detail-overview"><h3>Overview</h3>'
        + '<div class="overview-grid">'
        + overviewPill('Method', 'c-method ' + methodClass(packet.method), 'method-pill', packet.method)
        + overviewPill('Status', 'c-status ' + statusClass(packet.status), 'status-pill', packet.status || (packet.success ? 'OK' : 'ERR'))
        + overviewPill('Type', 'c-type ' + typeClass(packet.type), 'type-pill', packet.type)
        + overviewPill('Thread', 'thread-value', '', packet.threadName || '-')
        + '</div></section>'
        + validationSection(validation)
        + dashboardSectionPlaceholder()
        + '<section class="detail-section" id="detail-headers">' + sectionHeaders('Request headers', packet.requestHeaders, 'outgoing')
        + sectionHeaders('Response headers', packet.responseHeaders, 'incoming') + '</section>'
        + '<section class="detail-section" id="detail-bodies">' + bodyBlock(reqTitle(packet), packet.requestBody, false, false, 'request', validation.paths.request)
        + bodyBlock(respTitle(packet), packet.responseBody, packet.bodyBinary, packet.bodyTruncated, 'response', validation.paths.response) + '</section>'
        + (packet.failureMessage ? '<section class="detail-section" id="detail-raw"><h3>Failure</h3><pre>' + esc(packet.failureMessage) + '</pre></section>' : '<section class="detail-section" id="detail-raw"><h3>Raw</h3><pre>' + esc(JSON.stringify(packet, null, 2)) + '</pre></section>')
        + '</div>';
    mountDetailBodies();
    mountDashboardLinks(packet);
    mountDetailScrollSpy();
}

function detailMetric(label, value, suffix) {
    return '<div class="metric"><span>' + label + '</span><strong>' + esc(value == null ? '-' : value) + '</strong><em>' + suffix + '</em></div>';
}

function overviewPill(label, valueClass, pillClass, value) {
    const content = pillClass
        ? '<span class="' + pillClass + '">' + esc(value) + '</span>'
        : esc(value);
    return '<div><span>' + esc(label) + '</span><strong class="' + esc(valueClass.trim()) + '">' + content + '</strong></div>';
}

function validationSection(validation) {
    if (!validation.results.length) {
        return '<section class="detail-section validation-section" id="detail-validation"><h3>Validation</h3>'
            + '<div class="validation-empty">No matching schema rules.</div></section>';
    }
    const rules = validation.results.map(result => {
        const errors = result.errors.length
            ? '<ul>' + result.errors.map(e => '<li><code>' + esc(e.path) + '</code> ' + esc(e.message) + '</li>').join('') + '</ul>'
            : '<div class="validation-ok">Body matches schema.</div>';
        return '<div class="validation-rule ' + (result.errors.length ? 'invalid' : 'valid') + '">'
            + '<div class="validation-head"><span class="validation-target">' + esc(result.target) + '</span>'
            + '<code>' + esc(result.pattern) + '</code>'
            + '<strong>' + (result.errors.length ? 'invalid' : 'valid') + '</strong></div>'
            + errors
            + '</div>';
    }).join('');
    return '<section class="detail-section validation-section" id="detail-validation"><h3>Validation</h3>' + rules + '</section>';
}

function dashboardSectionPlaceholder() {
    return '<section class="detail-section" id="detail-dashboards"><h3>Dashboards</h3>'
        + '<div id="detail-dashboards-list" class="dash-list"></div></section>';
}

function sectionHeaders(title, headers, direction) {
    if (!headers || Object.keys(headers).length === 0) return '';
    let rows = '';
    for (const [k, v] of Object.entries(headers)) {
        rows += '<tr><td class="k">' + esc(k) + '</td><td class="v">' + esc(v) + '</td></tr>';
    }
    const kind = direction === 'incoming' ? 'incoming' : 'outgoing';
    const label = kind === 'incoming' ? 'incoming' : 'outgoing';
    return '<div class="headers-card ' + kind + '"><h3>' + title
        + '<span class="headers-direction">' + label + '</span></h3>'
        + '<table class="kv headers-table">' + rows + '</table></div>';
}

// Detail bodies: valid JSON/HTML is rendered into a read-only CodeMirror (highlight + pretty-print);
// "Raw" falls back to a plain <pre> of the original bytes. Views are mounted after innerHTML is set.
let detailBodies = [];  // {title, body, code, mode, size, raw} per rendered body block
let detailSpy = null;   // IntersectionObserver that syncs nav tabs to scroll position
const DETAIL_VIEWER_MAX = '55vh';  // tall bodies scroll inside this instead of growing the drawer

function bodyBlock(title, body, binary, truncated, target, validationErrors) {
    if (body == null || body === '') return '';
    let note = '<span class="body-size">' + humanSize(body.length) + '</span>'
        + (truncated ? ' (truncated)' : '');
    let code = body;
    let mode = null;
    if (!binary) {
        const lang = detectLang(body);
        if (lang) { code = lang.code; mode = lang.mode; }
    } else {
        note += ' (binary — hex preview)';
    }
    const i = detailBodies.push({ title: stripTags(title), body, code, mode, size: body.length, raw: false, target, validationErrors: validationErrors || [] }) - 1;
    // The raw/formatted toggle only makes sense when there is a formatted form (highlighted mode).
    const toggle = mode ? viewToggleHtml(i) : '';
    const expand = ' <button type="button" class="body-expand" data-body="' + i
        + '" title="Expand to full screen" aria-label="Expand">⤢</button>';
    return '<div class="body-block" data-body="' + i + '"><h3>' + title + note + toggle + expand + '</h3>'
        + '<div class="cm-host" id="body-view-' + i + '"></div></div>';
}

function viewToggleHtml(i) {
    return ' <span class="body-toggle" role="group" aria-label="View mode">'
        + '<button type="button" class="bt active" data-body="' + i + '" data-raw="0">Formatted</button>'
        + '<button type="button" class="bt" data-body="' + i + '" data-raw="1">Raw</button>'
        + '</span>';
}

function stripTags(s) { return String(s).replace(/<[^>]*>/g, '').trim(); }

function humanSize(n) {
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
}

// Re-indent XML/HTML by inserting newlines between tags and tracking depth. Pure string transform:
// self-closing tags, comments, and processing instructions do not change depth. Best-effort — it
// never throws, so malformed markup falls back to a light reflow of whatever it produced.
function prettyXml(str) {
    const withBreaks = String(str).replace(/>\s*</g, '>\n<');
    const lines = withBreaks.split('\n');
    let depth = 0;
    const out = [];
    for (let line of lines) {
        line = line.trim();
        if (!line) continue;
        const isClose = /^<\//.test(line);
        const isSelf = /\/>$/.test(line) || /^<[!?]/.test(line);
        const opensAndCloses = /^<[^!?][^>]*>.*<\/[^>]+>$/.test(line);
        if (isClose) depth = Math.max(0, depth - 1);
        out.push('  '.repeat(depth) + line);
        if (!isClose && !isSelf && !opensAndCloses && /^<[^!?]/.test(line)) depth++;
    }
    return out.join('\n');
}

// Pick a CodeMirror mode from the body content: JSON gets pretty-printed; HTML/XML is highlighted
// as-is; anything else returns null so the caller falls back to a plain <pre>.
function detectLang(body) {
    const t = body.trimStart();
    if (t.startsWith('{') || t.startsWith('[')) {
        try { return { code: JSON.stringify(JSON.parse(body), null, 2), mode: 'application/json' }; }
        catch (e) { /* not valid JSON */ }
    }
    if (t.startsWith('<')) {
        return { code: prettyXml(body), mode: { name: 'xml', htmlMode: true } };
    }
    return null;
}

// Render one body into a container: raw (or unrecognized) -> plain <pre> of the original bytes;
// formatted -> highlighted CodeMirror. `where` tunes sizing ('detail' caps height, 'modal' fills).
function fillViewer(container, b, raw, where) {
    container.innerHTML = '';
    if (raw || !b.mode || !window.CodeMirror) {
        const pre = document.createElement('pre');
        pre.textContent = raw ? b.body : (b.mode ? b.code : b.body);
        container.appendChild(pre);
        return null;
    }
    const lines = (b.code.match(/\n/g) || []).length + 1;
    const big = b.code.length > 6000 || lines > 25;
    const cm = CodeMirror(container, {
        value: b.code,
        mode: b.mode,
        readOnly: true,
        lineNumbers: where === 'modal' || big,
        lineWrapping: true,
        viewportMargin: (where === 'modal' || big) ? 10 : Infinity,
    });
    applyValidationMarks(cm, b);
    if (where === 'detail' && big) cm.setSize(null, DETAIL_VIEWER_MAX);
    requestAnimationFrame(() => cm.refresh());  // drawer/modal animates in; size after layout
    return cm;
}

function applyValidationMarks(cm, b) {
    if (!b.validationErrors || !b.validationErrors.length || b.mode !== 'application/json') return;
    const marked = new Set();
    for (const error of b.validationErrors) {
        if (marked.has(error.path)) continue;
        const pos = findJsonPathPosition(b.code, error.path);
        if (!pos) continue;
        marked.add(error.path);
        cm.markText(pos.from, pos.to, { className: 'cm-schema-error', title: error.message });
    }
}

function findJsonPathPosition(code, path) {
    const parts = jsonPathParts(path);
    if (!parts.length) return null;
    const leaf = parts[parts.length - 1];
    if (typeof leaf !== 'string') return null;
    const needle = '"' + leaf.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
    const idx = code.indexOf(needle);
    if (idx < 0) return null;
    const before = code.slice(0, idx);
    const line = (before.match(/\n/g) || []).length;
    const lineStart = before.lastIndexOf('\n') + 1;
    return {
        from: CodeMirror.Pos(line, idx - lineStart),
        to: CodeMirror.Pos(line, idx - lineStart + needle.length),
    };
}

function jsonPathParts(path) {
    if (!path || path === '$') return [];
    const parts = [];
    const re = /\.([A-Za-z_$][\w$-]*)|\[(\d+)]/g;
    let m;
    while ((m = re.exec(path)) !== null) {
        parts.push(m[1] != null ? m[1] : Number(m[2]));
    }
    return parts;
}

function renderDetailView(i) {
    const c = document.getElementById('body-view-' + i);
    if (c) fillViewer(c, detailBodies[i], detailBodies[i].raw, 'detail');
}

function mountDetailBodies() {
    detailBodies.forEach((b, i) => renderDetailView(i));
}

function teardownDetailScrollSpy() {
    if (detailSpy) { detailSpy.disconnect(); detailSpy = null; }
}

function mountDetailScrollSpy() {
    teardownDetailScrollSpy();
    const jumps = ['overview', 'headers', 'bodies', 'raw'];
    const sections = jumps
        .map(j => ({ j, node: document.getElementById('detail-' + j) }))
        .filter(s => s.node);
    if (!sections.length) return;
    const pane = el.detailPane;
    let active = null;
    const setActive = (jump) => {
        if (jump === active) return;
        active = jump;
        el.detail.querySelectorAll('.detail-tab').forEach(t =>
            t.classList.toggle('active', t.dataset.jump === jump));
    };
    // Determine the active section: the last section (largest offsetTop) whose top edge is within
    // the first half of the visible pane. This correctly handles the case where scrollIntoView
    // cannot scroll all the way (e.g. last section) — the last visible-from-top section wins.
    const updateActive = () => {
        const threshold = pane.scrollTop + pane.clientHeight / 2;
        let best = sections[0];
        for (const s of sections) {
            if (s.node.offsetTop <= threshold) best = s;
        }
        setActive(best.j);
    };
    // Poll via rAF while the spy is alive so it reacts immediately after any scrollIntoView call.
    let alive = true;
    const tick = () => { if (!alive) return; updateActive(); requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
    detailSpy = { disconnect() { alive = false; } };
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

// ---- dashboard links ----
const DASHBOARD_LINKS_KEY = 'listview.dashboardLinks';
const DASHBOARD_WINDOW_KEY = 'listview.dashboardWindowMs';
const DEFAULT_DASHBOARD_WINDOW_MS = 300000;  // ±5 min
const DASHBOARD_PRESETS = {
    grafana: 'https://GRAFANA/d/UID?var-host={host}&from={fromMs}&to={toMs}',
    splunk: 'https://SPLUNK/app/search/search?q=search%20host%3D{host}&earliest={fromSec}&latest={toSec}',
    signalfx: 'https://app.signalfx.com/#/dashboard/ID?startTime={fromIso}&endTime={toIso}',
};
const DASHBOARD_SYSTEMS = {
    grafana: { label: 'Grafana', icon: 'icons/grafana.svg' },
    splunk: { label: 'Splunk', icon: 'icons/splunk.svg' },
    signalfx: { label: 'SignalFx', icon: 'icons/signalfx.svg' },
    custom: { label: 'Custom', icon: 'icons/custom.svg' },
};

function dashboardSystem(system) {
    return DASHBOARD_SYSTEMS[system] || DASHBOARD_SYSTEMS.custom;
}

function dashboardSystemIconHtml(system) {
    const meta = dashboardSystem(system);
    return '<img class="system-icon" src="' + esc(meta.icon) + '" alt="' + esc(meta.label) + '" loading="lazy"/>';
}

function createDashboardSystemIcon(system) {
    const meta = dashboardSystem(system);
    const img = document.createElement('img');
    img.className = 'system-icon';
    img.src = meta.icon;
    img.alt = meta.label;
    img.loading = 'lazy';
    return img;
}

function updateDashboardSystemPreview() {
    if (!el.dashSystemIcon) return;
    const meta = dashboardSystem(el.dashSystem.value);
    el.dashSystemIcon.src = meta.icon;
    el.dashSystemIcon.alt = meta.label;
}

function dashboardWindowMs() {
    const stored = Number(localStorage.getItem(DASHBOARD_WINDOW_KEY));
    return stored > 0 ? stored : DEFAULT_DASHBOARD_WINDOW_MS;
}

function dashboardUrlParts(url) {
    try {
        const u = new URL(url);
        return { host: u.hostname, port: u.port, scheme: u.protocol.replace(/:$/, ''),
            path: u.pathname, query: u.search.replace(/^\?/, '') };
    } catch (e) {
        return { host: '', port: '', scheme: '', path: '', query: '' };
    }
}

function dashboardVars(packet) {
    const win = dashboardWindowMs();
    const center = (packet && packet.timestamp ? Date.parse(packet.timestamp) : Date.now()) || Date.now();
    const fromMs = center - win, toMs = center + win;
    const vars = {
        fromMs, toMs, from: fromMs, to: toMs,
        fromSec: Math.floor(fromMs / 1000), toSec: Math.floor(toMs / 1000),
        fromIso: new Date(fromMs).toISOString(), toIso: new Date(toMs).toISOString(),
        // packet-scope placeholders resolve to empty strings when there is no packet (global links)
        url: '', host: '', port: '', scheme: '', path: '', query: '',
        method: '', status: '', label: '', type: '', thread: '',
        timestamp: '', epochMs: '', epochSec: '',
    };
    if (packet) {
        const u = dashboardUrlParts(packet.url || '');
        Object.assign(vars, {
            url: packet.url || '', host: u.host, port: u.port, scheme: u.scheme,
            path: u.path, query: u.query,
            method: packet.method || '', status: packet.status || '',
            label: packet.label || '', type: packet.type || '', thread: packet.threadName || '',
            timestamp: packet.timestamp || '', epochMs: center, epochSec: Math.floor(center / 1000),
        });
    }
    return vars;
}

function applyTemplate(template, vars) {
    return String(template || '').replace(/\{(\w+)}/g, (m, name) =>
        Object.prototype.hasOwnProperty.call(vars, name) ? encodeURIComponent(vars[name]) : m);
}

const DASHBOARD_VAR_HINTS = {
    host: 'packet host',
    port: 'packet port',
    scheme: 'packet URL scheme',
    path: 'packet URL path',
    query: 'packet URL query',
    method: 'packet method',
    status: 'packet status',
    label: 'packet label',
    type: 'packet type',
    thread: 'packet thread',
    fromMs: 'window start ms',
    toMs: 'window end ms',
    fromSec: 'window start seconds',
    toSec: 'window end seconds',
    fromIso: 'window start ISO',
    toIso: 'window end ISO',
    timestamp: 'packet timestamp',
    epochMs: 'packet timestamp ms',
    epochSec: 'packet timestamp seconds',
};

function renderTemplatePreview(template) {
    const text = String(template || '').trim();
    if (!text) return '<span class="template-empty">Variables like {host}, {fromMs}, and {toMs} will be highlighted here.</span>';
    const parts = [];
    const placeholderPattern = /\{(\w+)}/g;
    let lastIndex = 0;
    let match;
    while ((match = placeholderPattern.exec(text)) !== null) {
        if (match.index > lastIndex) parts.push(esc(text.slice(lastIndex, match.index)));
        const name = match[1];
        const known = Object.prototype.hasOwnProperty.call(DASHBOARD_VAR_HINTS, name);
        const title = known ? DASHBOARD_VAR_HINTS[name] : 'custom placeholder';
        parts.push('<span class="template-var' + (known ? '' : ' custom') + '" data-var="' + esc(name)
            + '" title="' + esc(title) + '">' + esc(match[0]) + '</span>');
        lastIndex = placeholderPattern.lastIndex;
    }
    if (lastIndex < text.length) parts.push(esc(text.slice(lastIndex)));
    return parts.join('');
}

function updateDashboardTemplatePreview() {
    if (!el.dashUrlPreview) return;
    el.dashUrlPreview.innerHTML = renderTemplatePreview(el.dashUrl.value);
}

function buildDashboardUrl(template, packet) {
    return applyTemplate(template, dashboardVars(packet));
}

function safeDashboardHref(url) {
    try {
        const u = new URL(url, window.location.origin);
        return (u.protocol === 'http:' || u.protocol === 'https:') ? u.href : null;
    } catch (e) {
        return null;
    }
}

function matchesLinkUrl(url, match) {
    if (!match) return true;
    try { return new RegExp(match).test(url); }
    catch (e) { return String(url).includes(match); }
}

function normalizeLink(l) {
    return {
        id: l.id || (String(Date.now()) + '-' + Math.random().toString(16).slice(2)),
        name: String(l.name),
        system: l.system || 'custom',
        scope: l.scope === 'global' ? 'global' : 'packet',
        urlTemplate: String(l.urlTemplate),
        match: l.match || '',
    };
}

function loadDashboardLinks(render = true) {
    try {
        const raw = localStorage.getItem(DASHBOARD_LINKS_KEY);
        const links = raw ? JSON.parse(raw) : [];
        state.dashboardLinks = Array.isArray(links)
            ? links.filter(l => l && l.name && l.urlTemplate).map(normalizeLink)
            : [];
    } catch (e) {
        state.dashboardLinks = [];
    }
    if (render) refreshDashboardViews();
}

function saveDashboardLinks() {
    localStorage.setItem(DASHBOARD_LINKS_KEY, JSON.stringify(state.dashboardLinks));
    refreshDashboardViews();
    rerenderSelectedDetail();
}

function refreshDashboardViews() {
    renderDashboardList();
    renderGlobalLinks();
}

function renderDashboardList() {
    if (!el.dashList) return;
    updateSettingsCounts();
    if (!state.dashboardLinks.length) {
        el.dashList.innerHTML = '<div class="schema-empty">No dashboard links.</div>';
        return;
    }
    el.dashList.innerHTML = state.dashboardLinks.map(link =>
        '<div class="schema-rule" data-id="' + esc(link.id) + '">'
        + dashboardSystemIconHtml(link.system)
        + '<span class="validation-target">' + esc(link.scope) + '</span>'
        + '<strong>' + esc(link.name) + '</strong>'
        + '<code class="template-code">' + renderTemplatePreview(link.urlTemplate) + '</code>'
        + '<button type="button" class="mini dash-delete" data-id="' + esc(link.id) + '">Delete</button>'
        + '</div>').join('');
}

function setDashMessage(text, ok) {
    el.dashMessage.textContent = text;
    el.dashMessage.className = 'schema-message' + (ok ? ' ok' : '');
}

function packetDashboardLinks(packet) {
    return state.dashboardLinks.filter(l =>
        l.scope === 'packet' && matchesLinkUrl(packet.url || packet.label || '', l.match));
}

function dashboardAnchor(link, packet) {
    const href = safeDashboardHref(buildDashboardUrl(link.urlTemplate, packet));
    if (!href) return null;
    const a = document.createElement('a');
    a.className = 'dash-link sys-' + cssToken(link.system || 'custom');
    a.href = href;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    a.appendChild(createDashboardSystemIcon(link.system));
    a.appendChild(document.createTextNode(link.name || link.urlTemplate));
    return a;
}

// Guarded: inert until #global-links exists (added in Task 4).
function renderGlobalLinks() {
    if (!el.globalLinks) return;
    el.globalLinks.innerHTML = '';
    state.dashboardLinks.filter(l => l.scope === 'global').forEach(l => {
        const a = dashboardAnchor(l, null);
        if (a) el.globalLinks.appendChild(a);
    });
}

// Guarded: inert until #detail-dashboards-list exists (added in Task 3).
function mountDashboardLinks(packet) {
    const host = document.getElementById('detail-dashboards-list');
    if (!host || !packet) return;
    host.innerHTML = '';
    const links = packetDashboardLinks(packet);
    if (!links.length) { host.innerHTML = '<div class="dash-empty">No dashboard links.</div>'; return; }
    links.forEach(l => { const a = dashboardAnchor(l, packet); if (a) host.appendChild(a); });
}

function loadSettingsTab() {
    const stored = localStorage.getItem(SETTINGS_TAB_KEY);
    setSettingsTab(['dashboards', 'language'].includes(stored) ? stored : 'schema', false);
}

function setSettingsTab(tab, persist = true) {
    state.settingsTab = ['dashboards', 'language'].includes(tab) ? tab : 'schema';
    if (persist) localStorage.setItem(SETTINGS_TAB_KEY, state.settingsTab);
    [el.schemaPanel, el.dashboardPanel, document.getElementById('language-panel')].forEach(panel => {
        if (panel) panel.hidden = panel.id !== state.settingsTabPanelId;
    });
    el.settingsTabs.forEach(tabEl => {
        const selected = tabEl.dataset.settingsTab === state.settingsTab;
        tabEl.classList.toggle('active', selected);
        tabEl.setAttribute('aria-selected', selected ? 'true' : 'false');
    });
}

Object.defineProperty(state, 'settingsTabPanelId', {
    get() {
        if (state.settingsTab === 'dashboards') return 'dashboard-panel';
        if (state.settingsTab === 'language') return 'language-panel';
        return 'schema-panel';
    }
});

function focusActiveSettingsField() {
    if (state.settingsTab === 'dashboards') {
        el.dashName.focus();
    } else if (state.settingsTab === 'language') {
        const active = document.querySelector('.language-option.active');
        if (active) active.focus();
    } else {
        el.schemaPattern.focus();
    }
}

function openSettingsView() {
    el.trafficView.hidden = true;
    el.settingsView.hidden = false;
    el.settingsToggle.setAttribute('aria-expanded', 'true');
    focusActiveSettingsField();
}

function closeSettingsView() {
    el.settingsView.hidden = true;
    el.trafficView.hidden = false;
    el.settingsToggle.setAttribute('aria-expanded', 'false');
    el.settingsToggle.focus();
}

function loadLanguage() {
    const stored = localStorage.getItem(LANGUAGE_KEY) === 'ru' ? 'ru' : 'en';
    setLanguage(stored, false);
}

function setLanguage(language, persist = true) {
    const selectedLanguage = language === 'ru' ? 'ru' : 'en';
    if (persist) localStorage.setItem(LANGUAGE_KEY, selectedLanguage);
    el.languageOptions.forEach(option => {
        const selected = option.dataset.language === selectedLanguage;
        option.classList.toggle('active', selected);
        option.setAttribute('aria-pressed', selected ? 'true' : 'false');
    });
}

function updateSettingsCounts() {
    if (el.schemaCount) el.schemaCount.textContent = String(state.schemaRules.length);
    if (el.dashboardCount) el.dashboardCount.textContent = String(state.dashboardLinks.length);
}

function setStatus(text, kind) {
    el.status.textContent = text;
    el.status.style.color = kind === 'ok' ? 'var(--ok)' : kind === 'err' ? 'var(--err)' : kind === 'run' ? 'var(--accent)' : 'var(--muted)';
}

function loadSchemaRules(render = true) {
    try {
        const raw = localStorage.getItem(SCHEMA_RULES_KEY);
        const rules = raw ? JSON.parse(raw) : [];
        state.schemaRules = Array.isArray(rules) ? rules.filter(r => r && r.pattern && r.target && r.schema) : [];
    } catch (e) {
        state.schemaRules = [];
    }
    if (render) renderSchemaRules();
}

function saveSchemaRules() {
    localStorage.setItem(SCHEMA_RULES_KEY, JSON.stringify(state.schemaRules));
    renderSchemaRules();
    rerenderSelectedDetail();
}

function renderSchemaRules() {
    if (!el.schemaList) return;
    updateSettingsCounts();
    if (!state.schemaRules.length) {
        el.schemaList.innerHTML = '<div class="schema-empty">No schema rules.</div>';
        return;
    }
    el.schemaList.innerHTML = state.schemaRules.map(rule =>
        '<div class="schema-rule" data-id="' + esc(rule.id) + '">'
        + '<span class="validation-target">' + esc(rule.target) + '</span>'
        + '<code>' + esc(rule.pattern) + '</code>'
        + '<button type="button" class="mini schema-delete" data-id="' + esc(rule.id) + '">Delete</button>'
        + '</div>').join('');
}

function setSchemaMessage(text, ok) {
    el.schemaMessage.textContent = text;
    el.schemaMessage.className = 'schema-message' + (ok ? ' ok' : '');
}

function rerenderSelectedDetail() {
    if (state.selectedId == null) return;
    renderDetail(state.packets.find(p => p.id === state.selectedId));
}

function validatePacket(packet) {
    loadSchemaRules(false);
    const results = [];
    const paths = { request: [], response: [] };
    for (const rule of state.schemaRules) {
        if (!matchesUrlPattern(packet.url || packet.label || '', rule.pattern)) continue;
        const target = rule.target === 'request' ? 'request' : 'response';
        const body = target === 'request' ? packet.requestBody : packet.responseBody;
        const errors = validateBodyAgainstSchema(body, rule.schema);
        results.push({ target, pattern: rule.pattern, errors });
        paths[target].push(...errors.filter(e => e.path).map(e => ({ path: e.path, message: e.message })));
    }
    return { results, paths };
}

function validateBodyAgainstSchema(body, schema) {
    if (body == null || body === '') return [{ path: '$', message: 'body is empty' }];
    let value;
    try {
        value = JSON.parse(body);
    } catch (e) {
        return [{ path: '$', message: 'body is not valid JSON: ' + e.message }];
    }
    return validateValue(value, schema, '$');
}

function validateValue(value, schema, path) {
    if (!schema || typeof schema !== 'object') return [];
    const errors = [];
    if (schema.type && !schemaTypeMatches(value, schema.type)) {
        errors.push({ path, message: 'expected ' + schema.type + ', got ' + valueType(value) });
        return errors;
    }
    if (Array.isArray(schema.enum) && !schema.enum.some(v => JSON.stringify(v) === JSON.stringify(value))) {
        errors.push({ path, message: 'expected one of ' + schema.enum.map(v => JSON.stringify(v)).join(', ') });
    }
    if (typeof value === 'string') {
        if (schema.minLength != null && value.length < schema.minLength) errors.push({ path, message: 'minLength ' + schema.minLength + ' not met' });
        if (schema.maxLength != null && value.length > schema.maxLength) errors.push({ path, message: 'maxLength ' + schema.maxLength + ' exceeded' });
    }
    if (typeof value === 'number') {
        if (schema.minimum != null && value < schema.minimum) errors.push({ path, message: 'minimum ' + schema.minimum + ' not met' });
        if (schema.maximum != null && value > schema.maximum) errors.push({ path, message: 'maximum ' + schema.maximum + ' exceeded' });
    }
    if (value && typeof value === 'object' && !Array.isArray(value)) {
        const props = schema.properties || {};
        if (Array.isArray(schema.required)) {
            for (const key of schema.required) {
                if (!Object.prototype.hasOwnProperty.call(value, key)) {
                    errors.push({ path: path + '.' + key, message: 'required field is missing' });
                }
            }
        }
        for (const [key, childSchema] of Object.entries(props)) {
            if (Object.prototype.hasOwnProperty.call(value, key)) {
                errors.push(...validateValue(value[key], childSchema, path + '.' + key));
            }
        }
        if (schema.additionalProperties === false) {
            for (const key of Object.keys(value)) {
                if (!Object.prototype.hasOwnProperty.call(props, key)) {
                    errors.push({ path: path + '.' + key, message: 'additional property is not allowed' });
                }
            }
        }
    }
    if (Array.isArray(value) && schema.items) {
        value.forEach((item, i) => errors.push(...validateValue(item, schema.items, path + '[' + i + ']')));
    }
    return errors;
}

function schemaTypeMatches(value, type) {
    const types = Array.isArray(type) ? type : [type];
    return types.some(t => valueType(value) === t || (t === 'number' && valueType(value) === 'integer'));
}

function valueType(value) {
    if (value === null) return 'null';
    if (Array.isArray(value)) return 'array';
    if (Number.isInteger(value)) return 'integer';
    return typeof value;
}

function matchesUrlPattern(url, pattern) {
    const target = parseUrlParts(url);
    const wanted = parsePatternParts(pattern);
    if (!wanted.path) return false;
    const targetSegments = splitPath(target.path);
    const patternSegments = splitPath(wanted.path);
    if (targetSegments.length !== patternSegments.length) return false;
    for (let i = 0; i < patternSegments.length; i++) {
        const part = patternSegments[i];
        if (part === '*') continue;
        if (/^\{[^}]+}$/.test(part)) continue;
        if (decodeURIComponent(targetSegments[i]) !== decodeURIComponent(part)) return false;
    }
    for (const [key, value] of wanted.query.entries()) {
        if (!target.query.has(key)) return false;
        if (value !== '*' && target.query.get(key) !== value) return false;
    }
    return true;
}

function parseUrlParts(url) {
    try {
        const parsed = new URL(url, window.location.origin);
        return { path: parsed.pathname || '/', query: parsed.searchParams };
    } catch (e) {
        return parsePatternParts(url);
    }
}

function parsePatternParts(pattern) {
    const [path, query = ''] = String(pattern || '').split('?');
    return { path: path || '/', query: new URLSearchParams(query) };
}

function splitPath(path) {
    const clean = String(path || '/').replace(/^\/+|\/+$/g, '');
    return clean ? clean.split('/') : [];
}

// ---- run form ----
el.form.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    ensureStream();
    const payload = {
        url: el.url.value.trim(),
        method: el.method.value,
        body: bodyValue() || '',
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
    if (!window.confirm('Clear all captured packets?')) return;
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

el.settingsToggle.addEventListener('click', () => {
    if (el.settingsView.hidden) {
        openSettingsView();
    } else {
        closeSettingsView();
    }
});

el.settingsBack.addEventListener('click', closeSettingsView);

el.settingsTabs.forEach(tab => {
    tab.addEventListener('click', () => {
        setSettingsTab(tab.dataset.settingsTab);
        focusActiveSettingsField();
    });
});

el.languageOptions.forEach(option => {
    option.addEventListener('click', () => setLanguage(option.dataset.language));
});

el.schemaSave.addEventListener('click', () => {
    const pattern = el.schemaPattern.value.trim();
    if (!pattern) { setSchemaMessage('URL pattern is required', false); return; }
    let schema;
    try {
        schema = JSON.parse(el.schemaJson.value);
    } catch (e) {
        setSchemaMessage('Schema JSON: ' + e.message, false);
        return;
    }
    if (!schema || typeof schema !== 'object' || Array.isArray(schema)) {
        setSchemaMessage('Schema must be a JSON object', false);
        return;
    }
    state.schemaRules.push({
        id: String(Date.now()) + '-' + Math.random().toString(16).slice(2),
        pattern,
        target: el.schemaTarget.value === 'request' ? 'request' : 'response',
        schema,
    });
    el.schemaPattern.value = '';
    el.schemaJson.value = '';
    setSchemaMessage('Saved', true);
    saveSchemaRules();
});

el.schemaList.addEventListener('click', (ev) => {
    const bt = ev.target.closest('.schema-delete');
    if (!bt) return;
    state.schemaRules = state.schemaRules.filter(rule => rule.id !== bt.dataset.id);
    setSchemaMessage('Deleted', true);
    saveSchemaRules();
});

// ---- dashboard links panel ----
el.dashPreset.addEventListener('change', () => {
    const tpl = DASHBOARD_PRESETS[el.dashPreset.value];
    if (tpl) {
        el.dashUrl.value = tpl;
        el.dashSystem.value = el.dashPreset.value;
        updateDashboardTemplatePreview();
        updateDashboardSystemPreview();
    }
    el.dashPreset.value = '';
});
el.dashSystem.addEventListener('change', updateDashboardSystemPreview);
el.dashUrl.addEventListener('input', updateDashboardTemplatePreview);
el.dashWindow.addEventListener('change', () => {
    const min = Math.max(1, Number(el.dashWindow.value) || 5);
    localStorage.setItem(DASHBOARD_WINDOW_KEY, String(min * 60000));
    rerenderSelectedDetail();
});
el.dashSave.addEventListener('click', () => {
    const name = el.dashName.value.trim();
    const urlTemplate = el.dashUrl.value.trim();
    if (!name) { setDashMessage('Name is required', false); return; }
    if (!urlTemplate) { setDashMessage('URL template is required', false); return; }
    state.dashboardLinks.push(normalizeLink({
        name, system: el.dashSystem.value, scope: el.dashScope.value,
        urlTemplate, match: el.dashMatch.value.trim(),
    }));
    el.dashName.value = '';
    el.dashUrl.value = '';
    el.dashMatch.value = '';
    setDashMessage('Saved', true);
    saveDashboardLinks();
});
el.dashList.addEventListener('click', (ev) => {
    const bt = ev.target.closest('.dash-delete');
    if (!bt) return;
    state.dashboardLinks = state.dashboardLinks.filter(l => l.id !== bt.dataset.id);
    setDashMessage('Deleted', true);
    saveDashboardLinks();
});

// ---- detail drawer + keyboard navigation ----
el.detailClose.addEventListener('click', closeDetail);
el.detailRestore.addEventListener('click', () => {
    restoreDetail();
    if (state.selectedId != null) el.detailPane.classList.add('open');
});

// ---- full-screen body viewer modal (with in-body find) ----
let modalBodyIndex = null;
let modalRaw = false;
let modalCm = null;
let searchMarks = [];
let searchHits = [];
let searchIdx = -1;

function openBodyModal(i) {
    const b = detailBodies[i];
    if (!b) return;
    modalBodyIndex = i;
    modalRaw = false;
    el.bmTitle.textContent = b.title;
    el.bmSize.textContent = humanSize(b.size);
    el.bmToggle.innerHTML = b.mode
        ? '<button type="button" class="bt active" data-raw="0">Formatted</button>'
          + '<button type="button" class="bt" data-raw="1">Raw</button>'
        : '';
    el.bmSearch.value = '';
    el.bmRegex.checked = false;
    el.bmSearch.classList.remove('invalid');
    el.bodyModal.hidden = false;
    renderModalView();
    el.bmSearch.focus();
}
function renderModalView() {
    modalCm = fillViewer(el.bmHost, detailBodies[modalBodyIndex], modalRaw, 'modal');
    runBodySearch();  // re-apply the current query to the (re)rendered content
}
function closeBodyModal() {
    clearBodySearch();
    el.bodyModal.hidden = true;
    el.bmHost.innerHTML = '';
    modalBodyIndex = null;
    modalCm = null;
}

function clearBodySearch() {
    searchMarks.forEach(m => m.clear());
    searchMarks = [];
    searchHits = [];
    searchIdx = -1;
}
function runBodySearch() {
    clearBodySearch();
    const raw = el.bmSearch.value;
    if (!modalCm || !raw) { el.bmCount.textContent = ''; el.bmSearch.classList.remove('invalid'); return; }
    let query = raw;
    if (el.bmRegex.checked) {
        try { query = new RegExp(raw, 'gi'); el.bmSearch.classList.remove('invalid'); }
        catch (e) { el.bmSearch.classList.add('invalid'); el.bmCount.textContent = 'bad regex'; return; }
    } else {
        el.bmSearch.classList.remove('invalid');
    }
    const cur = modalCm.getSearchCursor(query, CodeMirror.Pos(modalCm.firstLine(), 0), { caseFold: true });
    while (cur.findNext()) {
        const from = cur.from(), to = cur.to();
        if (from.line === to.line && from.ch === to.ch) break;  // guard zero-length regex matches
        searchHits.push({ from, to });
        searchMarks.push(modalCm.markText(from, to, { className: 'cm-search-hit' }));
        if (searchHits.length >= 5000) break;
    }
    searchIdx = searchHits.length ? 0 : -1;
    focusHit();
    updateSearchCount();
}
function focusHit() {
    if (searchIdx < 0) return;
    const h = searchHits[searchIdx];
    modalCm.setSelection(h.from, h.to);
    modalCm.scrollIntoView({ from: h.from, to: h.to }, 80);
}
function updateSearchCount() {
    el.bmCount.textContent = searchHits.length ? (searchIdx + 1) + '/' + searchHits.length : '0';
}
function stepSearch(delta) {
    if (!searchHits.length) return;
    searchIdx = (searchIdx + delta + searchHits.length) % searchHits.length;
    focusHit();
    updateSearchCount();
}

// Detail body controls: Expand opens the modal; the Formatted/Raw toggle re-renders that block.
el.detail.addEventListener('click', (ev) => {
    const tab = ev.target.closest('.detail-tab');
    if (tab) {
        el.detail.querySelectorAll('.detail-tab').forEach(x => x.classList.toggle('active', x === tab));
        const target = document.getElementById('detail-' + tab.dataset.jump);
        if (target) target.scrollIntoView({ block: 'start', behavior: 'smooth' });
        return;
    }
    const exp = ev.target.closest('.body-expand');
    if (exp) { openBodyModal(Number(exp.dataset.body)); return; }
    const bt = ev.target.closest('.bt');
    if (bt) {
        const i = Number(bt.dataset.body);
        detailBodies[i].raw = bt.dataset.raw === '1';
        bt.parentElement.querySelectorAll('.bt').forEach(x => x.classList.toggle('active', x === bt));
        renderDetailView(i);
    }
});
el.bmClose.addEventListener('click', closeBodyModal);
el.bmToggle.addEventListener('click', (ev) => {
    const bt = ev.target.closest('.bt');
    if (!bt) return;
    modalRaw = bt.dataset.raw === '1';
    el.bmToggle.querySelectorAll('.bt').forEach(x => x.classList.toggle('active', x === bt));
    renderModalView();
});
el.bmSearch.addEventListener('input', runBodySearch);
el.bmRegex.addEventListener('change', runBodySearch);
el.bmSearch.addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter') { ev.preventDefault(); stepSearch(ev.shiftKey ? -1 : 1); }
});
el.bmPrev.addEventListener('click', () => stepSearch(-1));
el.bmNext.addEventListener('click', () => stepSearch(1));
el.bodyModal.addEventListener('click', (ev) => {
    if (ev.target === el.bodyModal) closeBodyModal();  // click backdrop to dismiss
});

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
        if (!el.bodyModal.hidden) { closeBodyModal(); ev.preventDefault(); }
        else if (el.detailPane.classList.contains('open')) { closeDetail(); ev.preventDefault(); }
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
    if (open) { refreshBodyEditor(); el.url.focus(); }
}
el.runToggle.addEventListener('click', () => setRunPanel(!el.runPanel.classList.contains('open')));

function updateBodyVisibility() {
    const m = el.method.value;
    el.bodyField.hidden = !(m === 'POST' || m === 'PUT' || m === 'PATCH');
    refreshBodyEditor();
}
el.method.addEventListener('change', updateBodyVisibility);

// ---- request body editor (CodeMirror over the textarea) ----
function initBodyEditor() {
    if (state.bodyEditor || !window.CodeMirror) return;
    state.bodyEditor = CodeMirror.fromTextArea(el.body, {
        mode: 'application/json',
        lineNumbers: true,
        lineWrapping: true,
        viewportMargin: Infinity,  // auto-grow with .CodeMirror{height:auto}
    });
    state.bodyEditor.on('change', validateBody);
}
function bodyValue() {
    return state.bodyEditor ? state.bodyEditor.getValue() : el.body.value;
}
function refreshBodyEditor() {
    // CM mis-measures while its container is display:none/clipped; refresh once visible.
    if (state.bodyEditor && !el.bodyField.hidden) {
        requestAnimationFrame(() => state.bodyEditor.refresh());
    }
}
function validateBody() {
    const v = bodyValue().trim();
    if (!v) { el.bodyErr.textContent = ''; el.bodyErr.className = 'body-err'; return true; }
    try { JSON.parse(v); el.bodyErr.textContent = '✓ valid JSON'; el.bodyErr.className = 'body-err ok'; return true; }
    catch (e) { el.bodyErr.textContent = '✕ ' + e.message; el.bodyErr.className = 'body-err'; return false; }
}
el.bodyFormat.addEventListener('click', () => {
    try {
        state.bodyEditor.setValue(JSON.stringify(JSON.parse(bodyValue()), null, 2));
        validateBody();
    } catch (e) {
        el.bodyErr.textContent = '✕ ' + e.message;
        el.bodyErr.className = 'body-err';
    }
});

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
initBodyEditor();
updateBodyVisibility();
loadFilters();
renderActiveFilters();
loadSettingsTab();
loadLanguage();
updateDashboardSystemPreview();
updateDashboardTemplatePreview();
loadSchemaRules();
const storedDashWin = Number(localStorage.getItem(DASHBOARD_WINDOW_KEY));
if (el.dashWindow && storedDashWin > 0) el.dashWindow.value = String(Math.round(storedDashWin / 60000));
loadDashboardLinks();
// open the SSE stream immediately so demo/manual runs are captured
ensureStream();
// backfill any packets captured before the page was opened (e.g. an external jmeter-dsl test)
loadRecent();
