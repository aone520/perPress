package com.per.server.init;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaPatcherTest {

    @Test
    void widensExistingResponseCodeColumn() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate(32);
        SchemaPatcher patcher = new SchemaPatcher(jdbcTemplate);
        String alter = "ALTER TABLE error_sample MODIFY COLUMN response_code VARCHAR(255) NULL";

        patcher.widenVarcharIfShorter("error_sample", "response_code", 255, alter);

        assertEquals(alter, jdbcTemplate.executedSql);
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private final Integer columnLength;
        private String executedSql;

        private FakeJdbcTemplate(Integer columnLength) {
            this.columnLength = columnLength;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(columnLength);
        }

        @Override
        public void execute(String sql) {
            executedSql = sql;
        }
    }
}
