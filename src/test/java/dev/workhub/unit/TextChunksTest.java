package dev.workhub.unit;

import static org.assertj.core.api.Assertions.*;

import dev.workhub.knowledge.TextChunks;
import org.junit.jupiter.api.Test;

class TextChunksTest {
  @Test
  void spansPreserveUnicodeAndCoverEntireOwnerText() {
    String text = "# 认证\n" + "tokenTimeout 😀 登录权限\n".repeat(420);
    var chunks = TextChunks.split(text);
    assertThat(chunks).hasSizeGreaterThan(2);
    StringBuilder restored = new StringBuilder();
    int end = 0;
    for (var c : chunks) {
      assertThat(c.start()).isLessThanOrEqualTo(end);
      assertThat(c.text()).isEqualTo(text.substring(c.start(), c.end()));
      assertThat(Character.isLowSurrogate(c.text().charAt(0))).isFalse();
      restored.append(text, end, c.end());
      end = c.end();
    }
    assertThat(restored.toString()).isEqualTo(text);
    assertThat(TextChunks.split("")).isEmpty();
  }

  @Test
  void technicalIdentifiersAndChineseQueryShareDeterministicTerms() {
    assertThat(TextChunks.terms("TokenTimeout HTTP_401 登录模块"))
        .contains("token timeout", "http_401", "登录", "模块");
    assertThat(TextChunks.terms("ＨＴＴＰ_401")).isEqualTo(TextChunks.terms("HTTP_401"));
  }
}
