# CLAUDE.md

Guidance for Claude Code working in this repo. Keep it authoritative where the code can't speak for
itself: non-obvious commands, project-specific architecture, and the gotchas that already bit us.
See @README.md for the overview/API. `docs/PLAN.md` records the original design and phase history
(all phases complete) — read it on demand for rationale, not as live guidance.

## Project
Wireglass — web traffic inspector on top of jmeter-java-dsl. Multi-module Maven (Java 17):
- `wireglass-app` — Spring Boot 3.3 web app (the browser UI + in-process runner + ingestion endpoint)
- `wireglass-client` — jmeter-dsl listener that streams captured packets from ANY standalone
  jmeter-dsl test plan to the running app over WebSocket
- `wireglass-jmeter` — `BackendListenerClient` plugin that streams captured packets from ANY
  stock JMeter (`.jmx`, GUI/non-GUI) test plan to the running app over the same WebSocket. Reactor
  order: client → jmeter → wireglass-app (falls out of the dependency graph).

## Build / run / test  (IMPORTANT — non-obvious)
- Build everything (from repo root): `mvn verify` — the reactor builds the client before the web app.
- Run the app: `./wireglass-app/run.sh`, or from the repo root in TWO steps —
  `mvn -pl wireglass-app -am -DskipTests install` then
  `mvn -pl wireglass-app org.springframework.boot:spring-boot-maven-plugin:run`. Do NOT use the
  short `spring-boot:run` prefix from the root — it resolves against the aggregator POM (no plugin
  there) and fails with `No plugin found for prefix 'spring-boot'`; the prefix works only from inside
  `wireglass-app/`.
- YOU MUST NOT combine `-am` with the fully-qualified run goal
  (`mvn -pl wireglass-app -am org.springframework.boot:spring-boot-maven-plugin:run`) — a CLI-invoked
  fully-qualified goal executes on EVERY module `-am` adds to the reactor, and `wireglass-client`
  builds first with no main class: `Unable to find a suitable main class`. The client POM has no
  Spring Boot parent, so Maven also resolves an unpinned (much newer) plugin version there. Split it:
  `-am install` first to refresh the client, then the run goal without `-am`.
- YOU MUST NOT use `java -jar target/*.jar`: the Spring Boot fat jar fails with
  `URI is not hierarchical`. JMeter's embedded engine resolves each test-element class' jar via
  `new File(codeSource.toURI())`, which breaks on nested `BOOT-INF/lib` URIs. Use an exploded
  classpath (`spring-boot-maven-plugin:run` / `run.sh`) — that's also why the e2e tests use
  `@SpringBootTest`.
- The `-am` on the run goal is required after shared-client changes: otherwise Maven can run the
  app with a stale `wireglass-client` from `~/.m2`, leading to runtime `NoSuchMethodError` and
  "run started but no packets appeared" behavior.
- E2E tests (Playwright/Java, `*IT.java`, run by Failsafe — `TrafficInspectorE2EIT`,
  `ServerConfigRulesE2EIT`, `MergedDashboardConfigE2EIT`, `LocalOnlyDashboardConfigE2EIT`, plus
  `JmeterBackendListenerIT`, which drives a real stock-JMeter run through the shaded plugin and is
  browser-less): `mvn verify` auto-installs chromium at `pre-integration-test`. Skip tests:
  `-DskipITs=true`; skip the browser download: `-DskipPlaywrightInstall=true`.
- Any test that boots the app (unit or `*IT.java`) MUST override `user.home` to a temp dir in
  `@BeforeEach`/`@AfterEach` (see `RemoteConfigServiceTest`, `TrafficInspectorE2EIT`) — the app reads
  `~/.wireglass/dashboards.json` on every load, so an un-isolated test touches (and can pollute) the
  real user's home directory.
- Single test class: `mvn -pl wireglass-app -am test -Dtest=RemoteConfigServiceTest`; single IT:
  `mvn -pl wireglass-app -am verify -Dit.test=ServerConfigRulesE2EIT`.
