package com.per.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.per.server.entity.NodeResourceSample;
import org.apache.ibatis.annotations.Mapper;

/**
 * 压力机资源采样 Mapper：任务运行期心跳资源落库与报告聚合查询
 */
@Mapper
public interface NodeResourceSampleMapper extends BaseMapper<NodeResourceSample> {
}
