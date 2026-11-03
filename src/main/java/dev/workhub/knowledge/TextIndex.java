package dev.workhub.knowledge;

import java.util.*;

public interface TextIndex {
  record Document(String id, Map<String, Object> fields) {}

  record Hit(String id, double score) {}

  record Candidates(List<Hit> hits, long total) {}

  void publish(List<Document> documents);

  Candidates search(UUID project, String query, int size);
}
