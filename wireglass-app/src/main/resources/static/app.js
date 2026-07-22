'use strict';

const state = {
    packets: [],          // all received packets
    runs: [],
    seen: new Set(),      // packet ids already added (dedupe history vs. live SSE)
    selectedId: null,
    selectedRunId: null,
    activeRunId: null,
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
    serverSchemaRules: [],
    localSchemaRules: [],
    urlSchemaSources: [],   // [{url, fetchedAt, rules:[...]}] loaded ad hoc from a URL repository
    schemaOverrides: {},    // id -> {name,pattern,target,schema} local edit of a server/url-sourced rule
    schemaRules: [],        // effective (enabled, override-applied) list used by the validator
    schemaRulesVersion: 0,  // bumped whenever schemaRules is recomputed; invalidates validation cache
    packetCache: new Map(), // packet.id -> { rulesVersion, validation, lang } memoized per-packet work
    serverDashboardLinks: [],
    localDashboardLinks: [],
    dashboardLinks: [],
    disabledServerItems: new Set(),
    traceLinks: [],
    editingDashLinkId: null,
    editingTraceIndex: null,
    editingSchemaRuleId: null,
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
    runAll: document.getElementById('run-all'),
    runList: document.getElementById('run-list'),
    sessionSave: document.getElementById('session-save'),
    sessionLoad: document.getElementById('session-load'),
    sessionFile: document.getElementById('session-file'),
    sessionMessage: document.getElementById('session-message'),
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
    schemaName: document.getElementById('schema-name'),
    schemaPattern: document.getElementById('schema-pattern'),
    schemaTarget: document.getElementById('schema-target'),
    schemaJson: document.getElementById('schema-json'),
    schemaSave: document.getElementById('schema-save'),
    schemaCancelEdit: document.getElementById('schema-cancel-edit'),
    schemaMessage: document.getElementById('schema-message'),
    schemaList: document.getElementById('schema-list'),
    schemaRemoteUrl: document.getElementById('schema-remote-url'),
    schemaRemoteLoad: document.getElementById('schema-remote-load'),
    schemaRemoteMessage: document.getElementById('schema-remote-message'),
    schemaRemoteSources: document.getElementById('schema-remote-sources'),
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
    dashCancelEdit: document.getElementById('dash-cancel-edit'),
    dashMessage: document.getElementById('dash-message'),
    dashList: document.getElementById('dash-list'),
    traceHeader: document.getElementById('trace-header'),
    traceUrl: document.getElementById('trace-url'),
    traceSave: document.getElementById('trace-save'),
    traceCancelEdit: document.getElementById('trace-cancel-edit'),
    traceMessage: document.getElementById('trace-message'),
    traceList: document.getElementById('trace-list'),
    traceCount: document.getElementById('trace-count'),
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
const SCHEMA_OVERRIDES_KEY = 'listview.schemaOverrides';
const URL_SCHEMA_SOURCES_KEY = 'listview.urlSchemaSources';
const SETTINGS_TAB_KEY = 'listview.settingsTab';
const LANGUAGE_KEY = 'listview.language';
const DISABLED_SERVER_ITEMS_KEY = 'listview.disabledServerItems';

function serverItemKey(kind, id) {
    return kind + ':' + String(id || '');
}

function isServerItemDisabled(kind, id) {
    return state.disabledServerItems.has(serverItemKey(kind, id));
}

function loadDisabledServerItems() {
    try {
        const raw = localStorage.getItem(DISABLED_SERVER_ITEMS_KEY);
        const ids = raw ? JSON.parse(raw) : [];
        state.disabledServerItems = new Set(Array.isArray(ids) ? ids.map(String) : []);
    } catch (e) {
        state.disabledServerItems = new Set();
    }
}

function saveDisabledServerItems() {
    localStorage.setItem(DISABLED_SERVER_ITEMS_KEY, JSON.stringify([...state.disabledServerItems].sort()));
}

function toggleServerItem(kind, id) {
    const key = serverItemKey(kind, id);
    if (state.disabledServerItems.has(key)) state.disabledServerItems.delete(key);
    else state.disabledServerItems.add(key);
    saveDisabledServerItems();
    rebuildEffectiveRules();
}

function normalizeSchemaRule(rule, source, sourceUrl, origin) {
    return {
        id: String(rule.id || (String(Date.now()) + '-' + Math.random().toString(16).slice(2))),
        name: rule.name ? String(rule.name) : '',
        pattern: String(rule.pattern),
        target: rule.target === 'request' ? 'request' : 'response',
        schema: rule.schema,
        source: source || 'local',
        sourceUrl: sourceUrl || null,
        origin: origin || null,
    };
}

function normalizeRemoteDashboardLink(link) {
    return normalizeLink({
        id: String(link.id),
        name: String(link.name),
        system: link.system || 'grafana',
        scope: link.scope === 'global' ? 'global' : 'packet',
        urlTemplate: String(link.urlTemplate),
        match: link.match || '',
        source: 'server',
        origin: link.origin || null,
    });
}

// Raw (pre-override, pre-disable) rules from the server config endpoint and any ad hoc URL
// sources, de-duplicated by id (a later source wins, matching load order).
function allRemoteSchemaRules() {
    const byId = new Map();
    state.serverSchemaRules.forEach(r => byId.set(r.id, r));
    state.urlSchemaSources.forEach(s => s.rules.forEach(r => byId.set(r.id, r)));
    return [...byId.values()];
}

// A local edit of a server/url-sourced rule is stored separately from the cached raw rule so
// that reloading/refreshing the source never silently discards it; `overridden: true` is what
// the yellow highlight keys off (see renderSchemaRules).
function effectiveSchemaRule(rule) {
    const override = state.schemaOverrides[rule.id];
    return override ? { ...rule, ...override, overridden: true } : rule;
}

function findSchemaRuleById(id) {
    return allRemoteSchemaRules().map(effectiveSchemaRule).concat(state.localSchemaRules)
        .find(r => r.id === id) || null;
}

function computeEffectiveSchemaRules() {
    state.schemaRules = allRemoteSchemaRules()
        .filter(r => !isServerItemDisabled('schema', r.id))
        .map(effectiveSchemaRule)
        .concat(state.localSchemaRules);
    state.schemaRulesVersion++;
}

function computeEffectiveDashboardLinks() {
    state.dashboardLinks = state.serverDashboardLinks
        .filter(l => !isServerItemDisabled('dashboard', l.id))
        .concat(state.localDashboardLinks);
}

// Every view that shows validation state, after the effective rule set changed. The packet
// table draws a ✓/✗ shield per row (see appendRow), so it must repaint here too — leaving it
// out is what made a disabled rule keep its shield until some unrelated filter change.
function refreshSchemaViews() {
    computeEffectiveSchemaRules();
    renderSchemaRules();
    rebuildList();
    rerenderSelectedDetail();
}

function rebuildEffectiveRules() {
    computeEffectiveDashboardLinks();
    refreshDashboardViews();
    refreshSchemaViews();
}

// Loads the app-configured (single, backend-side) server config. Independent of the ad hoc
// URL-source cache below — this always targets /api/config/rules.
async function loadServerConfig() {
    try {
        const res = await fetch('/api/config/rules', { cache: 'no-store' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const config = await res.json();
        state.serverSchemaRules = Array.isArray(config.schemas)
            ? config.schemas.filter(r => r && r.id && r.pattern && r.target && r.schema)
                .map(r => normalizeSchemaRule(r, 'server', null, r.origin))
            : [];
        state.serverDashboardLinks = Array.isArray(config.dashboards)
            ? config.dashboards.filter(l => l && l.id && l.name && l.urlTemplate)
                .map(normalizeRemoteDashboardLink)
            : [];
    } catch (e) {
        state.serverSchemaRules = [];
        state.serverDashboardLinks = [];
        console.warn('[config] failed to load server rules', e);
    }
    rebuildEffectiveRules();
}

function loadSchemaOverrides() {
    try {
        const raw = localStorage.getItem(SCHEMA_OVERRIDES_KEY);
        const parsed = raw ? JSON.parse(raw) : {};
        state.schemaOverrides = parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch (e) {
        state.schemaOverrides = {};
    }
}

function saveSchemaOverrides() {
    localStorage.setItem(SCHEMA_OVERRIDES_KEY, JSON.stringify(state.schemaOverrides));
}

function loadUrlSchemaSources(render = true) {
    try {
        const raw = localStorage.getItem(URL_SCHEMA_SOURCES_KEY);
        const stored = raw ? JSON.parse(raw) : [];
        state.urlSchemaSources = Array.isArray(stored)
            ? stored.filter(s => s && s.url).map(s => ({
                url: String(s.url),
                fetchedAt: s.fetchedAt || null,
                rules: Array.isArray(s.rules)
                    ? s.rules.filter(r => r && r.id && r.pattern && r.target && r.schema)
                        .map(r => normalizeSchemaRule(r, 'url', s.url))
                    : [],
            }))
            : [];
    } catch (e) {
        state.urlSchemaSources = [];
    }
    computeEffectiveSchemaRules();
    if (render) renderSchemaRules();
}

function saveUrlSchemaSources() {
    localStorage.setItem(URL_SCHEMA_SOURCES_KEY, JSON.stringify(state.urlSchemaSources));
    refreshSchemaViews();
}

// Fetched client-side (subject to the target's CORS policy) since this URL is picked by the
// user at runtime, not known to the backend ahead of time. Same {version, schemas} shape as the
// backend-configured server config (docs/server-config-format.md).
async function loadSchemaRulesFromUrl(url) {
    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const config = await res.json();
    if (config.version !== 1) throw new Error('unsupported version ' + config.version);
    const rules = Array.isArray(config.schemas)
        ? config.schemas.filter(r => r && r.id && r.pattern && r.target && r.schema)
        : [];
    if (!rules.length) throw new Error('no valid schema rules found');
    const withoutUrl = state.urlSchemaSources.filter(s => s.url !== url);
    state.urlSchemaSources = withoutUrl.concat([{
        url,
        fetchedAt: Date.now(),
        rules: rules.map(r => normalizeSchemaRule(r, 'url', url)),
    }]);
    saveUrlSchemaSources();
}

function setSchemaRemoteMessage(text, ok) {
    if (!el.schemaRemoteMessage) return;
    el.schemaRemoteMessage.textContent = text;
    el.schemaRemoteMessage.className = 'schema-message' + (ok ? ' ok' : '');
}

function renderUrlSchemaSources() {
    if (!el.schemaRemoteSources) return;
    if (!state.urlSchemaSources.length) {
        el.schemaRemoteSources.innerHTML = '<div class="schema-empty">' + esc(t('list.noSources')) + '</div>';
        return;
    }
    el.schemaRemoteSources.innerHTML = state.urlSchemaSources.map(s =>
        '<div class="schema-rule" data-url="' + esc(s.url) + '">'
        + '<code>' + esc(s.url) + '</code>'
        + '<span class="validation-target">' + esc(t('count.rules', { n: s.rules.length })) + '</span>'
        + '<button type="button" class="mini schema-source-refresh" data-url="' + esc(s.url) + '">' + esc(t('list.refresh')) + '</button>'
        + '<button type="button" class="mini schema-source-remove" data-url="' + esc(s.url) + '">' + esc(t('list.remove')) + '</button>'
        + '</div>').join('');
}

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
    if (packet && !packet.runId) {
        packet.runId = state.activeRunId || state.selectedRunId || null;
    }
    if (packet && packet.id != null && state.seen.has(packet.id)) {
        return; // already shown (history loaded after a live SSE delivery, or vice versa)
    }
    if (packet && packet.id != null) {
        state.seen.add(packet.id);
    }
    state.packets.push(packet);
    upsertRunFromPacket(packet);
    if (packet && packet.elapsedMs > state.maxElapsed) {
        state.maxElapsed = packet.elapsedMs;
        rescaleWaterfalls();
    }
    if (state.selectedRunId && packet.runId !== state.selectedRunId) {
        updateCount(visiblePackets().filter(matchesFilter).length);
        return;
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

function upsertRunFromPacket(packet) {
    if (!packet || !packet.runId) return;
    if (state.runs.some(run => run.id === packet.runId)) return;
    loadRuns();
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
        const suffix = state.selectedRunId
            ? '?runId=' + encodeURIComponent(state.selectedRunId) + '&limit=500'
            : '?limit=500';
        const r = await fetch('/api/packets' + suffix);
        if (!r.ok) return;
        state.packets = [];
        state.seen = new Set();
        state.packetCache.clear();
        const recent = await r.json();
        for (const p of recent) {
            addPacket(p);
        }
        rebuildList();  // normalize numbering/count + show empty-state if restored filters hide all
    } catch (e) {
        // ignore: live SSE will still carry new packets
    }
}

async function loadRuns() {
    try {
        const r = await fetch('/api/runs');
        if (!r.ok) return;
        state.runs = await r.json();
        renderRunList();
    } catch (e) {
        // ignore; packets still render without summaries
    }
}

function shortId(id) {
    return id ? String(id).slice(0, 8) : '';
}

function renderRunList() {
    if (!el.runList || !el.runAll) return;
    el.runAll.classList.toggle('active', !state.selectedRunId);
    el.runList.innerHTML = state.runs.map(run =>
        '<button type="button" class="run-chip' + (run.id === state.selectedRunId ? ' active' : '') + '"'
        + ' data-run-id="' + esc(run.id) + '" title="run ' + esc(run.id) + '">'
        + '<span class="src">' + esc(run.source) + '</span> '
        + '<span class="rid">#' + esc(shortId(run.id)) + '</span> '
        + '<span class="state">' + esc(run.state) + '</span>'
        + (run.restored ? ' <span class="restored-badge">' + esc(t('session.restored')) + '</span>' : '')
        + '</button>').join('');
}

function visiblePackets() {
    return state.selectedRunId
        ? state.packets.filter(packet => packet.runId === state.selectedRunId)
        : state.packets;
}

async function selectRun(runId) {
    state.selectedRunId = runId || null;
    state.selectedId = null;
    renderRunList();
    el.tbody.innerHTML = '';
    renderDetail(null);
    closeDetail();
    await loadRecent();
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
    const validation = validatePacket(packet);
    if (validation.results.length) {
        const invalid = validation.results.some(r => r.errors.length);
        row.classList.add(invalid ? 'pkt-invalid' : 'pkt-valid');
        const shield = document.createElement('span');
        shield.className = 'valid-shield ' + (invalid ? 'invalid' : 'valid');
        shield.textContent = invalid ? '✗' : '✓';
        shield.title = invalid ? t('detail.schemaInvalid') : t('detail.schemaValid');
        row.querySelector('.c-valid').appendChild(shield);
    }
    const timeCell = row.querySelector('.c-time');
    timeCell.textContent = formatTime(packet.timestamp);
    timeCell.title = packet.timestamp || '';
    const typeCell = row.querySelector('.c-type');
    typeCell.innerHTML = '<span class="type-pill">' + esc(packet.type) + '</span>';
    typeCell.classList.add(typeClass(packet.type));
    const methodCell = row.querySelector('.c-method');
    methodCell.innerHTML = '<span class="method-pill">' + esc(packet.method) + '</span>';
    const methodCls = methodClass(packet.method);
    if (methodCls) methodCell.classList.add(methodCls);
    const urlCell = row.querySelector('.c-url');
    urlCell.textContent = packet.url || packet.label;
    urlCell.title = packet.url || packet.label || '';
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
    if (packet.id === state.selectedId) row.classList.add('selected');
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
    if (!packet) {
        teardownDetailScrollSpy();
        el.detail.innerHTML = '<div class="empty"><span>' + esc(t('detail.emptyTitle')) + '</span><strong>'
            + esc(t('detail.emptyBody')) + '</strong><em>' + esc(t('detail.emptyHint')) + '</em></div>';
        return;
    }
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
        + '<div class="meta">' + esc(packet.threadName)
        + (packet.bodyTruncated ? ' &middot; <span style="color:var(--warn)">' + esc(t('detail.bodyTruncated')) + '</span>' : '')
        + '</div>'
        + '</div>'
        + '<div class="detail-metrics">'
        + detailMetric(t('detail.metric.elapsed'), packet.elapsedMs, 'ms')
        + detailMetric(t('detail.metric.connect'), packet.connectMs, 'ms')
        + detailMetric(t('detail.metric.latency'), packet.latencyMs, 'ms')
        + detailMetric(t('detail.metric.response'), packet.responseBody ? humanSize(packet.responseBody.length) : '0 B', '')
        + '</div>'
        + '<div class="detail-tabs" role="navigation" aria-label="' + esc(t('detail.sectionsAria')) + '">'
        + '<button type="button" class="detail-tab active" data-jump="overview">' + esc(t('detail.tab.overview')) + '</button>'
        + '<button type="button" class="detail-tab" data-jump="headers">' + esc(t('detail.tab.headers')) + '</button>'
        + '<button type="button" class="detail-tab" data-jump="bodies">' + esc(t('detail.tab.bodies')) + '</button>'
        + '<button type="button" class="detail-tab" data-jump="raw">' + esc(t('detail.tab.raw')) + '</button>'
        + '</div>'
        + '<section class="detail-section" id="detail-overview"><h3>' + esc(t('detail.overview')) + '</h3>'
        + '<div class="overview-grid">'
        + overviewPill(t('detail.pill.method'), 'c-method ' + methodClass(packet.method), 'method-pill', packet.method)
        + overviewPill(t('detail.pill.status'), 'c-status ' + statusClass(packet.status), 'status-pill', packet.status || (packet.success ? 'OK' : 'ERR'))
        + overviewPill(t('detail.pill.type'), 'c-type ' + typeClass(packet.type), 'type-pill', packet.type)
        + overviewPill(t('detail.pill.thread'), 'thread-value', '', packet.threadName || '-')
        + '</div></section>'
        + validationSection(validation)
        + dashboardSectionPlaceholder()
        + '<section class="detail-section" id="detail-headers">' + sectionHeaders(t('detail.requestHeaders'), packet.requestHeaders, 'outgoing')
        + sectionHeaders(t('detail.responseHeaders'), packet.responseHeaders, 'incoming') + '</section>'
        + '<section class="detail-section" id="detail-bodies">' + bodyBlock(packet.id, reqTitle(packet), packet.requestBody, false, false, 'request', validation.paths.request, null)
        + bodyBlock(packet.id, respTitle(packet), packet.responseBody, packet.bodyBinary, packet.bodyTruncated, 'response', validation.paths.response, bodyValidationState(validation, 'response')) + '</section>'
        + (packet.failureMessage
            ? '<section class="detail-section" id="detail-raw"><h3>' + esc(t('detail.failure')) + '</h3><pre>' + esc(packet.failureMessage) + '</pre></section>'
            : '<section class="detail-section" id="detail-raw"><h3>' + esc(t('detail.raw')) + '</h3><pre>' + esc(JSON.stringify(packet, null, 2)) + '</pre></section>')
        + '</div>';
    mountDetailBodies();
    mountDashboardLinks(packet);
    mountDetailScrollSpy();
}

function detailMetric(label, value, suffix) {
    return '<div class="metric"><span>' + esc(label) + '</span><strong>' + esc(value == null ? '-' : value) + '</strong><em>' + suffix + '</em></div>';
}

function overviewPill(label, valueClass, pillClass, value) {
    const content = pillClass
        ? '<span class="' + pillClass + '">' + esc(value) + '</span>'
        : esc(value);
    return '<div><span>' + esc(label) + '</span><strong class="' + esc(valueClass.trim()) + '">' + content + '</strong></div>';
}

function validationSection(validation) {
    if (!validation.results.length) {
        return '<section class="detail-section validation-section" id="detail-validation"><h3>' + esc(t('detail.validation')) + '</h3>'
            + '<div class="validation-empty">' + esc(t('detail.validationEmpty')) + '</div></section>';
    }
    const rules = validation.results.map(result => {
        const errors = result.errors.length
            ? '<ul>' + result.errors.map(e => '<li><code>' + esc(e.path) + '</code> ' + esc(e.message) + '</li>').join('') + '</ul>'
            : '<div class="validation-ok">' + esc(t('detail.validationOk')) + '</div>';
        return '<div class="validation-rule ' + (result.errors.length ? 'invalid' : 'valid') + '">'
            + '<div class="validation-head"><span class="validation-target">' + esc(result.target) + '</span>'
            + '<code>' + esc(result.pattern) + '</code>'
            + '<strong>' + esc(result.errors.length ? t('detail.invalid') : t('detail.valid')) + '</strong></div>'
            + errors
            + '</div>';
    }).join('');
    return '<section class="detail-section validation-section" id="detail-validation"><h3>' + esc(t('detail.validation')) + '</h3>' + rules + '</section>';
}

function dashboardSectionPlaceholder() {
    return '<section class="detail-section" id="detail-dashboards"><h3>' + esc(t('detail.dashboards')) + '</h3>'
        + '<div id="detail-dashboards-list" class="dash-list"></div></section>';
}

function isCookieHeader(name) {
    const n = String(name).toLowerCase();
    return n === 'cookie' || n === 'set-cookie';
}

// Split a Cookie / Set-Cookie value into name/value pairs. For Set-Cookie the first segment is the
// cookie itself and the remaining segments are attributes (Path, HttpOnly, ...). Attribute-only
// segments (no '=') carry an empty value. Pure — safe to unit-test.
function parseCookie(headerName, value) {
    const segments = String(value).split(';').map(s => s.trim()).filter(Boolean);
    const toPair = (seg) => {
        const eq = seg.indexOf('=');
        return eq < 0 ? { name: seg, value: '' } : { name: seg.slice(0, eq).trim(), value: seg.slice(eq + 1).trim() };
    };
    if (headerName.toLowerCase() === 'set-cookie') {
        return { pairs: segments.length ? [toPair(segments[0])] : [], attributes: segments.slice(1).map(toPair) };
    }
    return { pairs: segments.map(toPair), attributes: [] };
}

function cookieTableHtml(headerName, value) {
    const parsed = parseCookie(headerName, value);
    const row = (p, cls) => '<tr class="' + cls + '"><td class="ck-name">' + esc(p.name)
        + '</td><td class="ck-value">' + esc(p.value) + '</td></tr>';
    const pairs = parsed.pairs.map(p => row(p, 'ck-pair')).join('');
    const attrs = parsed.attributes.map(p => row(p, 'ck-attr')).join('');
    return '<table class="cookie-table">' + pairs + attrs + '</table>';
}

function sectionHeaders(title, headers, direction) {
    if (!headers || Object.keys(headers).length === 0) return '';
    let rows = '';
    for (const [k, v] of Object.entries(headers)) {
        let rendered;
        if (isCookieHeader(k)) {
            rendered = cookieTableHtml(k, v);
        } else {
            const tpl = traceLinkFor(k);
            const href = tpl ? buildTraceUrl(tpl, v) : null;
            rendered = href
                ? '<a class="trace-link" href="' + esc(href) + '" target="_blank" rel="noopener noreferrer">' + esc(v) + '</a>'
                : esc(v);
        }
        rows += '<tr><td class="k">' + esc(k) + '</td><td class="v">' + rendered + '</td></tr>';
    }
    const kind = direction === 'incoming' ? 'incoming' : 'outgoing';
    const label = kind === 'incoming' ? 'incoming' : 'outgoing';
    return '<div class="headers-card ' + kind + '"><h3>' + esc(title)
        + '<span class="headers-direction">' + label + '</span></h3>'
        + '<table class="kv headers-table">' + rows + '</table></div>';
}

// Detail bodies: valid JSON/HTML is rendered into a read-only CodeMirror (highlight + pretty-print);
// "Raw" falls back to a plain <pre> of the original bytes. Views are mounted after innerHTML is set.
let detailBodies = [];  // {title, body, code, mode, size, raw} per rendered body block
let detailSpy = null;   // IntersectionObserver that syncs nav tabs to scroll position
const DETAIL_VIEWER_MAX = '55vh';  // tall bodies scroll inside this instead of growing the drawer

function bodyBlock(packetId, title, body, binary, truncated, target, validationErrors, validationState) {
    // An absent section is indistinguishable from an uncaptured one, so an empty body still gets
    // its labelled section — just with a placeholder instead of a viewer, and no toggle/expand
    // controls (there is nothing to reformat or enlarge).
    if (body == null || body === '') {
        return '<div class="body-block body-block-empty"><h3>' + title
            + '<span class="body-size">0 B</span></h3>'
            + '<div class="body-empty">' + esc(t('detail.emptyBody')) + '</div></div>';
    }
    let note = '<span class="body-size">' + humanSize(body.length) + '</span>'
        + (truncated ? esc(t('detail.truncated')) : '');
    let code = body;
    let mode = null;
    if (!binary) {
        const lang = detectLangCached(packetId, target, body);
        if (lang) { code = lang.code; mode = lang.mode; }
    } else {
        note += esc(t('detail.binaryHex'));
    }
    const i = detailBodies.push({ title: stripTags(title), body, code, mode, size: body.length, raw: false, target, validationErrors: validationErrors || [], validationState: validationState || null }) - 1;
    // The raw/formatted toggle only makes sense when there is a formatted form (highlighted mode).
    const toggle = mode ? viewToggleHtml(i) : '';
    const expand = ' <button type="button" class="body-expand" data-body="' + i
        + '" title="' + esc(t('detail.expandTitle')) + '" aria-label="' + esc(t('detail.expandTitle')) + '">⤢</button>';
    return '<div class="body-block" data-body="' + i + '"><h3>' + title + note + toggle + expand + '</h3>'
        + '<div class="cm-host" id="body-view-' + i + '"></div></div>';
}

function viewToggleHtml(i) {
    return ' <span class="body-toggle" role="group" aria-label="' + esc(t('detail.viewModeAria')) + '">'
        + '<button type="button" class="bt active" data-body="' + i + '" data-raw="0">' + esc(t('detail.formatted')) + '</button>'
        + '<button type="button" class="bt" data-body="' + i + '" data-raw="1">' + esc(t('detail.rawView')) + '</button>'
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
// as-is; anything else returns null so the caller falls back to a plain <pre>. Bodies over
// DETECT_LANG_MAX_CHARS skip reformatting entirely and fall back to raw display.
const DETECT_LANG_MAX_CHARS = 200 * 1024;

function detectLangCached(packetId, target, body) {
    const entry = getPacketCacheEntry(packetId);
    if (Object.prototype.hasOwnProperty.call(entry.lang, target)) {
        return entry.lang[target];
    }
    const lang = detectLang(body);
    entry.lang[target] = lang;
    return lang;
}

function detectLang(body) {
    if (body.length > DETECT_LANG_MAX_CHARS) return null;
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
    container.classList.remove('cm-body-valid', 'cm-body-invalid');
    if (b.validationState) container.classList.add('cm-body-' + b.validationState);
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
        marked.add(error.path);
        const pos = findJsonPathPosition(b.code, error.path);
        if (pos) {
            cm.markText(pos.from, pos.to, { className: 'cm-schema-error', title: error.message });
            continue;
        }
        // The field itself isn't in the body (a missing required field) — there's nothing to
        // underline, so drop a marker on the parent object's line instead.
        markMissingField(cm, b.code, error);
    }
}

function markMissingField(cm, code, error) {
    const parts = jsonPathParts(error.path);
    const key = parts[parts.length - 1];
    if (typeof key !== 'string') return;
    const parentPath = error.path.slice(0, error.path.length - ('.' + key).length);
    const parentPos = findJsonPathPosition(code, parentPath);
    const line = parentPos ? parentPos.from.line : 0;
    const marker = document.createElement('span');
    marker.className = 'cm-schema-missing';
    marker.textContent = t('detail.missingKey', { key: key });
    marker.title = error.message;
    cm.addLineWidget(line, marker, { coverGutter: false, noHScroll: true });
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
        ? '<span class="ws-dir up">↑</span>' + esc(t('detail.sentFrame'))
        : esc(t('detail.requestBody'));
}
function respTitle(p) {
    return p.type === 'WEBSOCKET'
        ? '<span class="ws-dir down">↓</span>' + esc(t('detail.receivedFrame'))
        : esc(t('detail.responseBody'));
}

function esc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ---- dashboard links ----
const DASHBOARD_LINKS_KEY = 'listview.dashboardLinks';
const TRACE_LINKS_KEY = 'listview.traceLinks';
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
        __reqHeaders: {}, __respHeaders: {},
    };
    if (packet) {
        const u = dashboardUrlParts(packet.url || '');
        Object.assign(vars, {
            url: packet.url || '', host: u.host, port: u.port, scheme: u.scheme,
            path: u.path, query: u.query,
            method: packet.method || '', status: packet.status || '',
            label: packet.label || '', type: packet.type || '', thread: packet.threadName || '',
            timestamp: packet.timestamp || '', epochMs: center, epochSec: Math.floor(center / 1000),
            __reqHeaders: packet.requestHeaders || {}, __respHeaders: packet.responseHeaders || {},
        });
    }
    return vars;
}

function lookupHeader(map, name) {
    if (!map || typeof map !== 'object') return '';
    const want = String(name).toLowerCase();
    for (const key of Object.keys(map)) {
        if (key.toLowerCase() === want) return map[key] == null ? '' : String(map[key]);
    }
    return '';
}

function applyTemplate(template, vars) {
    return String(template || '').replace(/\{(\w+)(?::([^}]*))?\}/g, (m, name, arg) => {
        if (arg !== undefined && (name === 'reqHeader' || name === 'respHeader')) {
            const map = name === 'reqHeader' ? vars.__reqHeaders : vars.__respHeaders;
            return encodeURIComponent(lookupHeader(map, arg));
        }
        if (name.startsWith('__')) return m;
        return Object.prototype.hasOwnProperty.call(vars, name)
            ? encodeURIComponent(vars[name]) : m;
    });
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
        source: l.source || 'local',
        origin: l.origin || null,
    };
}

