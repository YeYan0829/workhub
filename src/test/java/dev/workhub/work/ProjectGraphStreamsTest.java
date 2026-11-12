package dev.workhub.work;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workhub.common.*;
import dev.workhub.identity.ActorContext;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;

class ProjectGraphStreamsTest {
    @Test
    void latestFrameIsCoalescedAndStalledSocketExpiresWithoutBlockingOthers() throws Exception {
        var reads = mock(GraphStreamReads.class);
        var json = new Json(new com.fasterxml.jackson.databind.ObjectMapper());
        var streams = new ProjectGraphStreams(reads, json);
        try {
            var output = mock(ServletOutputStream.class);
            when(output.isReady()).thenReturn(false);
            var async = mock(AsyncContext.class);
            var key =
                    new GraphStreamReads.Identity(UUID.randomUUID(), UUID.randomUUID(), "session");
            var c = streams.new Connection(key, UUID.randomUUID(), 0, async, output);
            for (int i = 0; i < 1000; i++)
                c.offer("event: project.changed\ndata: {\"latestSeq\":\"" + i + "\"}\n\n", false);
            assertThat(new String(c.pending, java.nio.charset.StandardCharsets.UTF_8))
                    .contains("999");
            assertThat(c.pending.length).isLessThan(1024);
            verify(output, never()).write(any(byte[].class));
            c.expire();
            assertThat(c.closed.get()).isFalse();
            c.pendingAt = System.nanoTime() - TimeUnit.SECONDS.toNanos(6);
            c.expire();
            assertThat(c.closed.get()).isTrue();
            verify(async).complete();
            var ready = mock(ServletOutputStream.class);
            when(ready.isReady()).thenReturn(true);
            var other =
                    streams
                    .new Connection(key, UUID.randomUUID(), 0, mock(AsyncContext.class), ready);
            other.offer(": heartbeat\n\n", false);
            verify(ready).write(any(byte[].class));
            assertThat(other.closed.get()).isFalse();
            other.close();
        } finally {
            streams.shutdown();
        }
    }

    @Test
    void connectionLimitsArePerUserAndPerInstanceAndOwnedCloseReleasesSlots() throws Exception {
        var streams =
                new ProjectGraphStreams(
                        mock(GraphStreamReads.class),
                        new Json(new com.fasterxml.jackson.databind.ObjectMapper()));
        // Disable the timer while exercising deterministic allocation at the servlet boundary.
        ((java.util.concurrent.ScheduledExecutorService)
                        ReflectionTestUtils.getField(streams, "watcher"))
                .shutdownNow();
        UUID project = UUID.randomUUID(), user = UUID.randomUUID();
        var request = mock(HttpServletRequest.class);
        var session = mock(HttpSession.class);
        when(session.getId()).thenReturn("session");
        when(request.getSession(false)).thenReturn(session);
        when(request.startAsync()).thenAnswer(i -> mock(AsyncContext.class));
        var response = mock(HttpServletResponse.class);
        when(response.getOutputStream())
                .thenAnswer(
                        i -> {
                            var out = mock(ServletOutputStream.class);
                            when(out.isReady()).thenReturn(false);
                            return out;
                        });
        try {
            UUID first = UUID.randomUUID();
            streams.open(new ActorContext(user), project, 0, first, request, response);
            streams.open(new ActorContext(user), project, 0, UUID.randomUUID(), request, response);
            streams.open(new ActorContext(user), project, 0, UUID.randomUUID(), request, response);
            assertThatThrownBy(
                            () ->
                                    streams.open(
                                            new ActorContext(user),
                                            project,
                                            0,
                                            UUID.randomUUID(),
                                            request,
                                            response))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.status).isEqualTo(503));
            streams.closeOwned(new ActorContext(UUID.randomUUID()), project, first, "session");
            assertThat(streams.activeConnections()).isEqualTo(3);
            streams.closeOwned(new ActorContext(user), project, first, "session");
            assertThat(streams.activeConnections()).isEqualTo(2);
            for (int i = 2; i < 200; i++)
                streams.open(
                        new ActorContext(UUID.randomUUID()),
                        project,
                        0,
                        UUID.randomUUID(),
                        request,
                        response);
            assertThat(streams.activeConnections()).isEqualTo(200);
            assertThatThrownBy(
                            () ->
                                    streams.open(
                                            new ActorContext(UUID.randomUUID()),
                                            project,
                                            0,
                                            UUID.randomUUID(),
                                            request,
                                            response))
                    .isInstanceOf(ApiException.class);
        } finally {
            streams.shutdown();
        }
        assertThat(streams.activeConnections()).isZero();
    }
}
