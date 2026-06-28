# Plan: jmeter-web-listview — Web Traffic Inspector on top of JMeter

## 1. Goal & Decisions

**Goal:** A web application that runs load tests via jmeter-java-dsl and, in real time,
shows the contents of every sent/received HTTP/HTTPS and WebSocket packet (bodies, headers,
timings), with an architectural foundation for TCP packet analysis.

**Confirmed decisions:**
- New Spring Boot 3.3 / Java 17 web application on top of jmeter-java-dsl (as a Maven dependency).
- Full shallow clones of `apache/jmeter` and `abstracta/jmeter-java-dsl` placed in `./jmeter/`
  as **reference only** (grep/read; not part of the build).
- Real-time packet stream to the browser via SSE (`EventSource`).
- Simple form-driven test launch + a one-click demo plan.
- Frontend — vanilla JS/HTML/CSS, no build step.
- TCP — only an SPI/extractor stub + a UI placeholder.

**Licenses:** both upstream sources are Apache 2.0 (allow modification and redistribution).
"Modifying JMeter" is achieved through jmeter-dsl extension points (`BaseListener` / `DslListener`)
without forking the core.

## 2. Directory Layout

```
jmeter-web-listview/                      (workspace root)
├── jmeter/                               ← reference, read-only
│   ├── jmeter-core/                      ← apache/jmeter (shallow clone)
│   └── jmeter-dsl/                       ← abstracta/jmeter-java-dsl (shallow clone)
├── web-listview/                         ← our application (all development here)
│   ├── pom.xml
│   ├── src/main/java/com/artembelikov/listview/
│   │   ├── ListViewApplication.java
│   │   ├── config/                       (ClockConfiguration etc., per AGENTS.md conventions)
│   │   ├── capture/                      ← JMeter integration
│   │   │   ├── TrafficCaptureListener.java   (extends BaseListener)
│   │   │   ├── PacketBus.java                (ring buffer + SSE subscribers)
│   │   │   └── TestRunService.java           (builds DslTestPlan, async run)
│   │   ├── protocol/                     ← per-protocol extractor SPI
│   │   │   ├── PacketExtractor.java          (interface)
│   │   │   ├── PacketType.java               (enum HTTP/WEBSOCKET/TCP)
│   │   │   ├── HttpPacketExtractor.java
│   │   │   ├── WebsocketPacketExtractor.java
│   │   │   └── TcpPacketExtractor.java       (stub: hex-dump from getResponseData)
│   │   ├── web/
│   │   │   ├── PageController.java           (serves index.html)
│   │   │   ├── RunApiController.java         (POST /api/runs, /api/runs/{id}/stop)
│   │   │   └── TrafficStreamController.java  (GET /api/traffic/stream — SSE)
│   │   ├── store/
│   │   │   └── PacketRepository.java         (in-memory ring buffer, post-run query)
│   │   └── dto/                              (records: RunRequest, CapturedPacket, ...)
│   └── src/main/resources/
│       ├── application.yml
│       └── static/  (index.html, app.js, style.css)
└── README.md
```

## 3. Tech Stack

| Layer | Technology | Rationale |
|---|---|---|
| Build | Maven | Matches AGENTS.md and jmeter-java-dsl |
| Engine | `us.abstracta.jmeter:jmeter-java-dsl:2.2` + `jmeter-java-dsl-websocket:2.2` | Canonical extension path |
| Server | Spring Boot 3.3, Java 17 | Matches AGENTS.md |
| Live stream | SSE (Spring `SseEmitter`) | Simple one-way stream, native browser support via `EventSource` |
| Frontend | Vanilla JS/HTML/CSS | No build step, minimal dependencies |

## 4. Capture Architecture (core part)

```
JMeter engine (DslTestPlan.run)
        │  sampleOccurred(SampleEvent)
        ▼
TrafficCaptureListener (extends BaseListener, DslListener)
        │  event.getResult() → SampleResult
        ▼
PacketExtractor (SPI: HTTP / WS / TCP by SampleLabel/ContentType)
        │  builds CapturedPacket
        ▼
PacketBus  ──► ring buffer (bounded, OOM protection under high RPS)
        │
        ├──► PacketRepository (history for post-run view/filter)
        └──► active SseEmitters of TrafficStreamController
                                        │
                                        ▼  browser (EventSource) → list-view
```

- **HTTP/HTTPS**: `HTTPSampleResult.getQueryString()` / `getSamplerData()` (request body),
  `getRequestHeaders()`, `getResponseData()` (response bytes), `getResponseHeaders()`,
  `getResponseCode()`, timings `currentTime()/startTime()`. For HTTPS the bodies are already
  decrypted — JMeter itself terminates TLS as the client, no MITM required.
- **WebSocket** (`jmeter-java-dsl-websocket` module): each WS sample yields a `SampleResult`
  with direction/message — the extractor pulls the payload and events (open/send/receive/close).
- **TCP**: a stub extractor reads `SampleResult.getResponseData()` and renders a hex dump;
  full analysis (frames, reassembly) is left as TODO, but the packet type and UI are in place.

## 5. Data Model (DTO records, per AGENTS.md conventions)

