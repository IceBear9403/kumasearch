package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色-权限关联表 Mapper：角色权限的绑定与展开。
 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {
}
