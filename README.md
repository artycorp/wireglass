# jmeter-web-listview

A web application that runs load tests on top of [jmeter-java-dsl](https://github.com/abstracta/jmeter-java-dsl)
and, in real time, shows the contents of every sent/received **HTTP/HTTPS** and **WebSocket** packet
(bodies, headers, timings), with an architectural foundation for **TCP** packet analysis.

It is a browser-based equivalent of the "Network" panel in browser devtools, powered by the JMeter engine.

## What it does

- Launches load tests from a simple form (URL, method, body, content-type, threads, iterations) or a one-click demo.
- Captures each sample's request/response bodies and headers via a custom JMeter listener.
- Streams packets to the browser live over **SSE** (`EventSource`).
- Renders a list view + detail pane (JSON pretty-print, text, hex preview for binary bodies).
- HTTPS bodies are captured decrypted — JMeter terminates TLS as the client, so no MITM is required.

## Layout

```
jmeter-web-listview/
├── jmeter/                  reference clones (read-only, for grep/inspection)
│   ├── jmeter-core/         apache/jmeter (shallow)
│   └── jmeter-dsl/          abstracta/jmeter-java-dsl (shallow)
├── web-listview/            the application
│   ├── pom.xml
│   └── src/main/java/com/artembelikov/listview/
│       ├── capture/         TrafficCaptureListener, PacketBus, TestRunService
│       ├── protocol/        PacketExtractor SPI + HTTP/WS/TCP extractors
│       ├── store/           PacketRepository (bounded ring buffer)
│       ├── web/             REST controllers + SSE
│       └── ...
├── PLAN.md
└── README.md
```

## Requirements

- Java 17
- Maven 3.6+

## How to run

> **Important:** JMeter's embedded engine resolves the on-disk jar path of each test-element class
> (`new File(codeSource.toURI())`). This requires dependencies to be **real files**, not nested inside
> a Spring Boot executable jar (`BOOT-INF/lib`). Therefore the app must run with an exploded classpath.

Two options:

```bash
cd web-listview

# option 1 (recommended for development)
mvn spring-boot:run

# option 2 — flat classpath via the helper script
./run.sh
```

Then open <http://localhost:8080>.

`java -jar target/web-listview-*.jar` will **not** work because of the limitation above.

## HTTP API

| Method | Path                       | Description                              |
|--------|----------------------------|------------------------------------------|
| POST   | `/api/runs`                | Start a run from a `RunRequest` JSON.    |
| POST   | `/api/runs/demo`           | Start the built-in demo plan.            |
| POST   | `/api/runs/{id}/stop`      | Stop a running test.                     |
| GET    | `/api/runs/{id}`           | Run status (state, counts, timing).      |
| GET    | `/api/traffic/stream`      | SSE stream of captured packets.          |
| GET    | `/api/packets?limit=200`   | Recent packets (post-run).               |
| GET    | `/api/packets/{id}`        | Single packet detail.                    |

`RunRequest`:
```json
{ "url": "https://httpbin.org/post", "method": "POST",
  "body": "{\"k\":\"v\"}", "contentType": "application/json",
  "threads": 2, "iterations": 3 }
```

## Extending protocols

Add a new `PacketExtractor` (Spring `@Component`) implementing `supports()` / `extract()` and return a
`PacketType`. Extractors are ordered HTTP → WebSocket → TCP (catch-all). This is the extension point for
a future full TCP analyzer (frame parsing / reassembly).

## Configuration

`src/main/resources/application.yml` exposes:
- `app.listview.ring-buffer-size` (default 5000) — in-memory packet history cap
- `app.listview.max-body-bytes` (default 256 KiB) — per-packet body truncation
