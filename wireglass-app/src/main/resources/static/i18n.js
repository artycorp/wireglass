const WG_I18N = {
    en: {
        'topbar.settings': 'Settings',
        'count.packets': '{n} packets',
        'topbar.newRun': 'New run',
        'topbar.idle': 'idle',
        'topbar.clear': 'Clear',
        'topbar.clearTitle': 'Clear captured packets',
        'form.url': 'url',
        'form.method': 'method',
        'form.threads': 'threads',
        'form.iterations': 'iterations',
        'form.contentType': 'content-type',
        'form.body': 'body',
        'form.format': 'Format',
        'form.formatTitle': 'Pretty-print JSON',
        'form.run': 'Run',
        'form.demo': 'Demo',
        'form.stop': 'Stop',
        'facets.allRuns': 'All runs',
        'facets.type': 'type',
        'facets.all': 'all',
        'facets.status': 'status',
        'facets.failed': '⚠ failed',
        'facets.method': 'method',
        'facets.any': 'any',
        'facets.latencyAtLeast': 'latency ≥',
        'facets.searchPlaceholder': 'search url / label...',
        'facets.bodies': 'bodies',
        'facets.bodiesTitle': 'also search request/response bodies',
        'facets.active': 'active:',
        'facets.reset': 'Reset',
        'table.index': '#',
        'table.validationTitle': 'Schema validation',
        'table.time': 'time',
        'table.type': 'type',
        'table.method': 'method',
        'table.url': 'url / label',
        'table.status': 'status',
        'table.latency': 'latency',
        'table.size': 'resp kB',
        'detail.emptyTitle': 'Pick a packet',
        'detail.emptyBody': 'Request and response details will appear here.',
        'detail.emptyHint': 'Use ↑/↓ or j/k to move through captured traffic.',
        'detail.closeTitle': 'Close (Esc)',
        'detail.showInspector': 'Show inspector'
    },
    ru: {
        'topbar.settings': 'Настройки',
        'count.packets': '{n} пакетов',
        'topbar.newRun': 'Новый прогон',
        'topbar.idle': 'ожидание',
        'topbar.clear': 'Очистить',
        'topbar.clearTitle': 'Очистить захваченные пакеты',
        'form.url': 'адрес',
        'form.method': 'метод',
        'form.threads': 'потоки',
        'form.iterations': 'итерации',
        'form.contentType': 'content-type',
        'form.body': 'тело',
        'form.format': 'Формат',
        'form.formatTitle': 'Форматировать JSON',
        'form.run': 'Запустить',
        'form.demo': 'Демо',
        'form.stop': 'Стоп',
        'facets.allRuns': 'Все прогоны',
        'facets.type': 'тип',
        'facets.all': 'все',
        'facets.status': 'статус',
        'facets.failed': '⚠ ошибки',
        'facets.method': 'метод',
        'facets.any': 'любой',
        'facets.latencyAtLeast': 'задержка ≥',
        'facets.searchPlaceholder': 'поиск по адресу / метке...',
        'facets.bodies': 'тела',
        'facets.bodiesTitle': 'искать также в телах запросов и ответов',
        'facets.active': 'активны:',
        'facets.reset': 'Сбросить',
        'table.index': '#',
        'table.validationTitle': 'Проверка по схеме',
        'table.time': 'время',
        'table.type': 'тип',
        'table.method': 'метод',
        'table.url': 'адрес / метка',
        'table.status': 'статус',
        'table.latency': 'задержка',
        'table.size': 'ответ, КБ',
        'detail.emptyTitle': 'Выберите пакет',
        'detail.emptyBody': 'Здесь появятся детали запроса и ответа.',
        'detail.emptyHint': 'Используйте ↑/↓ или j/k для перехода по трафику.',
        'detail.closeTitle': 'Закрыть (Esc)',
        'detail.showInspector': 'Показать инспектор'
    }
};

let currentLanguage = 'en';

function setActiveLanguage(lang) {
    currentLanguage = lang === 'ru' ? 'ru' : 'en';
}

function activeLanguage() {
    return currentLanguage;
}

function t(key, params) {
    const table = WG_I18N[currentLanguage] || WG_I18N.en;
    let text = table[key];
    if (text === undefined) text = WG_I18N.en[key];
    if (text === undefined) return key;
    if (!params) return text;
    return text.replace(/\{(\w+)\}/g, (whole, name) =>
        Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : whole);
}

function plural(n, forms) {
    if (activeLanguage() !== 'ru') return Math.abs(n) === 1 ? forms[0] : forms[1];
    const abs = Math.abs(n) % 100;
    const tail = abs % 10;
    if (abs > 10 && abs < 20) return forms[2];
    if (tail === 1) return forms[0];
    if (tail > 1 && tail < 5) return forms[1];
    return forms[2];
}

// Replaces the element's first NON-WHITESPACE text node, never textContent: nearly every label in
// this app is a text node sitting next to an element child (`<label>url <input/></label>`), and
// textContent would delete the input, the counter span, or the caret glyph along with the label.
// Whitespace-only nodes are skipped because indented markup puts a newline first, and the original
// node's surrounding spaces are preserved so inline labels keep their gap from the control.
function replaceLabel(element, text) {
    const target = Array.from(element.childNodes)
        .find(node => node.nodeType === 3 && node.nodeValue.trim() !== '');
    if (!target) {
        element.insertBefore(document.createTextNode(text), element.firstChild);
        return;
    }
    target.nodeValue = target.nodeValue.match(/^\s*/)[0] + text + target.nodeValue.match(/\s*$/)[0];
}

function applyAttribute(scope, datasetKey, dataAttribute, domAttribute) {
    scope.querySelectorAll('[' + dataAttribute + ']').forEach(element =>
        element.setAttribute(domAttribute, t(element.dataset[datasetKey])));
}

function applyTranslations(root) {
    const scope = root || document;
    scope.querySelectorAll('[data-i18n]').forEach(element =>
        replaceLabel(element, t(element.dataset.i18n)));
    applyAttribute(scope, 'i18nPlaceholder', 'data-i18n-placeholder', 'placeholder');
    applyAttribute(scope, 'i18nTitle', 'data-i18n-title', 'title');
    applyAttribute(scope, 'i18nAriaLabel', 'data-i18n-aria-label', 'aria-label');
}
