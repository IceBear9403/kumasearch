package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统用户表 Mapper：支持账号 CRUD 与按用户名登录查询，逻辑删除由 MyBatis-Plus 自动接管。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