function loadDashboardLinks(render = true) {
    try {
        const raw = localStorage.getItem(DASHBOARD_LINKS_KEY);
        const links = raw ? JSON.parse(raw) : [];
        state.localDashboardLinks = Array.isArray(links)
            ? links.filter(l => l && l.name && l.urlTemplate).map(l => normalizeLink({ ...l, source: 'local' }))
            : [];
    } catch (e) {
        state.localDashboardLinks = [];
    }
    computeEffectiveDashboardLinks();
    if (render) refreshDashboardViews();
}

function saveDashboardLinks() {
    localStorage.setItem(DASHBOARD_LINKS_KEY, JSON.stringify(state.localDashboardLinks));
    computeEffectiveDashboardLinks();
    refreshDashboardViews();
    rerenderSelectedDetail();
}

function refreshDashboardViews() {
    renderDashboardList();
    renderGlobalLinks();
}

function dashboardBadgeLabel(link) {
    return link.origin === 'local' ? t('badge.localFile') : t('badge.server');
}

function renderDashboardList() {
    if (!el.dashList) return;
    updateSettingsCounts();
    const rows = state.serverDashboardLinks.concat(state.localDashboardLinks);
    if (!rows.length) {
        el.dashList.innerHTML = '<div class="schema-empty">' + esc(t('list.noDashboards')) + '</div>';
        return;
    }
    el.dashList.innerHTML = rows.map(link => {
        const isServer = link.source === 'server';
        const disabled = isServer && isServerItemDisabled('dashboard', link.id);
        const classes = ['schema-rule'];
        if (isServer) classes.push('server');
        if (disabled) classes.push('disabled');
        if (link.id === state.editingDashLinkId) classes.push('editing');
        return '<div class="' + classes.join(' ') + '" data-id="' + esc(link.id) + '">'
            + dashboardSystemIconHtml(link.system)
            + '<span class="validation-target">' + esc(link.scope) + '</span>'
            + (isServer ? '<span class="validation-target">' + esc(dashboardBadgeLabel(link)) + '</span>' : '')
            + '<strong>' + esc(link.name) + '</strong>'
            + '<code class="template-code">' + renderTemplatePreview(link.urlTemplate) + '</code>'
            + (isServer
                ? '<button type="button" class="mini dash-toggle-server" data-id="' + esc(link.id) + '">' + esc(disabled ? t('list.enable') : t('list.disable')) + '</button>'
                : '<button type="button" class="mini dash-edit" data-id="' + esc(link.id) + '">' + esc(t('list.edit')) + '</button>'
                    + '<button type="button" class="mini dash-delete" data-id="' + esc(link.id) + '">' + esc(t('list.delete')) + '</button>')
            + '</div>';
    }).join('');
}

