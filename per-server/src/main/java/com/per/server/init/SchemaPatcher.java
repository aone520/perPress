package com.per.server.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
public class SchemaPatcher implements ApplicationRunner {

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

    /** 待执行的补列清单：{表名, 列名, ALTER 语句} */
    private static final List<String[]> PATCHES = List.of(
            new String[]{"test_task", "scheduled_start_time", PATCH_TASK_SCHEDULED_START_TIME},
            new String[]{"test_task", "trigger_type", PATCH_TASK_TRIGGER_TYPE},
            new String[]{"metric_snapshot", "create_time", PATCH_SNAPSHOT_CREATE_TIME});

    /** JDBC 模板（查询元数据与执行 DDL） */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 应用启动完成后依次执行补列检查，缺列才执行对应 ALTER
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        for (String[] patch : PATCHES) {
            addColumnIfAbsent(patch[0], patch[1], patch[2]);
        }
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
}
