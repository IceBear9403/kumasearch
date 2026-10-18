package com.kuma.search.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户-角色关联表：多对多授权关系，一个用户可同时拥有多个角色。
 *
 * <p>单独建表而非在用户表塞逗号串，是为了让授权关系可被 SQL 直接索引，
 * 鉴权时一次 join 即可展开用户全部权限，不必在应用层解析字符串。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_sys_user_role")
public class SysUserRole implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("role_id")
    private Long roleId;
}
