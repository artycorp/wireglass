# Server Config Format

Wireglass can load validation rules and dashboard links from a server-hosted JSON file. The file is read-only from the browser's point of view: users can disable individual server-provided items locally, but they cannot delete or edit them in the app.

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
