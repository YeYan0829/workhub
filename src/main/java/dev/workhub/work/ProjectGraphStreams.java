package dev.workhub.work;

import dev.workhub.common.*;
import dev.workhub.identity.ActorContext;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One batched project check and authorization check per tick, independent of viewer count. Each
 * nonblocking socket holds at most one latest notification, never business payloads.
 */
@Component
public class ProjectGraphStreams {
    private static final Logger log = LoggerFactory.getLogger(ProjectGraphStreams.class);
    private volatile long reportedAt = System.nanoTime(), previousFinished = System.nanoTime();
    private final AtomicLong framesSent = new AtomicLong(),
            slowCloses = new AtomicLong(),
            maxLagMillis = new AtomicLong();
    private final GraphStreamReads reads;
    private final Json json;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

    private record Cached(GraphStreamReads.Version version, long at) {}

    private final ConcurrentHashMap<UUID, Cached> versionCache = new ConcurrentHashMap<>();

    GraphStreamReads.Version cachedVersion(UUID project) {
        var c = versionCache.get(project);
        return c != null && System.nanoTime() - c.at() < TimeUnit.SECONDS.toNanos(1)
                ? c.version()
                : null;
    }

    private void cache(GraphStreamReads.Version version) {
        versionCache.compute(
                version.project(),
                (key, old) ->
                        old != null
                                        && (old.version().seq() > version.seq()
                                                || old.version().seq() == version.seq()
                                                        && old.version().business()
                                                                > version.business())
                                ? old
                                : new Cached(version, System.nanoTime()));
    }

