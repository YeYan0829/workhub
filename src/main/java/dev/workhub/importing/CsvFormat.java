package dev.workhub.importing;

import dev.workhub.common.ApiException;

import java.util.*;

/**
 * Strict bounded RFC4180-style UTF-8 parser; supports quoted commas/newlines and doubled quotes.
 */
public final class CsvFormat {
    public static final List<String> HEADER =
            List.of("title", "description", "type", "priority", "due_date", "assignee_login");

    public static List<List<String>> parse(byte[] bytes) {
        if (bytes.length > 1_048_576) throw new ApiException(413, "CSV_TOO_LARGE", "CSV最大1 MiB");
        String s;
        try {
            s =
                    java.nio.charset.StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
        } catch (Exception e) {
            throw ApiException.invalid("CSV必须为UTF-8");
        }
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else field.append(c);
            } else if (c == '"') {
                if (field.length() != 0 || closed) throw ApiException.invalid("CSV引号格式错误");
                quoted = true;
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(field.toString());
                field.setLength(0);
                closed = false;
                if (c != ',') {
                    if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                    rows.add(List.copyOf(row));
                    row.clear();
                    if (rows.size() > 1001) throw ApiException.invalid("CSV最多1000行数据");
                }
            } else {
                if (closed) throw ApiException.invalid("CSV闭合引号后只能是分隔符");
                field.append(c);
            }
            if (field.length() > 10000 || row.size() > 6) throw ApiException.invalid("CSV字段超过限制");
        }
        if (quoted) throw ApiException.invalid("CSV引号未闭合");
        if (field.length() > 0 || !row.isEmpty() || closed) {
            row.add(field.toString());
            rows.add(row);
        }
        if (rows.isEmpty() || !rows.remove(0).equals(HEADER))
            throw ApiException.invalid("CSV表头必须为 " + String.join(",", HEADER));
        if (rows.isEmpty() || rows.size() > 1000) throw ApiException.invalid("CSV需要1–1000行数据");
        return rows;
    }

    private CsvFormat() {}
}
