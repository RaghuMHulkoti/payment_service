package com.learning.payment_service.gateway;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Shared row-mapping helpers: UUIDs live in RAW(16), timestamps in TIMESTAMP WITH TIME ZONE. */
final class JdbcSupport {

    private JdbcSupport() {
    }

    static byte[] raw(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static UUID uuid(byte[] raw) {
        var buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return uuid(rs.getBytes(column));
    }

    static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
