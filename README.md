# Wireglass

> A smoked-glass pane onto the wire — see every packet your load test sends.
>
> _(formerly `jmeter-web-listview`; the Maven modules and Java packages keep that name.)_

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
├── jmeter/                  reference clones (read-only, gitignored — for grep/inspection)
│   ├── jmeter-core/         apache/jmeter (shallow)
│   └── jmeter-dsl/          abstracta/jmeter-java-dsl (shallow)
├── web-listview-client/     shared capture pipeline + jmeter-dsl listener
│   └── src/main/java/com/artembelikov/listview/client/
│       ├── capture/         CapturingReporter, SampleCapture, WsSink, PacketSink
│       ├── protocol/        PacketExtractor SPI + HTTP/WS/TCP extractors
│       ├── dto/             CapturedPacket, PacketType
│       └── json/            shared Jackson mapper
├── web-listview-jmeter/     BackendListenerClient plugin for stock JMeter (.jmx)
│   └── src/main/java/com/artembelikov/listview/jmeter/
│       └── WireglassBackendListener.java
├── web-listview/            the Spring Boot web app (UI + in-process runner + ingestion)
│   └── src/main/java/com/artembelikov/listview/
│       ├── capture/         PacketBus, TestRunService, InProcessSink
│       ├── store/           PacketRepository (ring buffer), RunRepository
│       ├── web/             REST controllers + SSE + WebSocket ingestion
│       └── config/          Spring configuration
├── docs/                    server-config format, PLAN.md, design docs
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
# option 1 (recommended) — flat classpath via the helper script
./web-listview/run.sh

# option 2 — Maven, from the repo root
mvn -pl web-listview -am org.springframework.boot:spring-boot-maven-plugin:run
```

Then open <http://localhost:8080>.

`java -jar target/web-listview-*.jar` will **not** work because of the limitation above.

`-am` matters here: `web-listview` depends on `web-listview-client`, and running only the app module can
pick up a stale client jar from your local Maven repository. That manifests as packets not appearing in
the UI or `NoSuchMethodError` at runtime.

> Use the **fully-qualified** goal (`org.springframework.boot:spring-boot-maven-plugin:run`), not the
> `spring-boot:run` prefix, when launching from the repo root: the short prefix is resolved against the
> aggregator POM, which doesn't declare the plugin, so it fails with `No plugin found for prefix
> 'spring-boot'`. (`spring-boot:run` works only from inside `web-listview/`.)

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

## Use with stock JMeter (`.jmx`, GUI / non-GUI)

Stock JMeter is supported too, via a `BackendListenerClient` plugin in the `web-listview-jmeter`
module. It reuses the same shared extractors and `/api/ingest` WebSocket transport, so a `.jmx` run
shows up in the browser exactly like a jmeter-dsl or form-launched run.

```bash
# 1. build the self-contained plugin jar
mvn -pl web-listview-jmeter -am package
# 2. drop it into your JMeter install
cp web-listview-jmeter/target/web-listview-jmeter-*-jmeter.jar "$JMETER_HOME/lib/ext/"
# 3. with Wireglass running, drive any plan that has the Backend Listener wired up
"$JMETER_HOME/bin/jmeter" -n -t web-listview-jmeter/examples/wireglass-example.jmx
```

Add a **Backend Listener** to the plan with implementation class
`com.artembelikov.listview.jmeter.WireglassBackendListener` and a `serverUrl` argument pointing at the
app (default `http://localhost:8080`). See [`web-listview-jmeter/README.md`](web-listview-jmeter/README.md)
for all parameters and a ready-to-run example plan.

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

## Server-provided rules and dashboards

The app can load read-only JSON Schema rules and dashboard links from two sources, merged together
(local wins on `id` collisions): a local file at `~/.wireglass/dashboards.json`, read automatically
with no configuration and auto-created empty on first run, and a server-hosted JSON file configured
by `app.listview.remote-config-url` — useful for a shared/centralized install. Both use the same
format, documented in [`docs/server-config-format.md`](docs/server-config-format.md).
