-- =====================================================
-- PerPress 压测平台数据库表结构（幂等，可重复执行）
-- =====================================================

-- 用户表：平台账号信息
CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `username` VARCHAR(64) NOT NULL UNIQUE,
  `password` VARCHAR(128) NOT NULL COMMENT 'BCrypt加密',
  `nickname` VARCHAR(64) DEFAULT NULL,
  `role` VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'ADMIN/USER',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 节点表：Agent 节点基础信息与实时资源状态
CREATE TABLE IF NOT EXISTS `node` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `node_key` VARCHAR(64) NOT NULL UNIQUE COMMENT '节点UUID',
  `hostname` VARCHAR(128), `ip` VARCHAR(64), `os` VARCHAR(64),
  `jvm_version` VARCHAR(64), `agent_version` VARCHAR(32),
  `engine_version` VARCHAR(32) COMMENT '已部署JMeter引擎版本',
  `labels` VARCHAR(512) COMMENT '标签逗号分隔',
  `status` VARCHAR(16) NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE/OFFLINE',
  `cpu_usage` DOUBLE, `mem_usage` DOUBLE, `mem_total` BIGINT,
  `jvm_mem_used` BIGINT, `jvm_mem_max` BIGINT,
  `net_recv_bps` DOUBLE COMMENT '网络接收速率B/s', `net_sent_bps` DOUBLE COMMENT '网络发送速率B/s',
  `last_heartbeat_time` DATETIME,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 审计日志表：关键操作记录
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` BIGINT, `username` VARCHAR(64),
  `action` VARCHAR(128), `detail` VARCHAR(1024), `ip` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 系统配置表：键值对存储
CREATE TABLE IF NOT EXISTS `sys_config` (
  `config_key` VARCHAR(64) PRIMARY KEY,
  `config_value` VARCHAR(1024),
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- =====================================================
-- M2：脚本中心 / 文件库 / 引擎包 / 任务引擎
-- =====================================================

-- 脚本表：压测脚本基本信息（导入 JMX 或表单生成）
CREATE TABLE IF NOT EXISTS `script` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(128) NOT NULL, `description` VARCHAR(512),
  `type` VARCHAR(16) NOT NULL DEFAULT 'IMPORTED' COMMENT 'IMPORTED导入/FORM表单',
  `form_def` LONGTEXT COMMENT '表单场景定义JSON',
  `latest_version` INT NOT NULL DEFAULT 1,
  `create_by` VARCHAR(64), `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 脚本版本表：每个版本对应一份 JMX 内容与关联文件
