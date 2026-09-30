package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.per.server.entity.ErrorSample;
import com.per.server.entity.MetricSnapshot;
import com.per.server.mapper.ErrorSampleMapper;
import com.per.server.mapper.MetricSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据保留清理调度器：每日凌晨删除已结束任务（FINISHED/FAILED）的超期指标快照与错误样本，
 * 固化报告 test_report 不在清理范围。删除按批执行（每批 5000 行），避免大事务长锁
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HousekeepingScheduler {

    /** 每批删除行数上限 */
    private static final int BATCH_SIZE = 5000;

    /** 已结束任务状态的子查询（所属任务状态为 FINISHED/FAILED 才可清理） */
    private static final String FINISHED_TASK_IDS_SQL =
            "SELECT id FROM test_task WHERE status IN ('FINISHED','FAILED')";

    private final MetricSnapshotMapper metricSnapshotMapper;
    private final ErrorSampleMapper errorSampleMapper;

    /** 快照保留天数（来自配置 per.report.snapshot-retention-days，默认 90） */
    @Value("${per.report.snapshot-retention-days:90}")
    private int retentionDays;

    /**
     * 每日 03:30 执行：删除 create_time 早于保留阈值且所属任务已结束的
     * metric_snapshot 与 error_sample（分批 select id limit 5000 循环删除，直到不足一批），
     * 汇总输出 INFO 日志；执行异常仅 WARN 不影响调度
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanExpiredSnapshots() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
            long snapshots = deleteSnapshotsInBatches(threshold);
            long errorSamples = deleteErrorSamplesInBatches(threshold);
            if (snapshots > 0) {
                log.info("清理任务快照 metric_snapshot {} 行", snapshots);
            }
            if (errorSamples > 0) {
                log.info("清理任务快照 error_sample {} 行", errorSamples);
            }
        } catch (Exception e) {
            log.warn("快照过期清理执行失败", e);
        }
    }

    /**
     * 分批删除过期指标快照：每批先按 id 取 5000 行再批量删除，循环直到不足一批
     *
     * @param threshold 过期阈值（create_time 早于该时间）
     * @return 累计删除行数
     */
    private long deleteSnapshotsInBatches(LocalDateTime threshold) {
        long deleted = 0;
        while (true) {
            List<MetricSnapshot> batch = metricSnapshotMapper.selectList(new LambdaQueryWrapper<MetricSnapshot>()
                    .select(MetricSnapshot::getId)
                    .lt(MetricSnapshot::getCreateTime, threshold)
                    .inSql(MetricSnapshot::getTaskId, FINISHED_TASK_IDS_SQL)
                    .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            metricSnapshotMapper.deleteBatchIds(batch.stream().map(MetricSnapshot::getId).toList());
            deleted += batch.size();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        return deleted;
    }

    /**
     * 分批删除过期错误样本：每批先按 id 取 5000 行再批量删除，循环直到不足一批
     *
     * @param threshold 过期阈值（create_time 早于该时间）
     * @return 累计删除行数
     */
    private long deleteErrorSamplesInBatches(LocalDateTime threshold) {
        long deleted = 0;
        while (true) {
            List<ErrorSample> batch = errorSampleMapper.selectList(new LambdaQueryWrapper<ErrorSample>()
                    .select(ErrorSample::getId)
                    .lt(ErrorSample::getCreateTime, threshold)
                    .inSql(ErrorSample::getTaskId, FINISHED_TASK_IDS_SQL)
                    .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            errorSampleMapper.deleteBatchIds(batch.stream().map(ErrorSample::getId).toList());
            deleted += batch.size();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        return deleted;
    }
}