function setDashMessage(text, ok) {
    el.dashMessage.textContent = text;
    el.dashMessage.className = 'schema-message' + (ok ? ' ok' : '');
}

function startEditDashboardLink(id) {
    const link = state.localDashboardLinks.find(l => l.id === id);
    if (!link) return;
    state.editingDashLinkId = id;
    el.dashName.value = link.name;
    el.dashSystem.value = link.system;
    el.dashScope.value = link.scope;
    el.dashUrl.value = link.urlTemplate;
    el.dashMatch.value = link.match || '';
    updateDashboardSystemPreview();
    updateDashboardTemplatePreview();
    el.dashSave.textContent = t('list.update');
    el.dashCancelEdit.hidden = false;
    setDashMessage(t('msg.editing', { name: link.name }), true);
    renderDashboardList();
    el.dashName.focus();
}

function cancelEditDashboardLink() {
    state.editingDashLinkId = null;
    el.dashName.value = '';
    el.dashUrl.value = '';
    el.dashMatch.value = '';
    updateDashboardTemplatePreview();
    el.dashSave.textContent = t('settings.save');
    el.dashCancelEdit.hidden = true;
    setDashMessage('', true);
    renderDashboardList();
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
    if (!links.length) { host.innerHTML = '<div class="dash-empty">' + esc(t('detail.noDashboards')) + '</div>'; return; }
    links.forEach(l => { const a = dashboardAnchor(l, packet); if (a) host.appendChild(a); });
}

