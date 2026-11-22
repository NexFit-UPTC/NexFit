package com.nexfit.bootstrap.seed;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Objects;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;

/** Escribe filas en formato texto de {@code COPY ... FROM STDIN}, por bloques, sin guardarlas todas en memoria. */
final class CopyWriter implements AutoCloseable {

    private static final int FLUSH_AT = 1 << 16;

    private final CopyIn copy;
    private final StringBuilder buffer = new StringBuilder(FLUSH_AT + 1024);
    private long rows;

    private CopyWriter(CopyIn copy) {
        this.copy = copy;
    }

    static CopyWriter open(PGConnection connection, String sql) {
        try {
            return new CopyWriter(connection.getCopyAPI().copyIn(sql));
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo iniciar COPY: " + sql, e);
        }
    }

    /** Una fila; {@code null} es NULL. */
    void row(Object... columns) {
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                buffer.append('\t');
            }
            Object value = columns[i];
            if (value == null) {
                buffer.append("\\N");
            } else if (value instanceof Boolean b) {
                buffer.append(b ? 't' : 'f');
            } else {
                escape(Objects.toString(value));
            }
        }
        buffer.append('\n');
        rows++;
        if (buffer.length() >= FLUSH_AT) {
            flush();
        }
    }

    long rows() {
        return rows;
    }

    private void escape(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> buffer.append("\\\\");
                case '\t' -> buffer.append("\\t");
                case '\n' -> buffer.append("\\n");
                case '\r' -> buffer.append("\\r");
                default -> buffer.append(c);
            }
        }
    }

    private void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
        buffer.setLength(0);
        try {
            copy.writeToCopy(bytes, 0, bytes.length);
        } catch (SQLException e) {
            throw new IllegalStateException("Falló COPY al escribir datos", e);
        }
    }

    @Override
    public void close() {
        flush();
        try {
            copy.endCopy();
        } catch (SQLException e) {
            throw new IllegalStateException("Falló COPY al terminar", e);
        }
    }
}
