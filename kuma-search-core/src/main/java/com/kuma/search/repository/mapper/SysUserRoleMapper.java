package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-角色关联表 Mapper：授权关系的增删与按用户/角色展开查询。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
