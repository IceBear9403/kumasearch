package com.kuma.search.admin.controller.admin;

import com.kuma.search.admin.service.DictService;
import com.kuma.search.common.result.R;
import com.kuma.search.repository.entity.SearchDict;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 词典管理接口。整个控制器需要权限码 {@code admin:dict:manage}。
 *
 * <p>坑：词典改动需重建分析器后才对检索生效，本接口只负责数据落库。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/dict")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:dict:manage')")
@Tag(name = "词典与同义词", description = """
        维护检索用词典（同义词、停用词等）。整个控制器需要权限码 `admin:dict:manage`。

        **改动只落库，不触发分析器重建** —— 对已写入的文档要重建索引后才生效，
        避免把「改了却没生效」误判成缺陷。""")
@SecurityRequirement(name = "bearerAuth")
public class DictController {

    private final DictService dictService;

    /** 按词典类型查询；{@code dictType} 为空时返回全部。 */
    @GetMapping
    @Operation(summary = "列出词典", description = """
            dictType 省略时返回全部类型的词典项。""")
    public R<List<SearchDict>> list(
            @Parameter(description = "词典类型；省略则返回全部")
            @RequestParam(required = false) String dictType) {
        return R.ok(dictService.list(dictType));
    }

    /**
     * 新增或更新词典：以 id 是否为空区分。
     *
     * @return 记录主键
     */
    @PostMapping
    @Operation(summary = "新增词典项", description = """
            以 id 是否为空区分新增与更新，返回记录主键。
            改动只落库，需重建分析器 / 索引后才对已写入的文档生效。""")
    public R<Long> save(@RequestBody SearchDict dict) {
        return R.ok(dictService.save(dict));
    }

    /** 删除词典。 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除词典项", description = """
            按主键删除词典项。同样需重建后才对已写入的文档生效。""")
    public R<Void> delete(
            @Parameter(description = "词典记录主键")
            @PathVariable Long id) {
        dictService.delete(id);
        return R.ok();
    }
}
