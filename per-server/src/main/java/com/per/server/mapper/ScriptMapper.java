package com.per.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.per.server.entity.Script;
import org.apache.ibatis.annotations.Mapper;

/**
 * 脚本表 Mapper：继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力
 */
@Mapper
public interface ScriptMapper extends BaseMapper<Script> {
}