CREATE TABLE IF NOT EXISTS `script_version` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `script_id` BIGINT NOT NULL, `version` INT NOT NULL,
  `jmx_content` LONGTEXT NOT NULL, `file_ids` VARCHAR(1024) DEFAULT NULL COMMENT '关联文件id逗号分隔',
  `remark` VARCHAR(256), `create_by` VARCHAR(64), `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_script_ver` (`script_id`,`version`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 数据文件表：CSV/JAR/BIN 文件元信息（本地按 md5 存储，同 md5 复用）
CREATE TABLE IF NOT EXISTS `data_file` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(256) NOT NULL, `file_type` VARCHAR(16) NOT NULL COMMENT 'CSV/JAR/BIN',
  `size` BIGINT NOT NULL, `md5` VARCHAR(64) NOT NULL, `storage_path` VARCHAR(512) NOT NULL,
  `create_by` VARCHAR(64), `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 引擎包表：JMeter 引擎 zip 包，is_current=1 为当前发布版本
CREATE TABLE IF NOT EXISTS `engine_package` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `version` VARCHAR(32) NOT NULL, `file_name` VARCHAR(256), `size` BIGINT, `md5` VARCHAR(64),
  `storage_path` VARCHAR(512), `is_current` TINYINT NOT NULL DEFAULT 0,
  `remark` VARCHAR(256), `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 压测任务表：任务基本信息与状态机
CREATE TABLE IF NOT EXISTS `test_task` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `task_no` VARCHAR(32) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `script_id` BIGINT NOT NULL, `script_version_id` BIGINT NOT NULL,
  `mode` VARCHAR(16) NOT NULL COMMENT 'CONCURRENT/FIXED_TPS/STEPPED(M2只实现CONCURRENT)',
  `config_json` VARCHAR(2048) NOT NULL COMMENT '模式参数',
  `node_keys` VARCHAR(2048) NOT NULL COMMENT '参测节点逗号分隔',
  `file_dispatch_json` VARCHAR(2048) COMMENT '文件分发策略[{fileId,mode:SHARED|SPLIT}]',
  `status` VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PREPARING/RUNNING/STOPPING/FINISHED/FAILED',
  `start_time` DATETIME, `end_time` DATETIME,
  `create_by` VARCHAR(64), `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 任务节点表：任务与参测节点的执行明细（含分片序号与节点级 JMeter 参数）
CREATE TABLE IF NOT EXISTS `task_node` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `task_id` BIGINT NOT NULL, `node_key` VARCHAR(64) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DOWNLOADING/READY/RUNNING/STOPPED/FINISHED/FAILED/EXCLUDED',
  `jmeter_props` TEXT COMMENT '该节点-J参数JSON（阶梯模式多单元时键数=段数×单元数×4，VARCHAR 长度不足故用 TEXT）',
  `shard_index` INT DEFAULT 0 COMMENT 'SPLIT分片序号',
  `error_msg` VARCHAR(512), `start_time` DATETIME, `end_time` DATETIME,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY `uk_task_node`(`task_id`,`node_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- =====================================================
-- M3：指标上报 / 报告聚合 / 任务脚本快照
-- =====================================================

-- 指标快照表：Agent 每 10 秒窗口上报的采样器聚合指标（按窗口对齐整 10s，唯一键保证幂等）
CREATE TABLE IF NOT EXISTS `metric_snapshot` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `task_id` BIGINT NOT NULL,
  `node_key` VARCHAR(64) NOT NULL,
  `sampler` VARCHAR(256) NOT NULL,
  `window_start` BIGINT NOT NULL COMMENT '窗口起点毫秒(对齐整10s)',
  `window_end` BIGINT NOT NULL,
  `sample_count` BIGINT DEFAULT 0, `error_count` BIGINT DEFAULT 0,
  `bytes` BIGINT DEFAULT 0, `sent_bytes` BIGINT DEFAULT 0,
  `active_threads` INT DEFAULT 0,
  `min_ms` INT, `max_ms` INT, `sum_ms` BIGINT DEFAULT 0,
  `buckets` VARCHAR(1024) COMMENT '对数桶计数,逗号分隔(桶定义见服务注释)',
  UNIQUE KEY `uk_snap` (`task_id`,`node_key`,`sampler`,`window_start`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 错误样本表：失败样本明细（每任务累计保留前 200 条）
CREATE TABLE IF NOT EXISTS `error_sample` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `task_id` BIGINT NOT NULL, `node_key` VARCHAR(64),
  `sampler` VARCHAR(256), `response_code` VARCHAR(32), `message` VARCHAR(1024),
  `ts` BIGINT COMMENT '样本时间戳毫秒',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_task` (`task_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 测试报告表：任务结束后固化的聚合报告（5 个 JSON 分区）
CREATE TABLE IF NOT EXISTS `test_report` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `task_id` BIGINT NOT NULL UNIQUE,
  `summary_json` LONGTEXT, `samplers_json` LONGTEXT, `nodes_json` LONGTEXT,
  `errors_json` LONGTEXT, `series_json` LONGTEXT,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 任务脚本快照表：FORM 脚本按压测模式重渲染后的 JMX 快照（任务创建时生成，任务下发优先使用）
CREATE TABLE IF NOT EXISTS `task_script_snapshot` (
  `task_id` BIGINT PRIMARY KEY,
  `jmx_content` LONGTEXT NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 压力机资源采样表：任务运行期间 Agent 心跳携带的资源指标落库（复用低频心跳通道，
-- 不触碰压测指标链路；报告据此绘制各节点 CPU%/MEM% 曲线）
CREATE TABLE IF NOT EXISTS `node_resource_sample` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `task_id` BIGINT NOT NULL COMMENT '关联任务ID',
  `node_key` VARCHAR(64) NOT NULL COMMENT '节点唯一标识',
  `cpu_usage` DOUBLE NOT NULL COMMENT 'CPU使用率(0-100)',
  `mem_usage` DOUBLE NOT NULL COMMENT '系统内存使用率(0-100)',
  `mem_total` BIGINT COMMENT '系统总内存(字节)',
  `jvm_mem_used` BIGINT COMMENT 'JVM已用堆(字节)',
  `jvm_mem_max` BIGINT COMMENT 'JVM最大堆(字节)',
  `net_recv_bps` DOUBLE COMMENT '网络接收速率B/s(物理网卡差分)',
  `net_sent_bps` DOUBLE COMMENT '网络发送速率B/s(物理网卡差分)',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '采样时间',
  KEY `idx_task_node` (`task_id`, `node_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
