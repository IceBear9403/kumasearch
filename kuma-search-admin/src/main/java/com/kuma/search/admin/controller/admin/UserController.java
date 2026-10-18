package com.kuma.search.admin.controller.admin;

import com.kuma.search.admin.service.UserService;
import com.kuma.search.common.result.PageResult;
import com.kuma.search.common.result.R;
import com.kuma.search.repository.entity.SysRole;
import com.kuma.search.repository.entity.SysUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户与用户角色管理接口。整个控制器需要权限码 {@code admin:user:manage}。
 *
 * <p>涉及密码的两条约束：新建密码 BCrypt 加密后入库；列表返回前清除 password 字段。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:user:manage')")
@Tag(name = "用户管理", description = """
        用户增删改查与用户角色分配。整个控制器需要权限码 `admin:user:manage`。

        密码相关：新建时 BCrypt 加密入库；列表返回前清除 password 字段，明文不落日志。

        **改角色、删用户、改密码、禁用都会立即作废该用户的全部令牌**
        （access token 最长 2 小时、refresh token 最长 7 天都会失效）。
        若操作对象是当前登录者自己，会把自己踢下线 —— 例如摘掉自己所属角色的
        `admin:user:manage` 后，本控制器立即不可再访问。""")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    /**
     * 分页查询用户列表。
     *
     * <p>坑：服务层已把每条记录的 password 置空，新增字段时勿再引入任何密码相关列。
     */
    @GetMapping
    @Operation(summary = "列出用户", description = """
            keyword 同时模糊匹配 username 与 nickname。返回前服务层已把每条记录的 password 置空。""")
    public R<PageResult<SysUser>> page(
            @Parameter(description = "页码，从 1 开始；默认 1", example = "1")
            @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页条数；默认 10", example = "10")
            @RequestParam(defaultValue = "10") int pageSize,
            @Parameter(description = "关键字，匹配用户名或昵称；省略则不过滤")
            @RequestParam(required = false) String keyword) {
        return R.ok(userService.page(pageNum, pageSize, keyword));
    }

    /**
     * 新建用户：密码 BCrypt 加密后入库。
     *
     * <p>坑：明文密码不得进入任何日志；用户名重复会返回业务错误。
     */
    @PostMapping
    @Operation(summary = "新建用户", description = """
            username 与 password 必填；密码 BCrypt 加密后入库，明文不落日志。
            用户名重复返回业务错误（USERNAME_EXISTS）。未带 status 时默认置为 1。""")
    public R<Long> create(@RequestBody SysUser user) {
        return R.ok(userService.create(user));
    }

    /**
     * 更新用户：仅当携带非空密码时才重新加密，否则保持原密码不变。
     */
    @PutMapping
    @Operation(summary = "更新用户", description = """
            仅当请求携带非空密码时才重新加密，否则保持原密码不变（跳过 password 列）。
            改密码、或把 status 置为 0（禁用）会立即作废该用户的全部令牌；
            若对象是自己，当前会话随即失效、需重新登录。id 不能为空。""")
    public R<Void> update(@RequestBody SysUser user) {
        userService.update(user);
        return R.ok();
    }

    /** 删除用户（逻辑删除）。 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户", description = """
            逻辑删除（置 deleted 字段），并立即作废该用户的全部令牌。
            唯一键带 deleted 字段，因此删除后可重新用同一用户名 / 邮箱注册。""")
    public R<Void> delete(
            @Parameter(description = "用户主键")
            @PathVariable Long id) {
        userService.delete(id);
        return R.ok();
    }

    /**
     * 给用户分配角色（覆盖式：先删后插）。
     *
     * <p>坑：入参是完整的角色 ID 列表，传空列表表示清空该用户全部角色，而非"不变更"。
     */
    @PostMapping("/{userId}/roles")
    @Operation(summary = "分配角色", description = """
            覆盖式：先清空该用户全部角色，再按入参重建。传空列表表示清空全部角色，不是「不变更」。
            分配后立即作废该用户的全部令牌（角色 / 权限写在 token claim 里，不作废旧令牌 2 小时内仍带旧权限）。
            若对象是自己，会把自己踢下线。""")
    public R<Void> assignRoles(
            @Parameter(description = "用户主键")
            @PathVariable Long userId,
            @RequestBody List<Long> roleIds) {
        userService.assignRoles(userId, roleIds);
        return R.ok();
    }

    /** 查询用户当前拥有的角色。 */
    @GetMapping("/{userId}/roles")
    @Operation(summary = "查询用户角色", description = """
            返回该用户当前拥有的角色列表；无角色时返回空列表。""")
    public R<List<SysRole>> userRoles(
            @Parameter(description = "用户主键")
            @PathVariable Long userId) {
        return R.ok(userService.rolesOf(userId));
    }
}