- Compile only (fast loop): `mvn -q -DskipTests compile`.
- CI (`.github/workflows/ci.yml`) caches Chromium under a key pinned to `playwright-<os>-chromium-1.48.0`.
  Bump that key in lockstep with `playwright.version` in `wireglass-app/pom.xml` — nothing enforces the
  match, and a stale cache restores the wrong browser silently.

## Architecture (specific to this project)
- All captured traffic flows through one `PacketBus` → bounded `PacketRepository` (ring buffer) and
  the browser SSE stream (`GET /api/traffic/stream`).
- Extraction is shared and lives in `wireglass-client`:
  `client.protocol.*` extractors + `client.capture.CapturingReporter` (a JMeter `SampleListener`,
  `NoThreadClone`) with a pluggable `client.capture.PacketSink`.
  - `InProcessSink` (web app) → `PacketBus` directly (no serialization).
  - `WsSink` (standalone jmeter-dsl client AND stock-JMeter plugin) → JSON over WebSocket to
    `POST /api/ingest` → bus.
  Form runs, external jmeter-dsl runs, and stock-JMeter `.jmx` runs all end up on the same bus, so
  the UI is identical.
- `SampleCapture` (in `wireglass-client`) is the single source of truth for "recurse to leaf
  sub-results → pick extractor → publish to sink" (leaf-only: a parent transaction reports aggregated
  time/headers). Reused by BOTH the jmeter-dsl `CapturingReporter` and the stock-JMeter
  `WireglassBackendListener` — change capture semantics there, not in either front-end.
- Stock-JMeter plugin (`wireglass-jmeter`): `WireglassBackendListener extends
  AbstractBackendListenerClient`. Its `-jmeter` classifier jar (maven-shade) is the deliverable for
  `$JMETER_HOME/lib/ext`; it bundles our code + Java-WebSocket + a RELOCATED Jackson (so it can't
  clash with JMeter's own lib Jackson) and treats the whole JMeter tree as provided. The thin main
  jar is what `wireglass-app` depends on in TEST scope (`JmeterBackendListenerIT`), keeping relocated
  Jackson off the app classpath.
- Run tracking: `TestRunService` stamps every packet with a `runId` and subscribes to the bus filtered
  by it; `store/RunRepository` holds a `RunSummary` per run and `GET /api/runs` lists them, so the UI
  can scope the table to one run. `capture/ClientRuntimeVerifier` reflectively asserts
  `CapturedPacket.withRunId(UUID)` exists at startup — that's what turns a stale `~/.m2` client (see
  the `-am` note above) into a clear boot failure instead of a runtime `NoSuchMethodError`. If you add
  a `CapturedPacket` method the app depends on, consider pinning it there too.
- Session files (`web/SessionController`, `dto/SessionFile`): `GET /api/session/export` dumps
  `RunRepository` + `PacketRepository` into one versioned JSON; `POST /api/session/import` **merges**
  it back (never replaces — comparing two sessions side by side is the whole point) and flags every
  imported run via `RunSummary.asRestored()`. The UI shows that flag as a `from file` badge on the
  existing run chip, so comparison reuses the run selector instead of adding a second mechanism.
  Dedup is by packet `id` inside `PacketRepository.importAll`, which holds the lock across the
  contains-check and the insert — do not reimplement it as `get()` then `add()`. Bump
  `SessionFile.CURRENT_VERSION` whenever the shape changes; imports of any other version are rejected
  whole. Note imports still pass through the ring buffer, so loading more than
  `app.listview.ring-buffer-size` packets evicts the oldest.
- Extractor ORDER MATTERS: HTTP → WebSocket → TCP. `TcpPacketExtractor.supports()` always returns
  true (catch-all), so it must stay last; see `TrafficCaptureListenerFactory.orderedExtractors()`.
- Server-provided rules/dashboards (`capture/RemoteConfigService`, `web/RemoteConfigController`):
  merges an optional server-hosted `app.listview.remote-config-url` JSON file with a local file at
  `~/.wireglass/dashboards.json` (auto-created empty on first read, no config needed). **Local wins
  on `id` collisions.** Both sources use the same schema — see `docs/server-config-format.md` before
  changing either side of this merge.