function loadTraceLinks(render = true) {
    let stored = null;
    try {
        const raw = localStorage.getItem(TRACE_LINKS_KEY);
        if (raw !== null) stored = JSON.parse(raw);
    } catch (e) { stored = null; }
    if (stored === null) {
        // seed a sensible default the user can edit (real SignalFX realm/org filled in later).
        state.traceLinks = [{ header: 'x-b3-traceid', urlTemplate: 'https://app.signalfx.com/#/apm/traces/{value}' }];
    } else {
        state.traceLinks = Array.isArray(stored)
            ? stored.filter(t => t && t.header && t.urlTemplate) : [];
    }
    if (render) renderTraceLinks();
}

function saveTraceLinks() {
    localStorage.setItem(TRACE_LINKS_KEY, JSON.stringify(state.traceLinks));
    renderTraceLinks();
    rerenderSelectedDetail();
}

function renderTraceLinks() {
    if (!el.traceList) return;
    if (el.traceCount) el.traceCount.textContent = String(state.traceLinks.length);
    if (!state.traceLinks.length) {
        el.traceList.innerHTML = '<div class="schema-empty">' + esc(t('list.noTraceLinks')) + '</div>';
        return;
    }
    el.traceList.innerHTML = state.traceLinks.map((tl, i) =>
        '<div class="schema-rule' + (i === state.editingTraceIndex ? ' editing' : '') + '" data-trace="' + i + '">'
        + '<span class="validation-target">' + esc(tl.header) + '</span>'
        + '<code>' + esc(tl.urlTemplate) + '</code>'
        + '<button type="button" class="mini trace-edit" data-trace="' + i + '">' + esc(t('list.edit')) + '</button>'
        + '<button type="button" class="mini trace-delete" data-trace="' + i + '">' + esc(t('list.delete')) + '</button>'
        + '</div>').join('');
}

