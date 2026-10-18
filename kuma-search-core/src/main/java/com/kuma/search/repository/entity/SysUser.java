package com.kuma.search.repository.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户表：后台管理端的唯一身份来源。
 *
 * <p>搜索业务本身对用户是无状态的，这张表存在的理由是「谁能进后台改索引配置、看检索日志」。
 * 因此账号被禁用（{@code status=0}）只影响登录，不影响已经写入的数据；
 * 用户删除采用逻辑删除，避免历史检索日志里的 {@code user_id} 变成悬空引用无法归因。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_sys_user")
public class SysUser implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录账号，全局唯一。 */
    @TableField("username")
    private String username;

    /** BCrypt 加密后的密码散列，任何情况下都不应明文返回前端。 */
    @TableField("password")
    private String password;

    /** 展示昵称，仅用于界面。 */
    @TableField("nickname")
    private String nickname;

    /**
     * 邮箱。邮箱码登录与找回密码的凭据载体。
     *
     * <p><b>落库前必须归一化（trim + 转小写）。</b>否则 {@code Foo@x.com} 与
     * {@code foo@x.com} 会被当成两个不同的邮箱（唯一索引也拦不住），
     * 而邮件服务商把它们视为同一个地址 —— 表现是用户「收到了验证码却提示邮箱不存在」。
     * 归一化在 {@code EmailNormalizer}，写入与查询两侧都要过它。
     *
     * <p>可为空：存量用户（以及只用密码登录的用户）没有邮箱。
     * MySQL 的唯一索引允许多个 NULL，因此这些用户不会互相冲突。
     */
    @TableField("email")
    private String email;

    /** 账号状态：1 启用、0 禁用。禁用后仍保留数据与角色关系，随时可恢复。 */
    @TableField("status")
    private Integer status;

    /**
     * 最后登录时间。
     *
     * <p>两个用途：一是「这个账号是不是僵尸账号」（长期未登录 + 管理员权限 = 风险），
     * 二是登录审计（异常时间/异常地点的登录需要比对基线，见 {@code docs/08-roadmap.md}）。
     * 写入成本可忽略，因此不考虑「要不要为了性能省掉它」。
     */
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0 未删、1 已删。MyBatis-Plus 查询会自动过滤已删除行。 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
