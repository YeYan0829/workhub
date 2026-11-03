package dev.workhub.unit;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.knowledge.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReciprocalRanksTest {
  KnowledgeReads.Evidence e(UUID source) {
    return new KnowledgeReads.Evidence(
        source, source, "DOCUMENT", "title", 1, "hash", source, 0, "text", "/source", 0, 4, 1);
  }

  @Test
  void sourceDuplicatesDoNotImproveRankAndAgreementWins() {
    var a = e(UUID.randomUUID());
    var b = e(UUID.randomUUID());
    var c = e(UUID.randomUUID());
    var fused = ReciprocalRanks.fuse(List.of(a, a, b), List.of(c, b), 3);
    assertThat(fused.get(0).sourceId()).isEqualTo(b.sourceId());
    assertThat(fused.get(0).score()).isCloseTo(2.0 / 62, within(1e-12));
    assertThat(fused).hasSize(3);
  }
}