function setTraceMessage(text, ok) {
    el.traceMessage.textContent = text;
    el.traceMessage.className = 'schema-message' + (ok ? ' ok' : '');
}

function startEditTraceLink(index) {
    const link = state.traceLinks[index];
    if (!link) return;
    state.editingTraceIndex = index;
    el.traceHeader.value = link.header;
    el.traceUrl.value = link.urlTemplate;
    el.traceSave.textContent = t('list.update');
    el.traceCancelEdit.hidden = false;
    setTraceMessage(t('msg.editing', { name: link.header }), true);
    renderTraceLinks();
    el.traceHeader.focus();
}

function cancelEditTraceLink() {
    state.editingTraceIndex = null;
    el.traceHeader.value = '';
    el.traceUrl.value = '';
    el.traceSave.textContent = t('settings.save');
    el.traceCancelEdit.hidden = true;
    setTraceMessage('', true);
    renderTraceLinks();
}

function traceLinkFor(headerName) {
    const n = String(headerName).toLowerCase();
    const hit = state.traceLinks.find(t => t.header.toLowerCase() === n);
    return hit ? hit.urlTemplate : null;
}

function buildTraceUrl(template, value) {
    const href = String(template).replace(/\{value\}/g, encodeURIComponent(value));
    return /^https?:\/\//i.test(href) ? href : null;
}