    private final ScheduledExecutorService watcher =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "overview-watcher");
                        t.setDaemon(true);
                        return t;
                    });
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "overview-expiry");
                        t.setDaemon(true);
                        return t;
                    });
    final AtomicLong projectChecks = new AtomicLong(),
            authorizationChecks = new AtomicLong(),
            failures = new AtomicLong();

    public ProjectGraphStreams(GraphStreamReads reads, Json json) {
        this.reads = reads;
        this.json = json;
        watcher.scheduleWithFixedDelay(this::tick, 0, 500, TimeUnit.MILLISECONDS);
        watchdog.scheduleWithFixedDelay(
                () -> connections.forEach(Connection::expire), 100, 100, TimeUnit.MILLISECONDS);
    }

    public void open(
            ActorContext actor,
            UUID project,
            long after,
            UUID connectionId,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        String id = session == null ? null : session.getId();
        var registered =
                reads.register(
                        actor, project,
                        id); // Authorize before headers, then join before the next version check.
        var identity = new GraphStreamReads.Identity(project, actor.userId(), id);
        Connection c;
        synchronized (connections) {
            if (connectionId != null)
                connections.stream()
                        .filter(
                                v ->
                                        v.identity.user().equals(actor.userId())
                                                && Objects.equals(v.identity.session(), id)
                                                && connectionId.equals(v.connectionId))
                        .toList()
                        .forEach(Connection::close);
            if (connections.size() >= 200
                    || connections.stream()
                                    .filter(v -> v.identity.user().equals(actor.userId()))
                                    .count()
                            >= 3) {
                response.setHeader("Retry-After", "5");
                throw new ApiException(503, "GRAPH_STREAM_CAPACITY", "概览订阅已满，将使用轮询同步");
            }
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            var async = request.startAsync();
            async.setTimeout(0);
            c = new Connection(identity, connectionId, after, async, response.getOutputStream());
            connections.add(c);
            if (registered != null)
                cache(
                        new GraphStreamReads.Version(
                                project,
                                Long.parseLong(registered.viewSeq()),
                                registered.businessRevision(),
                                Long.parseLong(registered.topologyRevision()),
                                Long.parseLong(registered.retainedAfterSeq())));
        }
        final var connection = c;
        try {
            c.async.addListener(
                    new AsyncListener() {
                        public void onComplete(AsyncEvent e) {
                            connection.close();
                        }

                        public void onTimeout(AsyncEvent e) {
                            connection.close();
                        }

                        public void onError(AsyncEvent e) {
                            connection.close();
                        }

                        public void onStartAsync(AsyncEvent e) {}
                    });
            c.output.setWriteListener(
                    new WriteListener() {
                        public void onWritePossible() {
                            connection.drain();
                        }

                        public void onError(Throwable e) {
                            connection.close();
                        }
                    });
            c.offer("retry: 1000\nevent: project.ready\ndata: {}\n\n", false);
        } catch (RuntimeException e) {
            c.close();
            throw e;
        }
    }

    public void closeOwned(ActorContext actor, UUID project, UUID connectionId, String session) {
        connections.stream()
                .filter(
                        c ->
                                c.identity.user().equals(actor.userId())
                                        && c.identity.project().equals(project)
                                        && Objects.equals(c.identity.session(), session)
                                        && connectionId.equals(c.connectionId))
                .toList()
                .forEach(Connection::close);
    }

    private void tick() {
        long began = System.nanoTime();
        maxLagMillis.accumulateAndGet(
                Math.max(0, TimeUnit.NANOSECONDS.toMillis(began - previousFinished) - 500),
                Math::max);
        try {
            var copy = List.copyOf(connections);
            if (System.nanoTime() - reportedAt >= TimeUnit.SECONDS.toNanos(30)) {
                log.info(
                        "OVERVIEW_STREAM_METRICS active={} projectChecks={} authorizationChecks={}"
                                + " frames={} slowCloses={} maxLagMs={}",
                        copy.size(),
                        projectChecks.get(),
                        authorizationChecks.get(),
                        framesSent.get(),
                        slowCloses.get(),
                        maxLagMillis.get());
                reportedAt = System.nanoTime();
            }
            if (copy.isEmpty()) {
                versionCache.clear();
                return;
            }
            var projects = new HashSet<UUID>();
            var identities = new HashSet<GraphStreamReads.Identity>();
            copy.forEach(
                    c -> {
                        projects.add(c.identity.project());
                        identities.add(c.identity);
                    });
            var allowed = reads.authorized(identities);
            authorizationChecks.incrementAndGet();
            var versions = reads.versions(projects);
            versions.values().forEach(this::cache);
            versionCache.keySet().retainAll(projects);
            projectChecks.incrementAndGet();
            for (var c : copy) {
                if (c.closed.get()) continue;
                if (!allowed.contains(c.identity)) {
                    c.offer("event: access.revoked\ndata: {}\n\n", true);
                    continue;
                }
                var v = versions.get(c.identity.project());
                if (v == null) {
                    c.close();
                    continue;
                }
                if (v.seq() != c.sentSeq || c.first) {
                    String event =
                            c.sentSeq < v.retained() || c.sentSeq > v.seq()
                                    ? "project.reset"
                                    : "project.changed";
                    c.offer(
                            "id: "
                                    + v.seq()
                                    + "\nevent: "
                                    + event
                                    + "\ndata: "
                                    + json.write(
                                            Map.of(
                                                    "latestSeq",
                                                    Long.toString(v.seq()),
                                                    "businessRevision",
                                                    v.business(),
                                                    "topologyRevision",
                                                    Long.toString(v.topology())))
                                    + "\n\n",
                            false);
                    c.sentSeq = v.seq();
                    c.first = false;
                } else if (System.nanoTime() - c.lastFrame > TimeUnit.SECONDS.toNanos(15))
                    c.offer(": heartbeat\n\n", false);
            }
        } catch (Exception e) {
            failures
                    .incrementAndGet(); /* Clients reconcile through /state; the next tick retries. */
        } finally {
            previousFinished = System.nanoTime();
        }
    }

    public int activeConnections() {
        return connections.size();
    }

    final class Connection {
        final GraphStreamReads.Identity identity;
        final UUID connectionId;
        final AsyncContext async;
        final ServletOutputStream output;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile long sentSeq, lastFrame = System.nanoTime(), pendingAt;
        volatile boolean first = true, closing;
        byte[] pending;

        Connection(
                GraphStreamReads.Identity identity,
                UUID connectionId,
                long after,
                AsyncContext async,
                ServletOutputStream output) {
            this.identity = identity;
            this.connectionId = connectionId;
            this.sentSeq = after;
            this.async = async;
            this.output = output;
        }

        synchronized void offer(String frame, boolean terminal) {
            if (closed.get() || closing) return;
            byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 1024) {
                close();
                return;
            }
            pending = bytes;
            if (pendingAt == 0) pendingAt = System.nanoTime();
            closing = terminal;
            drain();
        }

        synchronized void drain() {
            if (closed.get() || pending == null) return;
            try {
                if (!output.isReady()) return;
                output.write(pending);
                output.flush();
                framesSent.incrementAndGet();
                pending = null;
                pendingAt = 0;
                lastFrame = System.nanoTime();
                if (closing) close();
            } catch (IOException | IllegalStateException e) {
                close();
            }
        }

        void expire() {
            // drain() can clear the volatile timestamp between reads. Subtract one
            // captured value so clearing a healthy frame cannot become an uptime-sized delay.
            long queuedAt = pendingAt;
            if (queuedAt != 0 && System.nanoTime() - queuedAt > TimeUnit.SECONDS.toNanos(5)) {
                slowCloses.incrementAndGet();
                close();
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            connections.remove(this);
            if (connections.stream()
                    .noneMatch(c -> c.identity.project().equals(identity.project())))
                versionCache.remove(identity.project());
            try {
                async.complete();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    @PreDestroy
    void shutdown() {
        connections.forEach(Connection::close);
        watcher.shutdownNow();
        watchdog.shutdownNow();
    }
}
