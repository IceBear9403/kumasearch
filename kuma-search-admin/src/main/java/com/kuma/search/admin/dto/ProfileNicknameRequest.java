package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改昵称，对应 {@code PUT /api/auth/profile/nickname}。
 *
 * <p>昵称是<b>展示名</b>，不是登录身份：它不参与任何查询定位，也不是唯一的。
 * 因此这个接口只有长度与"非空"两条约束，改完也<b>不作废令牌</b> ——
 * 昵称不在 access token 的 claim 里，作废只会让用户莫名被踢下线。
 */
@Data
@Schema(description = "修改昵称请求体")
public class ProfileNicknameRequest {

    /**
     * 新昵称。
     *
     * <p>上限 64 与 {@code t_sys_user.nickname} 的列宽、以及
     * {@code RegisterRequest#nickname} 的校验保持一致：三个入口写同一列，
     * 三处的上限必须相同，否则会出现"注册时能填、改的时候被拒"这种自相矛盾的行为。
     */
    @NotBlank(message = "nickname 不能为空")
    @Size(max = 64, message = "nickname 长度不能超过 64")
    @Schema(description = "新昵称，1~64 字", example = "小明")
    private String nickname;
}
