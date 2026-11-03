package dev.workhub.common;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class Ids {
    private Ids() {}

    public static byte[] bytes(UUID id) {
        return id == null
                ? null
                : ByteBuffer.allocate(16)
                        .putLong(id.getMostSignificantBits())
                        .putLong(id.getLeastSignificantBits())
                        .array();
    }

    public static UUID uuid(byte[] value) {
        if (value == null) return null;
        var buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static UUID read(ResultSet row, String column) throws SQLException {
        return uuid(row.getBytes(column));
    }
}
