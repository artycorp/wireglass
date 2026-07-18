# wireglass-jmeter — Wireglass plugin for stock JMeter

A JMeter `BackendListenerClient` that streams every sample of a **stock JMeter** test plan
(`.jmx`, GUI or non-GUI `-n` CLI) into a running [Wireglass](../README.md) app — exactly like the
jmeter-dsl [`TrafficCaptureClient`](../wireglass-client) does, so a `.jmx` run appears live in the
browser list view with the same request/response bodies, headers, and timings.

It reuses the shared capture pipeline (extractors → `CapturedPacket` → JSON over the `/api/ingest`
WebSocket) from `wireglass-client`; this module only adds the JMeter-native front door and its
packaging.

## Build the plugin jar

From the repo root:

```bash
mvn -pl wireglass-jmeter -am package
```

This produces a **self-contained** plugin jar (JMeter supplies its own classes at runtime; only our
code + Java-WebSocket + a relocated Jackson are bundled):

```
wireglass-jmeter/target/wireglass-jmeter-<version>-jmeter.jar
```

## Install into JMeter

Copy the `-jmeter.jar` into your JMeter installation's extension directory, then start JMeter:

```bash
cp wireglass-jmeter/target/wireglass-jmeter-*-jmeter.jar "$JMETER_HOME/lib/ext/"
```

## Use it in a test plan

Add a **Backend Listener** to the test plan and set:

- **Backend Listener implementation:** `com.wireglass.listview.jmeter.WireglassBackendListener`
- **Parameters:**

  | Name           | Default                  | Meaning                                             |
  |----------------|--------------------------|-----------------------------------------------------|
  | `serverUrl`    | `http://localhost:8080`  | The running Wireglass app to stream to.             |
  | `maxBodyBytes` | `262144`                 | Per-packet body truncation (bytes).                 |
  | `runId`        | *(blank)*                | Optional UUID to group samples under one run; blank = auto. |

A ready-to-run plan lives at [`examples/wireglass-example.jmx`](examples/wireglass-example.jmx).

### Non-GUI run

With Wireglass already running (`mvn -pl wireglass-app -am spring-boot:run`):

```bash
"$JMETER_HOME/bin/jmeter" -n -t wireglass-jmeter/examples/wireglass-example.jmx
```

The requests/responses appear live in the browser at <http://localhost:8080>. HTTPS bodies are
captured decrypted (JMeter terminates TLS as the client). Packets captured before the page is opened
are backfilled on load.
