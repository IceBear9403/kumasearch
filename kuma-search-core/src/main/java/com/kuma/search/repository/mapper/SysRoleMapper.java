package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色表 Mapper：支持角色 CRUD 与按编码定位角色。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
}