function loadSettingsTab() {
    const stored = localStorage.getItem(SETTINGS_TAB_KEY);
    setSettingsTab(['dashboards', 'trace'].includes(stored) ? stored : 'schema', false);
}

function setSettingsTab(tab, persist = true) {
    state.settingsTab = ['dashboards', 'trace'].includes(tab) ? tab : 'schema';
    if (persist) localStorage.setItem(SETTINGS_TAB_KEY, state.settingsTab);
    [el.schemaPanel, el.dashboardPanel, document.getElementById('trace-panel')].forEach(panel => {
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
        if (state.settingsTab === 'trace') return 'trace-panel';
        return 'schema-panel';
    }
});

function focusActiveSettingsField() {
    if (state.settingsTab === 'dashboards') {
        el.dashName.focus();
    } else if (state.settingsTab === 'trace') {
        el.traceHeader.focus();
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
    setActiveLanguage(selectedLanguage);
    document.documentElement.lang = selectedLanguage;
    el.languageOptions.forEach(option => {
        const selected = option.dataset.language === selectedLanguage;
        option.classList.toggle('active', selected);
        option.setAttribute('aria-pressed', selected ? 'true' : 'false');
    });
    applyTranslations(document);
    retranslateRenderedContent();
}

function retranslateRenderedContent() {
    renderRunList();
    renderSchemaRules();
    renderDashboardList();
    renderTraceLinks();
    renderGlobalLinks();
    renderActiveFilters();
    updateSortIndicators();
    rebuildList();
    rerenderSelectedDetail();
}

function updateSettingsCounts() {
    if (el.schemaCount) el.schemaCount.textContent = String(state.schemaRules.length);
    if (el.dashboardCount) el.dashboardCount.textContent = String(state.dashboardLinks.length);
}

function setStatus(text, kind, runId) {
    el.status.textContent = runId ? text + ' · #' + shortId(runId) : text;
    el.status.title = runId || '';
    el.status.style.color = kind === 'ok' ? 'var(--ok)' : kind === 'err' ? 'var(--err)' : kind === 'run' ? 'var(--accent)' : 'var(--muted)';
}

function loadSchemaRules(render = true) {
    try {
        const raw = localStorage.getItem(SCHEMA_RULES_KEY);
        const rules = raw ? JSON.parse(raw) : [];
        state.localSchemaRules = Array.isArray(rules)
            ? rules.filter(r => r && r.pattern && r.target && r.schema).map(r => normalizeSchemaRule(r, 'local'))
            : [];
    } catch (e) {
        state.localSchemaRules = [];
    }
    computeEffectiveSchemaRules();
    if (render) renderSchemaRules();
}

function saveSchemaRules() {
    localStorage.setItem(SCHEMA_RULES_KEY, JSON.stringify(state.localSchemaRules));
    refreshSchemaViews();
}

function schemaBadgeLabel(rule) {
    if (rule.origin === 'local') return t('badge.localFile');
    return rule.source === 'url' ? t('badge.url') : t('badge.server');
}

function renderSchemaRules() {
    renderUrlSchemaSources();
    if (!el.schemaList) return;
    updateSettingsCounts();
    const rows = allRemoteSchemaRules().map(effectiveSchemaRule).concat(state.localSchemaRules);
    if (!rows.length) {
        el.schemaList.innerHTML = '<div class="schema-empty">' + esc(t('list.noRules')) + '</div>';
        return;
    }
    el.schemaList.innerHTML = rows.map(rule => {
        const isRemote = rule.source !== 'local';
        const disabled = isRemote && isServerItemDisabled('schema', rule.id);
        const classes = ['schema-rule'];
        if (isRemote) classes.push('server');
        if (disabled) classes.push('disabled');
        if (rule.overridden) classes.push('overridden');
        if (rule.id === state.editingSchemaRuleId) classes.push('editing');
        return '<div class="' + classes.join(' ') + '" data-id="' + esc(rule.id) + '">'
            + '<span class="validation-target">' + esc(rule.target) + '</span>'
            + (isRemote ? '<span class="validation-target">' + esc(schemaBadgeLabel(rule)) + '</span>' : '')
            + (rule.overridden ? '<span class="validation-target">' + esc(t('badge.edited')) + '</span>' : '')
            + (rule.name ? '<strong>' + esc(rule.name) + '</strong>' : '')
            + '<code>' + esc(rule.pattern) + '</code>'
            + '<button type="button" class="mini schema-edit" data-id="' + esc(rule.id) + '">' + esc(t('list.edit')) + '</button>'
            + (rule.overridden ? '<button type="button" class="mini schema-reset" data-id="' + esc(rule.id) + '">' + esc(t('facets.reset')) + '</button>' : '')
            + (isRemote
                ? '<button type="button" class="mini schema-toggle-server" data-id="' + esc(rule.id) + '">' + esc(disabled ? t('list.enable') : t('list.disable')) + '</button>'
                : '<button type="button" class="mini schema-delete" data-id="' + esc(rule.id) + '">' + esc(t('list.delete')) + '</button>')
            + '</div>';
    }).join('');
}

function setSchemaMessage(text, ok) {
    el.schemaMessage.textContent = text;
    el.schemaMessage.className = 'schema-message' + (ok ? ' ok' : '');
}

function startEditSchemaRule(id) {
    const rule = findSchemaRuleById(id);
    if (!rule) return;
    state.editingSchemaRuleId = id;
    el.schemaName.value = rule.name || '';
    el.schemaPattern.value = rule.pattern;
    el.schemaTarget.value = rule.target;
    el.schemaJson.value = JSON.stringify(rule.schema, null, 2);
    el.schemaSave.textContent = t('list.update');
    el.schemaCancelEdit.hidden = false;
    setSchemaMessage(t('msg.editing', { name: rule.name || rule.pattern }), true);
    renderSchemaRules();
    el.schemaPattern.focus();
}

function cancelEditSchemaRule() {
    state.editingSchemaRuleId = null;
    el.schemaName.value = '';
    el.schemaPattern.value = '';
    el.schemaJson.value = '';
    el.schemaSave.textContent = t('settings.save');
    el.schemaCancelEdit.hidden = true;
    setSchemaMessage('', true);
    renderSchemaRules();
}

function rerenderSelectedDetail() {
    if (state.selectedId == null) return;
    renderDetail(state.packets.find(p => p.id === state.selectedId));
}

function bodyValidationState(validation, target) {
    const matched = validation.results.filter(r => r.target === target);
    if (!matched.length) return null;
    return matched.some(r => r.errors.length) ? 'invalid' : 'valid';
}

function getPacketCacheEntry(packetId) {
    let entry = state.packetCache.get(packetId);
    if (!entry) {
        entry = { rulesVersion: -1, validation: null, lang: {} };
        state.packetCache.set(packetId, entry);
    }
    return entry;
}

function validatePacket(packet) {
    const entry = getPacketCacheEntry(packet.id);
    if (entry.validation && entry.rulesVersion === state.schemaRulesVersion) {
        return entry.validation;
    }
    entry.validation = computeValidation(packet);
    entry.rulesVersion = state.schemaRulesVersion;
    return entry.validation;
}

function computeValidation(packet) {
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
    await afterStart(await r.json());
    setRunPanel(false);
});

