package com.kuma.search.admin.controller.admin;

import com.kuma.search.admin.service.RoleService;
import com.kuma.search.common.result.R;
import com.kuma.search.repository.entity.SysRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色查询接口。需要权限码 {@code admin:user:manage}（角色属于用户管理域）。
 */
@RestController
@RequestMapping("/api/admin/role")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:user:manage')")
@Tag(name = "角色管理", description = """
        角色查询，供用户分配角色时选择。整个控制器需要权限码 `admin:user:manage`（角色属于用户管理域）。""")
@SecurityRequirement(name = "bearerAuth")
public class RoleController {

    private final RoleService roleService;

    /** 列出全部角色，供分配角色时选择。 */
    @GetMapping
    @Operation(summary = "列出角色", description = """
            返回全部角色（不分页），供用户分配角色时选择。""")
    public R<List<SysRole>> list() {
        return R.ok(roleService.listAll());
    }
}
