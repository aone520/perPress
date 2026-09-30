package com.per.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.per.server.entity.MetricSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 指标快照表 Mapper：继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力
 */
@Mapper
public interface MetricSnapshotMapper extends BaseMapper<MetricSnapshot> {
}
