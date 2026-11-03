package dev.workhub.knowledge;

import dev.workhub.common.Inputs;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Fixed config 1: UTF-16 spans into the exact owner text, never model-generated citations. */
public final class TextChunks {
  private TextChunks() {}

  public record Chunk(int ordinal, int start, int end, String text, String hash) {}

  public static List<Chunk> split(String text) {
    if (text.isEmpty()) return List.of();
    List<Chunk> chunks = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(text.length(), start + 2000);
      if (end < text.length()) {
        int line = text.lastIndexOf('\n', end);
        if (line > start + 1000) end = line + 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
      }
      String body = text.substring(start, end);
      chunks.add(new Chunk(chunks.size(), start, end, body, Inputs.hash(body)));
      if (end == text.length()) break;
      start = end - 160;
      if (Character.isLowSurrogate(text.charAt(start))) start++;
    }
    return List.copyOf(chunks);
  }

  public static String terms(String value) {
    String normalized =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("([a-z])([A-Z])", "$1 $2")
            .toLowerCase(Locale.ROOT);
    List<String> result = new ArrayList<>();
    Matcher m = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+").matcher(normalized);
    while (m.find()) {
      String token = m.group();
      if (token
          .codePoints()
          .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
        int[] chars = token.codePoints().toArray();
        for (int i = 0; i < chars.length; i++) {
          result.add(new String(chars, i, 1));
          if (i + 1 < chars.length) result.add(new String(chars, i, 2));
        }
      } else result.add(token);
    }
    return String.join(" ", result);
  }
}
