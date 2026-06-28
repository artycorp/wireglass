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
| DELETE | `/api/packets`             | Clear the in-memory packet history.      |
| WS     | `/api/ingest`              | Ingestion endpoint for external jmeter-java-dsl clients (each frame is a `CapturedPacket` JSON). |

`RunRequest`:
```json
{ "url": "https://httpbin.org/post", "method": "POST",
  "body": "{\"k\":\"v\"}", "contentType": "application/json",
  "threads": 2, "iterations": 3 }
```

## Extending protocols

Add a new `PacketExtractor` (Spring `@Component`) implementing `supports()` / `extract()` and returning a
`PacketType`. Extractors are ordered HTTP → WebSocket → TCP (catch-all). This is the extension point for
a future full TCP analyzer (frame parsing / reassembly).

## Use with any jmeter-java-dsl test plan

The web form is not limited to tests launched from its own UI. The `web-listview-client` module
provides a `TrafficCaptureClient` listener that streams every captured sample to the running form
over a WebSocket, so **any** standalone jmeter-java-dsl test plan (in its own process/JVM) shows up
live in the browser — exactly like a form-launched run.

```bash
cd web-listview-client && mvn install        # publish the client to your local Maven repo
cd ../web-listview && mvn spring-boot:run     # start the form (or ./run.sh)
```

Then add the listener to your test plan:

```java
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;

import com.artembelikov.listview.client.capture.TrafficCaptureClient;

testPlan(
    threadGroup(2, 3, httpSampler("https://httpbin.org/get")),
    new TrafficCaptureClient("http://localhost:8080")   // stream to the running form
).run();
```

Run it, and the requests/responses appear in the browser list view in real time. Packets captured
before the page is opened are backfilled automatically when the page loads. HTTPS bodies are captured
decrypted (JMeter terminates TLS as the client).

> Regular JMeter (GUI / non-GUI, `.jmx`) is **not** supported yet: that requires a dedicated JMeter
> plugin (a `BackendListenerClient` jar). The WebSocket ingestion endpoint (`/api/ingest`) and the
> shared extractors are already in place as the foundation for it.

## Testing (end-to-end with Playwright)

The e2e tests boot the full app in-process (`@SpringBootTest`, random port — which also keeps JMeter on
a real-file classpath) and drive the browser UI with Playwright against a local echo HTTP server, so no
external network is needed.

```bash
cd web-listview
mvn verify                       # installs chromium once, then runs the e2e suite
mvn verify -DskipPlaywrightInstall=true   # skip the browser install if already present
mvn verify -DskipITs=true        # skip e2e tests entirely
```

The suite (`TrafficInspectorE2EIT`) verifies: a run produces packets in the list, selecting a packet
shows request/response bodies, and the filter hides non-matching packets.

## Configuration

`src/main/resources/application.yml` exposes:
- `app.listview.ring-buffer-size` (default 5000) — in-memory packet history cap
- `app.listview.max-body-bytes` (default 256 KiB) — per-packet body truncation