## How-to: add support for a new protocol (e.g. gRPC)
1. Add a `PacketType` enum value in `wireglass-client/.../client/dto/PacketType.java`.
2. Write `XxxPacketExtractor extends AbstractPacketExtractor` (override `supportedType()`,
   `supports()`, `resolveMethod/Url/RequestBody`). Mirror `HttpPacketExtractor`.
3. Register it in BOTH ordered lists:
   - `wireglass-client/.../client/capture/TrafficCaptureClient.defaultExtractors()`
   - `wireglass-app/.../capture/TrafficCaptureListenerFactory.orderedExtractors()`
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
4. `mvn verify -pl wireglass-app -am` until green; also exercise the remote path if you touched
   capture/transport.

## Tooling (required)
- Use `rg` (ripgrep), never plain `grep`, for all code search.
- Use `jdtls` (Eclipse JDT Language Server) for Java code intelligence — go-to-definition,
  find-references, symbol search — instead of text search across `wireglass-app`/`wireglass-client`
  sources.
- Use `vtsls` (TypeScript/JavaScript language server) for JS code intelligence in
  `wireglass-app/src/main/resources/static/app.js` instead of text search.

## Code style (differs from defaults)
- Package roots: `com.wireglass.listview` (web app) and `com.wireglass.listview.client` (client).
- Java 17 records for DTOs/data; constructor injection only; `java.time.OffsetDateTime` for time.
- Logging is log4j2 (NOT logback) because JMeter requires it — never reintroduce
  `spring-boot-starter-logging`. Config: `wireglass-app/src/main/resources/log4j2.xml`.
- The client AND `wireglass-jmeter` modules each pin Jackson via `jackson-bom`. Without Spring
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
- i18n lives in `static/i18n.js` (loaded before `app.js`): `t(key, params)`, `plural(n, forms)`,
  `applyTranslations(root)`, `setActiveLanguage(lang)`. Static text is marked with `data-i18n`
  (and `data-i18n-placeholder` / `-title` / `-aria-label` for attributes).
  **`applyTranslations` replaces an element's first NON-WHITESPACE TEXT NODE, not its
  `textContent`** — nearly every label here is a text node adjacent to an element child
  (`<label>url <input/></label>`, `<button>JSON Schema <span id="schema-count">0</span></button>`),
  and `textContent` would delete the nested control. Whitespace-only nodes are skipped so indented
  markup works, and the node's surrounding spaces are preserved so inline labels keep their gap
  from the control. A label whose text sits after its children (the `bodies` checkbox) or between
  them (the `New run` button) needs no wrapper.
  Text that is a DATA VALUE carries no `data-i18n`: `HTTP`/`WS`/`TCP` (bound to `data-type`), HTTP
  methods, `2xx`–`5xx`, and the `response`/`request` options in the schema pane — that last one is
  persisted into saved rules and asserted by the e2e suite.
  English stays the default when nothing is stored, which is what keeps the existing ITs green.
  Switching language does NOT reload: `setLanguage` calls `retranslateRenderedContent()`, which
  re-runs every render entry point (`rebuildList`, `rerenderSelectedDetail`, the settings renders)
  so already-drawn content follows the switch while filters, the packet list, and the selected
  packet survive. Add a new render function to that list when you add one, or its output will keep
  the old language until something else repaints it.
- Packets reach the UI two ways and are deduped by id in `state.seen`: live via SSE, and backfilled
  on page load via `GET /api/packets` (so a test that ran before the page was opened still shows up).
- h3 section titles are uppercased by CSS — assert case-insensitively in tests.
- Third-party frontend assets are VENDORED under `static/vendor/` (CodeMirror 5, Plex fonts). No build
  step and no CDN — a strict-CSP-friendly setup. Add a library by vendoring it, not by adding a
  `<script src="https://...">`.

## Repo etiquette
- Never commit `jmeter/` (apache/jmeter + jmeter-java-dsl shallow reference clones) or `target/` —
  both gitignored. `jmeter/` exists only for local grep/inspection of upstream sources.
- Keep changes scoped; commit messages match the existing imperative style. Don't push or open PRs
  unless asked.
