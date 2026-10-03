package com.per.server.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 轻量表结构补丁器：应用启动时检测 information_schema.columns，
 * 对已存在但缺少新增列的表执行幂等 ALTER TABLE ADD COLUMN。
 * 背景：schema.sql 仅在建表时生效（CREATE TABLE IF NOT EXISTS 不会修改已存在的表），
 * 而 sql.init 直接执行 ALTER 在列已存在时会报错中断启动，因此升级补列改由本类在代码内完成
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaPatcher implements SmartInitializingSingleton {

    /** test_task.scheduled_start_time 补列语句 */
    private static final String PATCH_TASK_SCHEDULED_START_TIME =
            "ALTER TABLE test_task ADD COLUMN scheduled_start_time DATETIME NULL COMMENT '定时启动时间'";

    /** test_task.trigger_type 补列语句（NOT NULL DEFAULT，补列时旧行自动回填 MANUAL） */
    private static final String PATCH_TASK_TRIGGER_TYPE =
            "ALTER TABLE test_task ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL' "
                    + "COMMENT 'MANUAL立即手动/SCHEDULED定时'";

    /** metric_snapshot.create_time 补列语句（快照过期清理依赖，旧行回填补列时刻） */
    private static final String PATCH_SNAPSHOT_CREATE_TIME =
            "ALTER TABLE metric_snapshot ADD COLUMN create_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP";

    /** test_task.status_time 补列语句（服务重启后恢复状态超时判断） */
    private static final String PATCH_TASK_STATUS_TIME =
            "ALTER TABLE test_task ADD COLUMN status_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '当前状态开始时间'";

    private static final String PATCH_SNAPSHOT_TASK_WINDOW_INDEX =
            "CREATE INDEX idx_task_window ON metric_snapshot(task_id, window_start)";

    /** error_sample.response_code 扩容：JMeter 非 HTTP 错误码可能是长异常类名/描述 */
    private static final String PATCH_ERROR_RESPONSE_CODE_LENGTH =
            "ALTER TABLE error_sample MODIFY COLUMN response_code VARCHAR(255) NULL";

    /** 待执行的补列清单：{表名, 列名, ALTER 语句} */
    private static final List<String[]> PATCHES = List.of(
            new String[]{"test_task", "scheduled_start_time", PATCH_TASK_SCHEDULED_START_TIME},
            new String[]{"test_task", "trigger_type", PATCH_TASK_TRIGGER_TYPE},
            new String[]{"test_task", "status_time", PATCH_TASK_STATUS_TIME},
            new String[]{"metric_snapshot", "create_time", PATCH_SNAPSHOT_CREATE_TIME});

    /** JDBC 模板（查询元数据与执行 DDL） */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 所有单例初始化完成、应用上下文发布前执行补列检查。
     * 该时点早于 @Scheduled 任务真正启动，避免定时查询抢跑访问尚未补齐的字段。
     */
    @Override
    public void afterSingletonsInstantiated() {
        for (String[] patch : PATCHES) {
            addColumnIfAbsent(patch[0], patch[1], patch[2]);
        }
        widenVarcharIfShorter("error_sample", "response_code", 255, PATCH_ERROR_RESPONSE_CODE_LENGTH);
        addIndexIfAbsent("metric_snapshot", "idx_task_window", PATCH_SNAPSHOT_TASK_WINDOW_INDEX);
    }

    /**
     * 检测指定表的列是否存在，不存在则执行 ALTER TABLE ADD COLUMN（幂等）
     *
     * @param table    表名
     * @param column   列名
     * @param alterSql 缺列时执行的 ALTER 语句
     */
    private void addColumnIfAbsent(String table, String column, String alterSql) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(alterSql);
        log.info("表结构补丁完成：{}.{} 列已追加", table, column);
    }

    private void addIndexIfAbsent(String table, String index, String createSql) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(createSql);
        log.info("表结构补丁完成：{}.{} 索引已追加", table, index);
    }

    /**
     * 检测 VARCHAR 字段长度，小于目标长度时执行 MODIFY COLUMN；字段不存在时交由 schema.sql/补列流程处理。
     *
     * @param table     表名
     * @param column    列名
     * @param minLength 最小字符长度
     * @param alterSql  扩容 DDL
     */
    void widenVarcharIfShorter(String table, String column, int minLength, String alterSql) {
        Integer currentLength = jdbcTemplate.queryForObject(
                "SELECT MAX(character_maximum_length) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (currentLength == null || currentLength >= minLength) {
            return;
        }
        jdbcTemplate.execute(alterSql);
        log.info("表结构补丁完成：{}.{} 已由 VARCHAR({}) 扩容为 VARCHAR({})",
                table, column, currentLength, minLength);
    }
}
