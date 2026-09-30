package com.per.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.per.server.entity.TaskScriptSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务脚本快照表 Mapper：继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力
 */
@Mapper
public interface TaskScriptSnapshotMapper extends BaseMapper<TaskScriptSnapshot> {
}