el.stop.addEventListener('click', async () => {
    if (!state.activeRunId) return;
    await fetch('/api/runs/' + state.activeRunId + '/stop', { method: 'POST' });
    setStatus(t('status.stopping'), 'run', state.activeRunId);
});

el.clear.addEventListener('click', async () => {
    if (!window.confirm(t('msg.confirmClear'))) return;
    state.packets = [];
    state.runs = [];
    state.seen = new Set();
    state.selectedId = null;
    state.selectedRunId = null;
    state.activeRunId = null;
    state.maxElapsed = 1;
    state.packetCache.clear();
    el.tbody.innerHTML = '';
    updateCount(0);
    renderRunList();
    renderDetail(null);
    closeDetail();
    try { await fetch('/api/packets', { method: 'DELETE' }); } catch (e) { /* ignore */ }
});

if (el.runAll) {
    el.runAll.addEventListener('click', () => { selectRun(null); });
}
if (el.runList) {
    el.runList.addEventListener('click', (ev) => {
        const bt = ev.target.closest('.run-chip[data-run-id]');
        if (!bt) return;
        selectRun(bt.dataset.runId);
    });
}

function sessionMessage(text, kind) {
    if (!el.sessionMessage) return;
    el.sessionMessage.textContent = text;
    el.sessionMessage.classList.toggle('ok', kind === 'ok');
}

function wordForms(ru, en) {
    return activeLanguage() === 'ru' ? ru : en;
}

async function saveSession() {
    try {
        const r = await fetch('/api/session/export');
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const blob = await r.blob();
        const disposition = r.headers.get('Content-Disposition') || '';
        const match = disposition.match(/filename="([^"]+)"/);
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = match ? match[1] : 'wireglass-session.json';
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
        sessionMessage(t('session.saved'), 'ok');
    } catch (e) {
        sessionMessage(t('session.exportFailed', { reason: e.message }), 'err');
    }
}

async function loadSessionFile(file) {
    try {
        const text = await file.text();
        const r = await fetch('/api/session/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: text
        });
        const payload = await r.json();
        if (!r.ok) throw new Error(payload && payload.message ? payload.message : 'HTTP ' + r.status);
        await loadRuns();
        await loadRecent();
        if (payload.importedPackets === 0) {
            sessionMessage(t('session.importedNothing'), 'ok');
            return;
        }
        sessionMessage(t('session.imported', {
            runs: payload.importedRuns,
            runWord: plural(payload.importedRuns,
                wordForms(['прогон', 'прогона', 'прогонов'], ['run', 'runs', 'runs'])),
            packets: payload.importedPackets,
            packetWord: plural(payload.importedPackets,
                wordForms(['пакет', 'пакета', 'пакетов'], ['packet', 'packets', 'packets']))
        }), 'ok');
    } catch (e) {
        sessionMessage(t('session.importFailed', { reason: e.message }), 'err');
    }
}

if (el.sessionSave) {
    el.sessionSave.addEventListener('click', saveSession);
}
if (el.sessionLoad && el.sessionFile) {
    el.sessionLoad.addEventListener('click', () => { el.sessionFile.click(); });
    el.sessionFile.addEventListener('change', async () => {
        const file = el.sessionFile.files && el.sessionFile.files[0];
        if (!file) return;
        await loadSessionFile(file);
        el.sessionFile.value = '';
    });
}

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
    if (!pattern) { setSchemaMessage(t('msg.patternRequired'), false); return; }
    let schema;
    try {
        schema = JSON.parse(el.schemaJson.value);
    } catch (e) {
        setSchemaMessage(t('msg.schemaJsonError', { error: e.message }), false);
        return;
    }
    if (!schema || typeof schema !== 'object' || Array.isArray(schema)) {
        setSchemaMessage(t('msg.schemaNotObject'), false);
        return;
    }
    const name = el.schemaName.value.trim();
    const target = el.schemaTarget.value === 'request' ? 'request' : 'response';
    const editingId = state.editingSchemaRuleId;
    if (editingId) {
        if (state.localSchemaRules.some(r => r.id === editingId)) {
            state.localSchemaRules = state.localSchemaRules.map(r =>
                r.id === editingId ? normalizeSchemaRule({ id: editingId, name, pattern, target, schema }, 'local') : r);
            saveSchemaRules();
        } else {
            // server/url-sourced rule: the edit becomes a local override, not a mutation of the
            // cached remote copy, so refreshing/reloading that source can never silently drop it.
            state.schemaOverrides[editingId] = { name, pattern, target, schema };
            saveSchemaOverrides();
            refreshSchemaViews();
        }
        state.editingSchemaRuleId = null;
        el.schemaSave.textContent = t('settings.save');
        el.schemaCancelEdit.hidden = true;
        setSchemaMessage(t('msg.updated'), true);
    } else {
        state.localSchemaRules.push(normalizeSchemaRule({ name, pattern, target, schema }, 'local'));
        setSchemaMessage(t('msg.savedShort'), true);
        saveSchemaRules();
    }
    el.schemaName.value = '';
    el.schemaPattern.value = '';
    el.schemaJson.value = '';
});
el.schemaCancelEdit.addEventListener('click', cancelEditSchemaRule);

el.schemaList.addEventListener('click', (ev) => {
    const toggle = ev.target.closest('.schema-toggle-server');
    if (toggle) {
        toggleServerItem('schema', toggle.dataset.id);
        setSchemaMessage(isServerItemDisabled('schema', toggle.dataset.id) ? t('msg.disabled') : t('msg.enabled'), true);
        return;
    }
    const resetBt = ev.target.closest('.schema-reset');
    if (resetBt) {
        delete state.schemaOverrides[resetBt.dataset.id];
        saveSchemaOverrides();
        if (state.editingSchemaRuleId === resetBt.dataset.id) cancelEditSchemaRule();
        refreshSchemaViews();
        setSchemaMessage(t('msg.resetToServer'), true);
        return;
    }
    const editBt = ev.target.closest('.schema-edit');
    if (editBt) { startEditSchemaRule(editBt.dataset.id); return; }
    const bt = ev.target.closest('.schema-delete');
    if (!bt) return;
    if (state.editingSchemaRuleId === bt.dataset.id) cancelEditSchemaRule();
    state.localSchemaRules = state.localSchemaRules.filter(rule => rule.id !== bt.dataset.id);
    setSchemaMessage(t('msg.deleted'), true);
    saveSchemaRules();
});

