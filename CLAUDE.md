# CLAUDE.md

Guidance for Claude Code working in this repo. Keep it authoritative where the code can't speak for
itself: non-obvious commands, project-specific architecture, and the gotchas that already bit us.
See @README.md for the overview/API and @PLAN.md for the design.

## Project
jmeter-web-listview — web traffic inspector on top of jmeter-java-dsl. Multi-module Maven (Java 17):
- `web-listview` — Spring Boot 3.3 web app (the browser UI + in-process runner + ingestion endpoint)
- `web-listview-client` — jmeter-dsl listener that streams captured packets from ANY standalone
  jmeter-dsl test plan to the running app over WebSocket

## Build / run / test  (IMPORTANT — non-obvious)
- Build everything (from repo root): `mvn verify` — the reactor builds the client before the web app.
- Run the app: `mvn -pl web-listview -am spring-boot:run` (or `./web-listview/run.sh`).
- YOU MUST NOT use `java -jar target/*.jar`: the Spring Boot fat jar fails with
  `URI is not hierarchical`. JMeter's embedded engine resolves each test-element class' jar via
  `new File(codeSource.toURI())`, which breaks on nested `BOOT-INF/lib` URIs. Use an exploded
  classpath (`spring-boot:run` / `run.sh`) — that's also why the e2e tests use `@SpringBootTest`.
- The `-am` on `spring-boot:run` is required after shared-client changes: otherwise Maven can run the
  app with a stale `web-listview-client` from `~/.m2`, leading to runtime `NoSuchMethodError` and
  "run started but no packets appeared" behavior.
- E2E tests (Playwright/Java, `*IT.java`, run by Failsafe): `mvn verify` auto-installs chromium at
  `pre-integration-test`. Skip tests: `-DskipITs=true`; skip the browser download:
  `-DskipPlaywrightInstall=true`.
- Compile only (fast loop): `mvn -q -DskipTests compile`.

## Architecture (specific to this project)
- All captured traffic flows through one `PacketBus` → bounded `PacketRepository` (ring buffer) and
  the browser SSE stream (`GET /api/traffic/stream`).
- Extraction is shared and lives in `web-listview-client`:
  `client.protocol.*` extractors + `client.capture.CapturingReporter` (a JMeter `SampleListener`,
  `NoThreadClone`) with a pluggable `client.capture.PacketSink`.
  - `InProcessSink` (web app) → `PacketBus` directly (no serialization).
  - `WsSink` (standalone jmeter-dsl client) → JSON over WebSocket to `POST /api/ingest` → bus.
  Both in-form runs and external jmeter-dsl runs end up on the same bus, so the UI is identical.
- Extractor ORDER MATTERS: HTTP → WebSocket → TCP. `TcpPacketExtractor.supports()` always returns
  true (catch-all), so it must stay last; see `TrafficCaptureListenerFactory.orderedExtractors()`.

## How-to: add support for a new protocol (e.g. gRPC)
1. Add a `PacketType` enum value in `web-listview-client/.../client/dto/PacketType.java`.
2. Write `XxxPacketExtractor extends AbstractPacketExtractor` (override `supportedType()`,
   `supports()`, `resolveMethod/Url/RequestBody`). Mirror `HttpPacketExtractor`.
3. Register it in BOTH ordered lists:
   - `web-listview-client/.../client/capture/TrafficCaptureClient.defaultExtractors()`
   - `web-listview/.../capture/TrafficCaptureListenerFactory.orderedExtractors()`
   Place a catch-all extractor last; otherwise keep specific-before-generic.
4. The frontend already colors by `type` lowercase class — add CSS for the new type if desired.

## How-to: add a new capture transport (e.g. HTTP POST instead of WebSocket)
Implement `PacketSink` (`open/publish/close`) in the client module and a matching ingestion endpoint
in the web app that calls `packetBus.publish(packet)`. `CapturedPacket` is serialized with
`client.json.Json` (Jackson + JavaTimeModule) — reuse it so the wire format stays consistent.

## How-to: typical feature workflow
1. Scope it (small fix → just do it; multi-file/uncertain → plan first).
2. Implement; reuse the shared extraction/sink primitives before adding parallel logic.
3. Add/adjust an e2e assertion in `TrafficInspectorE2EIT` (the test boots the full app in-process on
   a random port and drives the real browser against a local echo server — no external network).
4. `mvn verify -pl web-listview -am` until green; also exercise the remote path if you touched
   capture/transport.

## Code style (differs from defaults)
- Package roots: `com.artembelikov.listview` (web app) and `com.artembelikov.listview.client` (client).
- Java 17 records for DTOs/data; constructor injection only; `java.time.OffsetDateTime` for time.
- Logging is log4j2 (NOT logback) because JMeter requires it — never reintroduce
  `spring-boot-starter-logging`. Config: `web-listview/src/main/resources/log4j2.xml`.
- The client module pins Jackson via `jackson-bom`. Without Spring Boot's BOM, standalone usage
  otherwise hits `NoSuchMethodError: BufferRecycler.releaseToPool()` (jackson-core/databind mismatch).
- No code comments unless explicitly requested.

## Frontend gotchas
- Vanilla JS, no build step. `el` holds DOM refs; the packet-table ref is `el.tbody` — NOT `el.body`
  (that name collided with the request-body textarea, shadowed it, and sent empty POST bodies; a
  real bug). When adding a form field, pick a unique `el.<name>`.
- Packets reach the UI two ways and are deduped by id in `state.seen`: live via SSE, and backfilled
  on page load via `GET /api/packets` (so a test that ran before the page was opened still shows up).
- h3 section titles are uppercased by CSS — assert case-insensitively in tests.

## Repo etiquette
- Never commit `jmeter/` (apache/jmeter + jmeter-java-dsl shallow reference clones) or `target/` —
  both gitignored. `jmeter/` exists only for local grep/inspection of upstream sources.
- Keep changes scoped; commit messages match the existing imperative style. Don't push or open PRs
  unless asked.
