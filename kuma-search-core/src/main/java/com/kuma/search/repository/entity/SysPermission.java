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
 * 权限点表：把「能做什么」抽象成稳定编码（如 INDEX:REBUILD），供鉴权注解引用。
 *
 * <p>权限点用编码而非中文名落地，是为了让权限判断与展示解耦：
 * 改名不影响代码，代码里的常量也不会因为运营改文案而失效。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_sys_permission")
public class SysPermission implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 权限编码，如 INDEX:REBUILD、DICT:WRITE，代码中作为鉴权标识。 */
    @TableField("perm_code")
    private String permCode;

    /** 权限显示名，用于角色配置界面勾选说明。 */
    @TableField("perm_name")
    private String permName;
}