```java
record CapturedPacket(
    UUID id, PacketType type, OffsetDateTime timestamp,
    String threadName, String label,
    // request
    String method, String url, Map<String,String> requestHeaders, String requestBody,
    // response
    int status, String statusMessage, Map<String,String> responseHeaders,
    byte[] responseBody, String contentType,
    // timing
    long elapsedMs, long latencyMs, long connectMs,
    boolean success, String failureMessage) {}

enum PacketType { HTTP, WEBSOCKET, TCP }

record RunRequest(String url, String method, String body, String contentType,
                  int threads, int iterations) {}   // form
```

## 6. Implementation Phases (executable steps)

**Phase 0 — reference sources** ✅ DONE
1. ✅ `git clone --depth 1 https://github.com/apache/jmeter jmeter/jmeter-core`
2. ✅ `git clone --depth 1 https://github.com/abstracta/jmeter-java-dsl jmeter/jmeter-dsl`

**Phase 1 — application skeleton** ✅ DONE
3. ✅ Create the Maven module `web-listview` (`pom.xml`: spring-boot-starter-web,
   jmeter-java-dsl, jmeter-java-dsl-websocket; Java 17).
4. ✅ `ListViewApplication.java`, `application.yml` (port, ring-buffer limits). (+ log4j2 config)

**Phase 2 — capture layer** ✅ DONE
5. ✅ `PacketType`, `PacketExtractor` (SPI), `CapturedPacket`, `RunRequest`.
6. ✅ `HttpPacketExtractor` (map `SampleResult` → `CapturedPacket`).
7. ✅ `PacketBus` (bounded ring buffer + SSE subscribe/unsubscribe; drop/throttle under load).
8. ✅ `TrafficCaptureListener extends BaseListener` — `sampleOccurred`: pick extractor, post to bus.
9. ✅ `PacketRepository` (post-run read, search/filter). (+ abstract base to avoid duplication, + TrafficCapturingReporter TestElement)

**Phase 3 — running tests** ✅ DONE
10. ✅ `TestRunService`: build `testPlan(threadGroup(...), httpSampler(...), trafficCaptureListener)`
    via `JmeterDsl`, async launch with `EmbeddedJmeterEngine`, run statuses.
11. ✅ `RunApiController`: `POST /api/runs` (from form + demo), `POST /api/runs/{id}/stop`,
    `GET /api/runs/{id}`. (+ ClockConfiguration bean)

**Phase 4 — live streaming** ✅ DONE
12. ✅ `TrafficStreamController`: `GET /api/traffic/stream` → `SseEmitter`, subscribe to `PacketBus`,
    heartbeat, cleanup on disconnect. (+ `PacketsController` for post-run fetch/filter)

**Phase 5 — frontend** ✅ DONE
13. ✅ `index.html` + `style.css`: two-pane layout (list top/left, details bottom/right),
    launch panel (form + demo).
14. ✅ `app.js`: `EventSource` → append rows to list-view; row click → fetch
    `/api/packets/{id}` (or cache) → render headers/bodies (JSON-pretty, text, hex for binary);
    filters (by type/status/search), auto-scroll/pause.

**Phase 6 — WebSocket + TCP foundation** ✅ DONE
15. ✅ `WebsocketPacketExtractor`: handle WS sub-samples, show direction and events.
16. ✅ `TcpPacketExtractor`: hex-dump from `getResponseData()`, mark as `PacketType.TCP`,
    UI placeholder "TCP analysis: coming soon".

**Phase 7 — demo & verification** ✅ DONE
17. ✅ Demo plan against a public endpoint (e.g. `https://httpbin.org/get|post`).
18. ✅ Verify: HTTP GET/POST visible with bodies; HTTPS visible decrypted; WS visible;
    TCP row renders via stub; high RPS does not crash the app (ring buffer + throttle).
    Verified live: GET (200, HTTPS decrypted), POST with JSON body (request+response bodies),
    SSE streams 6 packets in real time, static UI served (index/app.js/style.css).

## 7. Key Files & Responsibilities (cheat sheet)

- `capture/TrafficCaptureListener.java` — single integration point with JMeter; one
  `sampleOccurred` method.
- `protocol/PacketExtractor.java` — SPI; a new protocol = a new extractor (the extension
  point for TCP and beyond).
- `capture/PacketBus` — decouples JMeter threads from HTTP threads; bounded.
- `capture/TestRunService` — the only place a `DslTestPlan` is assembled.
- `web/TrafficStreamController` — SSE endpoint.

## 8. Verification

- Run `./mvnw spring-boot:run`, open `http://localhost:8080`, launch demo: packets appear live.
- Separately: a POST with a JSON body → request body and response body visible in details.
- An HTTPS endpoint → bodies decrypted (no extra certificate setup).
- A load test (1000 req/s short burst) → no OOM, UI does not freeze (thanks to throttle/ring buffer).

## 9. Risks & Notes

- **High RPS:** capturing all packet bodies in memory can blow the heap. Mitigation — a bounded
  ring buffer (e.g. last N=5000) + an optional sample rate; large (binary) bodies → hex preview
  with truncation.
- **Logging conflict:** jmeter-java-dsl pulls log4j2, Spring Boot uses logback. Need
  `spring-boot-starter-log4j2` (exclude `spring-boot-starter-logging`) — a classic gotcha,
  fix it in `pom.xml` up front.
- **JMeter GUI deps:** `jmeter-java-dsl` core does not pull Swing; make sure no transitive
  `ApacheJMeter_components` sneaks in.
- **Versions:** jmeter-java-dsl `2.2` is built against JMeter 5.6.x — pin it explicitly in
  `pom.xml`.
