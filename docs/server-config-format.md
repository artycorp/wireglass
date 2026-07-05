# Server Config Format

Wireglass can load validation rules and dashboard links from a server-hosted JSON file (configured
once, backend-side, via `app.listview.remote-config-url`), or ad hoc from any `http(s)://` URL a
user pastes into the JSON Schema panel's "Load rules from URL" field (fetched client-side, subject
to that URL's CORS policy). Both use this same file format.

The file itself is never mutated by the app. Users can disable individual items locally without
deleting them, and — for schema rules only — edit one without touching the cached copy: editing
creates a local override keyed by the rule's `id`, shown with a yellow "edited" highlight, with a
"Reset" action to drop the override and fall back to the cached version. Reloading or refreshing a
source only replaces its own raw cache; it never discards overrides or disabled state, since both
are stored separately and keyed by `id`. Dashboard links loaded from a server config stay
disable-only (no override), matching the read-only contract for that panel.

## Example

```json
{
  "version": 1,
  "schemas": [
    {
      "id": "catalog-response-v1",
      "name": "Catalog response",
      "pattern": "/api/catalog/*",
      "target": "response",
      "schema": {
        "type": "object",
        "required": ["items"],
        "properties": {
          "items": { "type": "array" }
        }
      }
    }
  ],
  "dashboards": [
    {
      "id": "grafana-http-overview",
      "name": "HTTP overview",
      "system": "grafana",
      "scope": "packet",
      "urlTemplate": "http://localhost:3000/d/http?var-host={host}&from={fromMs}&to={toMs}",
      "match": ".*"
    }
  ]
}
```

## Fields

`version` must be `1`.

Each `schemas[]` item must contain:

- `id`: stable unique ID for disable state and future updates.
- `name`: human-readable label shown in the settings list.
- `pattern`: same URL pattern syntax as local JSON Schema rules.
- `target`: `request` or `response`.
- `schema`: JSON Schema object supported by the current in-browser validator.

Each `dashboards[]` item must contain:

- `id`: stable unique ID for disable state and future updates.
- `name`: link text shown in the UI.
- `system`: `grafana`, `splunk`, `signalfx`, or `custom`.
- `scope`: `packet` or `global`.
- `urlTemplate`: same template syntax as local dashboard links.
- `match`: optional packet URL matcher, used only for `packet` scope.

## Local file source

In addition to the server-hosted file above, Wireglass automatically reads a local file at
`~/.wireglass/dashboards.json` on every load — no flag required. If the file doesn't exist yet, it
is created with an empty template (`{"version":1,"schemas":[],"dashboards":[]}`) the first time
it's read, so a fresh install has something to edit immediately.

The local file uses the exact same format as the server-hosted file, for both `schemas[]` and
`dashboards[]`. If both a local file and `app.listview.remote-config-url` are configured, they are
merged by `id` — **the local file wins on id collisions.** This lets a single Wireglass install
have its own dashboards/schemas configured with zero setup, while still supporting a centralized
server config for a shared install.
