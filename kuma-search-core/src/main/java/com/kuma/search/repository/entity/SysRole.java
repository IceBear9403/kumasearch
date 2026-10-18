package com.kuma.search.repository.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色表：把「权限点」打包成可分配的集合，避免逐个用户点选权限。
 *
 * <p>搜索后台的操作粒度差异很大（改一条词典 vs 重建整个索引），
 * 角色层让「运维」「只读观察者」这类职责可以一次性授予，减少误操作面。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_sys_role")
public class SysRole implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 角色编码，如 SUPER_ADMIN、INDEX_OPERATOR，代码中按此常量判断。 */
    @TableField("role_code")
    private String roleCode;

    /** 角色显示名。 */
    @TableField("role_name")
    private String roleName;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
