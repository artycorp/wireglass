package com.artembelikov.listview.capture;

import com.artembelikov.listview.dto.RunRequest;
import com.artembelikov.listview.dto.RunSummary;
import com.artembelikov.listview.dto.RunStatus;
import com.artembelikov.listview.store.PacketRepository;
import com.artembelikov.listview.store.RunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.stereotype.Service;
import us.abstracta.jmeter.javadsl.JmeterDsl;
import us.abstracta.jmeter.javadsl.core.DslTestPlan;
import us.abstracta.jmeter.javadsl.core.engines.AutoStoppedTestException;
import us.abstracta.jmeter.javadsl.http.DslHttpSampler;

@Service
public class TestRunService {

    private static final Logger LOG = LoggerFactory.getLogger(TestRunService.class);

    private final TrafficCaptureListenerFactory listenerFactory;
    private final PacketRepository repository;
    private final RunRepository runRepository;
    private final PacketBus bus;
    private final Clock clock;
    private final ServletWebServerApplicationContext webServerApplicationContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jmeter-run");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentMap<UUID, ActiveRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, RunStatus> finished = new ConcurrentHashMap<>();

    @Autowired
    public TestRunService(TrafficCaptureListenerFactory listenerFactory, PacketRepository repository,
                          RunRepository runRepository, PacketBus bus, Clock clock,
                          ServletWebServerApplicationContext webServerApplicationContext) {
        this.listenerFactory = listenerFactory;
        this.repository = repository;
        this.runRepository = runRepository;
        this.bus = bus;
        this.clock = clock;
        this.webServerApplicationContext = webServerApplicationContext;
    }

    public RunStatus start(RunRequest request) {
        UUID runId = UUID.randomUUID();
        ActiveRun run = new ActiveRun(runId, request, Instant.now(clock));
        run.counter = bus.subscribe(packet -> {
            if (!runId.equals(packet.runId())) {
                return;
            }
            run.captured.incrementAndGet();
            if (!packet.success()) {
                run.errors.incrementAndGet();
            }
            runRepository.upsert(toSummary(run, "RUNNING", null));
        });
        runs.put(runId, run);
        runRepository.upsert(toSummary(run, "RUNNING", null));

        DslTestPlan testPlan = buildPlan(request, runId);
        Future<?> task = executor.submit(() -> execute(runId, testPlan));
        run.task = task;
        return toStatus(run);
    }

    public RunStatus startDemo() {
        return start(new RunRequest(demoUrl(), "GET", null, null, 2, 3));
    }

    private void execute(UUID runId, DslTestPlan testPlan) {
        ActiveRun run = runs.get(runId);
        try {
            testPlan.run();
            finish(runId, "FINISHED", null);
        } catch (AutoStoppedTestException e) {
            finish(runId, "STOPPED", null);
        } catch (Exception e) {
            LOG.warn("Test run {} failed", runId, e);
            finish(runId, "FAILED", e.getMessage());
        }
    }

    private void finish(UUID runId, String state, String failureMessage) {
        ActiveRun run = runs.remove(runId);
        if (run == null) {
            return;
        }
        run.counter.close();
        RunStatus status = new RunStatus(
                run.id, state, run.startedAt, Instant.now(clock), run.request.url(),
                run.request.threads(), run.request.iterations(),
                run.captured.get(), run.errors.get());
        finished.put(run.id, status);
        runRepository.upsert(toSummary(run, state, status.finishedAt()));
    }

    public boolean stop(UUID runId) {
        ActiveRun run = runs.get(runId);
        if (run == null) {
            return false;
        }
        try {
            org.apache.jmeter.engine.StandardJMeterEngine.stopEngine();
        } catch (RuntimeException e) {
            LOG.debug("stopEngine call: {}", e.toString());
        }
        run.task.cancel(true);
        finish(runId, "STOPPED", null);
        return true;
    }

    public RunStatus status(UUID runId) {
        RunStatus done = finished.get(runId);
        if (done != null) {
            return done;
        }
        ActiveRun run = runs.get(runId);
        return run == null ? null : toStatus(run);
    }

    private RunStatus toStatus(ActiveRun run) {
        return new RunStatus(
                run.id, "RUNNING", run.startedAt, null, run.request.url(),
                run.request.threads(), run.request.iterations(),
                run.captured.get(), run.errors.get());
    }

    private RunSummary toSummary(ActiveRun run, String state, Instant finishedAt) {
        return new RunSummary(
                run.id,
                "internal",
                state,
                run.startedAt,
                finishedAt,
                run.request.url(),
                run.request.threads(),
                run.request.iterations(),
                run.captured.get(),
                run.errors.get());
    }

    private DslTestPlan buildPlan(RunRequest request, UUID runId) {
        DslHttpSampler sampler = JmeterDsl.httpSampler(request.url());
        String method = request.method().toUpperCase();
        configureMethodAndBody(sampler, method, request);
        return JmeterDsl.testPlan(
                JmeterDsl.threadGroup(request.threads(), request.iterations(), sampler),
                listenerFactory.newListener(runId));
    }

    private void configureMethodAndBody(DslHttpSampler sampler, String method, RunRequest request) {
        String body = request.body();
        boolean hasBody = body != null && !body.isBlank();
        ContentType contentType = parseContentType(request.contentType());
        /*
         * jmeter-dsl only transmits the request body when a content type is set (see DslHttpSampler.post
         * which is method(POST).contentType(ct).body(body)). When the user provides a body without a
         * content type, default to text/plain so the body is actually sent on the wire.
         */
        if (hasBody && contentType == null) {
            contentType = ContentType.TEXT_PLAIN.withCharset(java.nio.charset.StandardCharsets.UTF_8);
        }
        sampler.method(method);
        if (hasBody) {
            sampler.body(body);
        }
        if (contentType != null) {
            sampler.contentType(contentType);
        }
    }

    private ContentType parseContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        try {
            return ContentType.parse(contentType);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String demoUrl() {
        return "http://127.0.0.1:" + webServerApplicationContext.getWebServer().getPort() + "/api/demo/http";
    }

    private static final class ActiveRun {
        final UUID id;
        final RunRequest request;
        final Instant startedAt;
        final AtomicInteger captured = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        PacketBus.Subscription counter;
        Future<?> task;

        ActiveRun(UUID id, RunRequest request, Instant startedAt) {
            this.id = id;
            this.request = request;
            this.startedAt = startedAt;
        }
    }
}
