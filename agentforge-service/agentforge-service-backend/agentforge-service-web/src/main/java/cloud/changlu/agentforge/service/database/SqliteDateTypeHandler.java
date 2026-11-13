package cloud.changlu.agentforge.service.database;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Stores {@link Date} values as SQLite-friendly text instead of epoch milliseconds.
 *
 * <p>sqlite-jdbc writes {@code java.util.Date} through {@code setTimestamp} as a millisecond
 * number, while the default reader expects a formatted timestamp. This handler keeps both sides
 * consistent and also accepts the {@code CURRENT_TIMESTAMP} format produced by SQLite defaults.
 *
 * @author changlu
 * @since 2026-10-29
 */
public class SqliteDateTypeHandler extends BaseTypeHandler<Date> {

    private static final DateTimeFormatter WRITE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter[] READ_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    };

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Date parameter, JdbcType jdbcType)
            throws SQLException {
        String value =
                WRITE_FORMAT.format(
                        parameter.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        ps.setString(i, value);
    }

    @Override
    public Date getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Date getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Date getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Date parse(String raw) throws SQLException {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.matches("\\d+")) {
            return new Date(Long.parseLong(value));
        }
        for (DateTimeFormatter formatter : READ_FORMATS) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(value, formatter);
                return Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
            } catch (RuntimeException ignored) {
                // Try the next supported format.
            }
        }
        try {
            return new Date(Timestamp.valueOf(value).getTime());
        } catch (RuntimeException e) {
            throw new SQLException("Unparseable date: " + raw, e);
        }
    }
}