if (el.schemaRemoteLoad) {
    el.schemaRemoteLoad.addEventListener('click', async () => {
        const url = el.schemaRemoteUrl.value.trim();
        if (!url) { setSchemaRemoteMessage('URL is required', false); return; }
        if (!/^https?:\/\//i.test(url)) { setSchemaRemoteMessage('URL must be http(s)', false); return; }
        setSchemaRemoteMessage(t('msg.loading'), true);
        try {
            await loadSchemaRulesFromUrl(url);
            el.schemaRemoteUrl.value = '';
            setSchemaRemoteMessage(t('msg.loaded'), true);
        } catch (e) {
            setSchemaRemoteMessage(t('msg.loadFailed', { error: e.message }), false);
        }
    });
}
if (el.schemaRemoteSources) {
    el.schemaRemoteSources.addEventListener('click', async (ev) => {
        const refresh = ev.target.closest('.schema-source-refresh');
        if (refresh) {
            setSchemaRemoteMessage(t('msg.refreshing'), true);
            try {
                await loadSchemaRulesFromUrl(refresh.dataset.url);
                setSchemaRemoteMessage(t('msg.refreshed'), true);
            } catch (e) {
                setSchemaRemoteMessage(t('msg.refreshFailed', { error: e.message }), false);
            }
            return;
        }
        const remove = ev.target.closest('.schema-source-remove');
        if (remove) {
            state.urlSchemaSources = state.urlSchemaSources.filter(s => s.url !== remove.dataset.url);
            saveUrlSchemaSources();
            setSchemaRemoteMessage(t('msg.removed'), true);
        }
    });
}

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
    if (!name) { setDashMessage(t('msg.nameRequired'), false); return; }
    if (!urlTemplate) { setDashMessage(t('msg.urlTemplateRequired'), false); return; }
    const fields = {
        name, system: el.dashSystem.value, scope: el.dashScope.value,
        urlTemplate, match: el.dashMatch.value.trim(),
    };
    if (state.editingDashLinkId) {
        const id = state.editingDashLinkId;
        state.localDashboardLinks = state.localDashboardLinks.map(l =>
            l.id === id ? normalizeLink({ ...fields, id, source: 'local' }) : l);
        state.editingDashLinkId = null;
        el.dashSave.textContent = t('settings.save');
        el.dashCancelEdit.hidden = true;
        setDashMessage(t('msg.updated'), true);
    } else {
        state.localDashboardLinks.push(normalizeLink(fields));
        setDashMessage(t('msg.savedShort'), true);
    }
    el.dashName.value = '';
    el.dashUrl.value = '';
    el.dashMatch.value = '';
    saveDashboardLinks();
});
el.dashCancelEdit.addEventListener('click', cancelEditDashboardLink);
el.dashList.addEventListener('click', (ev) => {
    const toggle = ev.target.closest('.dash-toggle-server');
    if (toggle) {
        toggleServerItem('dashboard', toggle.dataset.id);
        setDashMessage(isServerItemDisabled('dashboard', toggle.dataset.id) ? t('msg.disabled') : t('msg.enabled'), true);
        return;
    }
    const editBt = ev.target.closest('.dash-edit');
    if (editBt) { startEditDashboardLink(editBt.dataset.id); return; }
    const bt = ev.target.closest('.dash-delete');
    if (!bt) return;
    if (state.editingDashLinkId === bt.dataset.id) cancelEditDashboardLink();
    state.localDashboardLinks = state.localDashboardLinks.filter(l => l.id !== bt.dataset.id);
    setDashMessage(t('msg.deleted'), true);
    saveDashboardLinks();
});

// ---- trace links panel ----
if (el.traceSave) {
    el.traceSave.addEventListener('click', () => {
        const header = el.traceHeader.value.trim();
        const urlTemplate = el.traceUrl.value.trim();
        if (!header || !urlTemplate) { setTraceMessage(t('msg.traceRequired'), false); return; }
        if (!buildTraceUrl(urlTemplate, 'x')) { setTraceMessage(t('msg.traceInvalid'), false); return; }
        if (state.editingTraceIndex !== null) {
            state.traceLinks[state.editingTraceIndex] = { header, urlTemplate };
            state.editingTraceIndex = null;
            el.traceSave.textContent = t('settings.save');
            el.traceCancelEdit.hidden = true;
            setTraceMessage(t('msg.updated'), true);
        } else {
            state.traceLinks.push({ header, urlTemplate });
            setTraceMessage(t('msg.saved'), true);
        }
        el.traceHeader.value = ''; el.traceUrl.value = '';
        saveTraceLinks();
    });
}
if (el.traceCancelEdit) {
    el.traceCancelEdit.addEventListener('click', cancelEditTraceLink);
}
if (el.traceList) {
    el.traceList.addEventListener('click', (ev) => {
        const editBt = ev.target.closest('.trace-edit');
        if (editBt) { startEditTraceLink(Number(editBt.dataset.trace)); return; }
        const btn = ev.target.closest('.trace-delete');
        if (!btn) return;
        // indices shift on removal, so any delete invalidates an in-progress edit
        if (state.editingTraceIndex !== null) cancelEditTraceLink();
        state.traceLinks.splice(Number(btn.dataset.trace), 1);
        saveTraceLinks();
    });
}

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
        catch (e) { el.bmSearch.classList.add('invalid'); el.bmCount.textContent = t('msg.badRegex'); return; }
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
    await afterStart(await r.json());
}

async function afterStart(status) {
    state.activeRunId = status.id;
    state.selectedRunId = status.id;
    for (const packet of state.packets) {
        if (!packet.runId) {
            packet.runId = status.id;
        }
    }
    await loadRuns();
    renderRunList();
    await loadRecent();
    setStatus(t('status.running'), 'run', status.id);
    el.stop.disabled = false;
    pollStatus(status.id);
}

function pollStatus(id) {
    const handle = setInterval(async () => {
        const r = await fetch('/api/runs/' + id);
        if (!r.ok) return;
        const s = await r.json();
        if (s.state === 'RUNNING') {
            setStatus(t('status.runningSamples', {
                n: s.capturedSamples,
                word: plural(s.capturedSamples, ['сэмпл', 'сэмпла', 'сэмплов'])
            }), 'run', id);
            return;
        }
        clearInterval(handle);
        if (state.activeRunId === id) state.activeRunId = null;
        await loadRuns();
        if (!state.selectedRunId || state.selectedRunId === id) {
            await loadRecent();
        }
        el.stop.disabled = true;
        if (s.state === 'FINISHED') setStatus(t('status.finished', {
            samples: s.capturedSamples,
            errors: s.errorSamples,
            sword: plural(s.capturedSamples, ['сэмпл', 'сэмпла', 'сэмплов']),
            eword: plural(s.errorSamples, ['ошибка', 'ошибки', 'ошибок'])
        }), 'ok', id);
        else if (s.state === 'FAILED') setStatus(t('status.failed'), 'err', id);
        else setStatus(s.state, 'muted', id);
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
    try { JSON.parse(v); el.bodyErr.textContent = t('msg.validJson'); el.bodyErr.className = 'body-err ok'; return true; }
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
    x.setAttribute('aria-label', t('msg.removeFilter', { tag: text }));
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
    let rows = visiblePackets().filter(matchesFilter);
    if (state.sort.key) {
        const dir = state.sort.dir === 'desc' ? -1 : 1;
        rows = rows.slice().sort((a, b) => comparePackets(a, b, state.sort.key) * dir);
    }
    for (const p of rows) appendRow(p);
    updateCount(rows.length);
    if (rows.length === 0 && visiblePackets().length > 0) showEmptyRow();
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
    const total = visiblePackets().length;
    const word = plural(total, ['пакет', 'пакета', 'пакетов']);
    el.count.textContent = (shown === total)
        ? t('count.packets', { n: total, word: word })
        : t('count.packetsFiltered', { shown: shown, total: total, word: word });
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
    td.append(t('list.noPackets'));
    const a = document.createElement('a');
    a.textContent = t('facets.reset');
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
loadDisabledServerItems();
loadSchemaOverrides();
loadUrlSchemaSources(false);
loadSchemaRules(false);
loadTraceLinks();
const storedDashWin = Number(localStorage.getItem(DASHBOARD_WINDOW_KEY));
if (el.dashWindow && storedDashWin > 0) el.dashWindow.value = String(Math.round(storedDashWin / 60000));
loadDashboardLinks(false);
loadServerConfig();
// open the SSE stream immediately so demo/manual runs are captured
ensureStream();
loadRuns();
// backfill any packets captured before the page was opened (e.g. an external jmeter-dsl test)
loadRecent();
