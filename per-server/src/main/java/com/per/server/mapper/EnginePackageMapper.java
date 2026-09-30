package com.per.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.per.server.entity.EnginePackage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 引擎包表 Mapper：继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力
 */
@Mapper
public interface EnginePackageMapper extends BaseMapper<EnginePackage> {
}
