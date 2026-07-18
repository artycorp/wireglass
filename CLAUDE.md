# CLAUDE.md

Guidance for Claude Code working in this repo. Keep it authoritative where the code can't speak for
itself: non-obvious commands, project-specific architecture, and the gotchas that already bit us.
See @README.md for the overview/API and @docs/PLAN.md for the design.

## Project
jmeter-web-listview — web traffic inspector on top of jmeter-java-dsl. Multi-module Maven (Java 17):
- `web-listview` — Spring Boot 3.3 web app (the browser UI + in-process runner + ingestion endpoint)
- `web-listview-client` — jmeter-dsl listener that streams captured packets from ANY standalone
  jmeter-dsl test plan to the running app over WebSocket
- `web-listview-jmeter` — `BackendListenerClient` plugin that streams captured packets from ANY
  stock JMeter (`.jmx`, GUI/non-GUI) test plan to the running app over the same WebSocket. Reactor
  order: client → jmeter → web-listview (falls out of the dependency graph).

## Build / run / test  (IMPORTANT — non-obvious)
- Build everything (from repo root): `mvn verify` — the reactor builds the client before the web app.
- Run the app: `./web-listview/run.sh`, or from the repo root
  `mvn -pl web-listview -am org.springframework.boot:spring-boot-maven-plugin:run`. Do NOT use the
  short `spring-boot:run` prefix from the root — it resolves against the aggregator POM (no plugin
  there) and fails with `No plugin found for prefix 'spring-boot'`; the prefix works only from inside
  `web-listview/`.
- YOU MUST NOT use `java -jar target/*.jar`: the Spring Boot fat jar fails with
  `URI is not hierarchical`. JMeter's embedded engine resolves each test-element class' jar via
  `new File(codeSource.toURI())`, which breaks on nested `BOOT-INF/lib` URIs. Use an exploded
  classpath (`spring-boot-maven-plugin:run` / `run.sh`) — that's also why the e2e tests use
  `@SpringBootTest`.
- The `-am` on the run goal is required after shared-client changes: otherwise Maven can run the
  app with a stale `web-listview-client` from `~/.m2`, leading to runtime `NoSuchMethodError` and
  "run started but no packets appeared" behavior.
- E2E tests (Playwright/Java, `*IT.java`, run by Failsafe — `TrafficInspectorE2EIT`,
  `ServerConfigRulesE2EIT`, `MergedDashboardConfigE2EIT`, `LocalOnlyDashboardConfigE2EIT`):
  `mvn verify` auto-installs chromium at `pre-integration-test`. Skip tests: `-DskipITs=true`; skip
  the browser download: `-DskipPlaywrightInstall=true`.
- Any test that boots the app (unit or `*IT.java`) MUST override `user.home` to a temp dir in
  `@BeforeEach`/`@AfterEach` (see `RemoteConfigServiceTest`, `TrafficInspectorE2EIT`) — the app reads
  `~/.wireglass/dashboards.json` on every load, so an un-isolated test touches (and can pollute) the
  real user's home directory.
- Single test class: `mvn -pl web-listview -am test -Dtest=RemoteConfigServiceTest`; single IT:
  `mvn -pl web-listview -am verify -Dit.test=ServerConfigRulesE2EIT`.
- Compile only (fast loop): `mvn -q -DskipTests compile`.

## Architecture (specific to this project)
- All captured traffic flows through one `PacketBus` → bounded `PacketRepository` (ring buffer) and
  the browser SSE stream (`GET /api/traffic/stream`).
- Extraction is shared and lives in `web-listview-client`:
  `client.protocol.*` extractors + `client.capture.CapturingReporter` (a JMeter `SampleListener`,
  `NoThreadClone`) with a pluggable `client.capture.PacketSink`.
  - `InProcessSink` (web app) → `PacketBus` directly (no serialization).
  - `WsSink` (standalone jmeter-dsl client AND stock-JMeter plugin) → JSON over WebSocket to
    `POST /api/ingest` → bus.
  Form runs, external jmeter-dsl runs, and stock-JMeter `.jmx` runs all end up on the same bus, so
  the UI is identical.
- `SampleCapture` (in `web-listview-client`) is the single source of truth for "recurse to leaf
  sub-results → pick extractor → publish to sink" (leaf-only: a parent transaction reports aggregated
  time/headers). Reused by BOTH the jmeter-dsl `CapturingReporter` and the stock-JMeter
  `WireglassBackendListener` — change capture semantics there, not in either front-end.
- Stock-JMeter plugin (`web-listview-jmeter`): `WireglassBackendListener extends
  AbstractBackendListenerClient`. Its `-jmeter` classifier jar (maven-shade) is the deliverable for
  `$JMETER_HOME/lib/ext`; it bundles our code + Java-WebSocket + a RELOCATED Jackson (so it can't
  clash with JMeter's own lib Jackson) and treats the whole JMeter tree as provided. The thin main
  jar is what `web-listview` depends on in TEST scope (`JmeterBackendListenerIT`), keeping relocated
  Jackson off the app classpath.
- Extractor ORDER MATTERS: HTTP → WebSocket → TCP. `TcpPacketExtractor.supports()` always returns
  true (catch-all), so it must stay last; see `TrafficCaptureListenerFactory.orderedExtractors()`.
- Server-provided rules/dashboards (`capture/RemoteConfigService`, `web/RemoteConfigController`):
  merges an optional server-hosted `app.listview.remote-config-url` JSON file with a local file at
  `~/.wireglass/dashboards.json` (auto-created empty on first read, no config needed). **Local wins
  on `id` collisions.** Both sources use the same schema — see `docs/server-config-format.md` before
  changing either side of this merge.

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

## Tooling (required)
- Use `rg` (ripgrep), never plain `grep`, for all code search.
- Use `jdtls` (Eclipse JDT Language Server) for Java code intelligence — go-to-definition,
  find-references, symbol search — instead of text search across `web-listview`/`web-listview-client`
  sources.
- Use `vtsls` (TypeScript/JavaScript language server) for JS code intelligence in
  `web-listview/src/main/resources/static/app.js` instead of text search.

## Code style (differs from defaults)
- Package roots: `com.artembelikov.listview` (web app) and `com.artembelikov.listview.client` (client).
- Java 17 records for DTOs/data; constructor injection only; `java.time.OffsetDateTime` for time.
- Logging is log4j2 (NOT logback) because JMeter requires it — never reintroduce
  `spring-boot-starter-logging`. Config: `web-listview/src/main/resources/log4j2.xml`.
- The client AND `web-listview-jmeter` modules each pin Jackson via `jackson-bom`. Without Spring
  Boot's BOM, standalone usage otherwise hits `NoSuchMethodError: BufferRecycler.releaseToPool()`
  (jackson-core/databind mismatch). `dependencyManagement` is NOT inherited transitively, so every
  module that (re)bundles Jackson — e.g. the shaded plugin jar — must import the bom itself, not rely
  on the client's.
- No code comments unless explicitly requested.

## Frontend gotchas
- Vanilla JS, no build step, everything in one `static/app.js` (~2400 lines) — grep for the relevant
  section/state key rather than reading it top to bottom. `el` holds DOM refs; the packet-table ref
  is `el.tbody` — NOT `el.body`
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
