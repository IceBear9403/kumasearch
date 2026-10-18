package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权限点表 Mapper：权限点 CRUD 与按编码查询。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {
}
