package dev.workhub.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.workhub.common.*;
import dev.workhub.identity.*;
import jakarta.servlet.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;

class AgentProgressStreamsTest {
  AgentProgress progress;AgentProgressStreams streams;AgentProgressStreams.Connection connection;
  ServletOutputStream output;AsyncContext async;ActorContext actor;UUID project,run;
  @BeforeEach void setup() {
    progress=mock(AgentProgress.class);var json=new Json(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    streams=new AgentProgressStreams(progress,json);output=mock(ServletOutputStream.class);async=mock(AsyncContext.class);
    actor=new ActorContext(UUID.randomUUID());project=UUID.randomUUID();run=UUID.randomUUID();
    connection=streams.new Connection(actor,project,run,null,async,output);
    when(progress.streamSnapshot(actor,project,run,null)).thenReturn(new AgentProgress.Snapshot(run,null,"RUNNING",1,"WAITING","RATE",null,Instant.now(),Instant.now()));
  }
  @AfterEach void close(){streams.shutdown();}
  @RepeatedTest(3) void slowWriterKeepsNoFrameQueueAndClosesAtFiveSecondBoundary() throws Exception {
    when(output.isReady()).thenReturn(false);
    for(int i=0;i<100;i++)connection.pump();
    verifyNoInteractions(progress);verify(output,never()).write(any(byte[].class));
    assertThat(connection.closed.get()).isFalse();
    // Controlled monotonic-clock boundary, not a network socket throughput measurement.
    connection.stalledAt=System.nanoTime()-AgentProgressStreams.SLOW_NANOS-1;
    connection.expire();assertThat(connection.closed.get()).isTrue();verify(async).complete();
  }
  @RepeatedTest(3) void everyWriteReauthorizesAndRevocationSendsNoFurtherContent() throws Exception {
    when(output.isReady()).thenReturn(true);
    connection.pump();verify(output,times(1)).write(any(byte[].class));
    when(progress.streamSnapshot(actor,project,run,null)).thenThrow(ApiException.forbidden());
    connection.pump();connection.pump();
    verify(progress,times(2)).streamSnapshot(actor,project,run,null);verify(output,times(1)).write(any(byte[].class));verify(async).complete();
  }
  @Test void terminalSendsOneSnapshotAndCloses() throws Exception {
    when(output.isReady()).thenReturn(true);
    when(progress.streamSnapshot(actor,project,run,null)).thenReturn(new AgentProgress.Snapshot(run,null,"FAILED",3,"TERMINAL",null,"MODEL_COST_BUDGET",null,Instant.now()));
    connection.pump();connection.pump();verify(output,times(1)).write(any(byte[].class));verify(async).complete();
  }
  @Test void connectionAdmissionIsBoundedAndShutdownReleasesAll() throws Exception {
    for(int i=0;i<AgentProgressStreams.MAX_CONNECTIONS;i++) {
      var request=mock(jakarta.servlet.http.HttpServletRequest.class);
      var response=mock(jakarta.servlet.http.HttpServletResponse.class);
      when(request.startAsync()).thenReturn(mock(AsyncContext.class));
      when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
      streams.open(actor,project,run,request,response);
    }
    assertThatThrownBy(()->streams.open(actor,project,run,mock(jakarta.servlet.http.HttpServletRequest.class),mock(jakarta.servlet.http.HttpServletResponse.class)))
        .hasFieldOrPropertyWithValue("code","PROGRESS_CAPACITY");
    streams.shutdown();
  }
}
