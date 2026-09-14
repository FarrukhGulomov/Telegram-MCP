package io.tgmcp.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Small JDBC helpers.
 *
 * <p>The PostgreSQL driver does not convert {@code timestamptz} straight to {@link Instant};
 * it goes through {@link OffsetDateTime}. Doing that in one place keeps every repository
 * honest about time zones.
 */
public final class Jdbc {

    private Jdbc() {
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
