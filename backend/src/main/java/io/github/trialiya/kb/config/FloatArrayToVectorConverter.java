package io.github.trialiya.kb.config;

import static io.github.trialiya.kb.repository.DocumentEmbeddingRepository.toVectorLiteral;

import java.sql.SQLException;
import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * Spring Data JDBC converters that map between Java {@code float[]} and the PostgreSQL {@code
 * vector} type introduced by pgvector.
 *
 * <p>Register both via {@link PgVectorJdbcConfig}.
 */
public final class FloatArrayToVectorConverter {

    private FloatArrayToVectorConverter() {}

    // ── Write: float[] → PGobject("vector") ─────────────────────────────────

    @WritingConverter
    public static class Writer implements Converter<float[], PGobject> {

        @Override
        public PGobject convert(float[] source) {
            // pgvector literal: [0.1,0.2,...,0.n]
            String literal = toVectorLiteral(source);
            try {
                PGobject obj = new PGobject();
                obj.setType("vector");
                obj.setValue(literal);
                return obj;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot convert float[] to vector PGobject", e);
            }
        }
    }

    // ── Read: PGobject("vector") → float[] ──────────────────────────────────

    @ReadingConverter
    public static class Reader implements Converter<PGobject, float[]> {

        @Override
        public float[] convert(PGobject source) {
            String value = source.getValue(); // e.g. "[0.1,0.2,...]"
            if (value == null || value.isBlank()) {
                return new float[0];
            }
            // Strip surrounding brackets
            value = value.substring(1, value.length() - 1);
            String[] parts = value.split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
            return result;
        }
    }
}
