package dev.workhub.work;

import dev.workhub.common.*;
import dev.workhub.identity.*;

import jakarta.servlet.http.*;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/projects/{p}")
public class OverviewController {
    private final CurrentActor actors;
    private final ProjectGraph legacy;
    private final GraphReadService graph;
    private final GraphSyncService sync;
    private final OverviewState states;
    private final TaskPreviewService previews;
    private final ProjectGraphStreams streams;
    private final Json json;

    public OverviewController(
            CurrentActor actors,
            ProjectGraph legacy,
            GraphReadService graph,
            GraphSyncService sync,
            OverviewState states,
            TaskPreviewService previews,
            ProjectGraphStreams streams,
            Json json) {
        this.actors = actors;
        this.legacy = legacy;
        this.graph = graph;
        this.sync = sync;
        this.states = states;
        this.previews = previews;
        this.streams = streams;
        this.json = json;
    }

    @GetMapping("/graph")
    public Object graph(
            Principal u,
            @PathVariable UUID p,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) UUID focusTaskId,
            @RequestParam(required = false) Integer nodeLimit,
            @RequestParam(required = false) Integer edgeLimit) {
        var actor = actors.from(u);
        return bounded(
                view == null
                        ? legacy.snapshot(actor, p)
                        : graph.initial(actor, p, view, focusTaskId, nodeLimit, edgeLimit),
                1048576);
    }

    @PostMapping("/graph/view")
    public Object view(
            Principal u,
            @PathVariable UUID p,
            @RequestBody GraphReadService.ViewRequest request,
            HttpServletRequest raw) {
        bodyBudget(raw, request);
        return bounded(graph.view(actors.from(u), p, request), 1048576);
    }

    @PostMapping("/graph/sync")
    public Object sync(
            Principal u,
            @PathVariable UUID p,
            @RequestBody GraphSyncService.Request request,
            HttpServletRequest raw) {
        bodyBudget(raw, request);
        return bounded(sync.sync(actors.from(u), p, request), 1048576);
    }

    @GetMapping(value = "/task-search", params = "projection=overview")
    public Object search(
            Principal u,
            @PathVariable UUID p,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "20") int limit) {
        return graph.search(actors.from(u), p, q, limit);
    }

    @GetMapping("/graph/state")
    public ResponseEntity<?> state(
            Principal u,
            @PathVariable UUID p,
            @RequestHeader(value = "If-None-Match", required = false) String match,
            @RequestHeader(value = "Cache-Control", required = false) String requestCache) {
        var actor = actors.from(u);
        var cached =
                requestCache != null && requestCache.contains("no-cache")
                        ? null
                        : streams.cachedVersion(p);
        OverviewState.State s;
        if (cached == null) s = states.state(actor, p);
        else {
            states.authorize(actor, p);
            s =
                    new OverviewState.State(
                            Long.toString(cached.seq()),
                            cached.business(),
                            Long.toString(cached.topology()),
                            Long.toString(cached.retained()),
                            Map.of(),
                            0,
                            0);
        }
        String tag =
                "\""
                        + s.viewSeq()
                        + ":"
                        + s.businessRevision()
                        + ":"
                        + s.topologyRevision()
                        + ":"
                        + s.retainedAfterSeq()
                        + "\"";
        if (tag.equals(match))
            return ResponseEntity.status(304)
                    .header("ETag", tag)
                    .cacheControl(CacheControl.noStore())
                    .build();
        return ResponseEntity.ok()
                .header("ETag", tag)
                .cacheControl(CacheControl.noStore())
                .body(
                        Map.of(
                                "viewSeq",
                                s.viewSeq(),
                                "businessRevision",
                                s.businessRevision(),
                                "topologyRevision",
                                s.topologyRevision(),
                                "retainedAfterSeq",
                                s.retainedAfterSeq()));
    }

    @GetMapping("/graph/events")
    public void events(
            Principal u,
            @PathVariable UUID p,
            @RequestParam(defaultValue = "0") String after,
            @RequestParam(required = false) UUID connectionId,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        try {
            streams.open(
                    actors.from(u),
                    p,
                    OverviewState.sequence(after),
                    connectionId,
                    request,
                    response);
        } catch (ApiException error) {
            response.setStatus(error.status);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(json.write(ApiError.from(error)));
        }
    }

    @DeleteMapping("/graph/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeEvents(
            Principal u,
            @PathVariable UUID p,
            @RequestParam UUID connectionId,
            HttpServletRequest request) {
        var session = request.getSession(false);
        streams.closeOwned(
                actors.from(u), p, connectionId, session == null ? null : session.getId());
    }

    @GetMapping("/tasks/{t}/preview")
    public Object preview(
            Principal u,
            @PathVariable UUID p,
            @PathVariable UUID t,
            @RequestParam(defaultValue = "5") int commentLimit) {
        return bounded(previews.preview(actors.from(u), p, t, commentLimit), 131072);
    }

    private void bodyBudget(HttpServletRequest request, Object body) {
        if (request.getContentLengthLong() > 32768
                || json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > 32768) throw new ApiException(413, "GRAPH_REQUEST_LIMIT", "图范围请求超过32KiB");
    }

    private Object bounded(Object value, int max) {
        if (json.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > max)
            throw new ApiException(409, "GRAPH_RESPONSE_LIMIT", "请缩小图范围后重试");
        return value;
    }
}
