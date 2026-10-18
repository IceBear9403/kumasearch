package com.kuma.search.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.admin.captcha.CaptchaVerifier;
import com.kuma.search.admin.dto.EmailScene;
import com.kuma.search.admin.dto.RegisterRequest;
import com.kuma.search.admin.security.EmailNormalizer;
import com.kuma.search.admin.security.PasswordPolicy;
import com.kuma.search.admin.security.UserDetailsServiceImpl;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.repository.entity.SysRole;
import com.kuma.search.repository.entity.SysUser;
import com.kuma.search.repository.entity.SysUserRole;
import com.kuma.search.repository.mapper.SysRoleMapper;
import com.kuma.search.repository.mapper.SysUserMapper;
import com.kuma.search.repository.mapper.SysUserRoleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户注册。
 *
 * <h3>校验顺序：为什么与需求文档里写的略有不同</h3>
 * 计划中的顺序是「滑块 → 邮箱验证码 → 唯一性」，实现里调整为
 * 「滑块 → 密码强度 → 唯一性 → 邮箱验证码」。两处调整的理由都是<b>用户可感知的失败</b>：
 * <ul>
 *   <li><b>密码强度提到最前</b>：它是纯粹的本地格式检查，不查询任何数据、
 *       也不泄露任何信息。放在滑块之后、验证码之前，能让"密码太短"这种
 *       五秒钟就能改好的错误不必先消耗一次滑块与一枚验证码。</li>
 *   <li><b>验证码校验挪到唯一性检查之后</b>：邮箱验证码是<b>一次性且受 60 秒节流</b>的
 *       （见 {@code EmailCodeService}）。若先校验验证码，"用户名已被占用"这个
 *       完全可以当场换个名字解决的错误，会让用户白丢一枚验证码、再等 60 秒 ——
 *       而用户名枚举的防护并不依赖这个顺序：两道唯一性检查都在滑块之后，
 *       每次探测都要解一次滑块。</li>
 * </ul>
 *
 * <h3>唯一性最终由数据库唯一索引裁决</h3>
 * 应用层的两个 {@code exists} 查询只是为了让用户拿到一个明确的错误提示，
 * <b>不是</b>并发安全的判据：两个人同时注册同一个用户名，两次查询都会说"可用"。
 * 真正的裁决是 {@code uk_username} / {@code uk_email} 唯一索引，
 * 冲突时抛出的 {@link DuplicateKeyException} 在这里被翻译成对应的业务错误码。
 */
@Slf4j
@Service
public class RegisterService {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaVerifier captchaVerifier;
    private final EmailCodeService emailCodeService;

    public RegisterService(SysUserMapper sysUserMapper,
                           SysUserRoleMapper sysUserRoleMapper,
                           SysRoleMapper sysRoleMapper,
                           PasswordEncoder passwordEncoder,
                           CaptchaVerifier captchaVerifier,
                           EmailCodeService emailCodeService) {
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.captchaVerifier = captchaVerifier;
        this.emailCodeService = emailCodeService;
    }

    /**
     * 创建用户并授予 {@code USER} 角色，返回新用户 ID。
     *
     * <p><b>只负责"建用户"这一件事</b>，不签发令牌：注册即登录的令牌签发由
     * {@code AuthService} 调用既有的 {@code issueTokens} 完成。
     * 让注册流程自己拼一次 {@code TokenResponse}，就会出现第二份令牌签发逻辑 ——
     * 而令牌的字段、有效期、权限装载方式将来只要改一处，两处就会不一致，
     * 且不一致的那一侧（注册）通常没人会去回归。
     *
     * @param clientIp 仅用于日志。注册本身不参与登录失败计数（它没有"密码错误"这个概念），
     *                 但记录来源 IP 对排查批量注册有价值
     * @return 新用户的 ID
     * @throws BizException 滑块未通过、密码太弱、用户名/邮箱已占用、验证码错误
     */
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterRequest request, String clientIp) {
        // ① 滑块。必须最先 —— 它是后面那两道唯一性检查（可被用来枚举用户名/邮箱）
        //    的防护前提。
        captchaVerifier.verify(request.getCaptchaVerification());

        // ② 密码强度。本地检查，不查库、不消耗验证码。
        PasswordPolicy.validate(request.getPassword());

        String username = request.getUsername().trim();
        String email = EmailNormalizer.normalize(request.getEmail());
        if (email == null) {
            throw BizException.paramInvalid("email 不能为空");
        }

        // ③ 唯一性（应用层预检，只为给出明确提示）
        if (usernameTaken(username)) {
            throw BizException.of(ResultCode.USERNAME_EXISTS);
        }
        if (emailTaken(email)) {
            throw BizException.of(ResultCode.EMAIL_EXISTS);
        }

        // ④ 邮箱验证码。放在最后消费：见类注释。
        emailCodeService.verifyCode(EmailScene.REGISTER, email, request.getEmailCode());

        SysUser user = SysUser.builder()
                .username(username)
                // 明文密码到此为止，绝不写日志、绝不进任何异常信息
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(StringUtils.hasText(request.getNickname())
                        ? request.getNickname().trim()
                        : username)
                .email(email)
                .status(1)
                .build();

        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册撞上唯一索引。把数据库的约束错误翻译成用户能看懂的业务错误 ——
            // 原样抛出去前端只会看到"系统内部错误"，而用户此刻唯一需要知道的是换个名字。
            log.info("注册时唯一索引冲突（并发注册）：username={}", username);
            throw BizException.of(ResultCode.USERNAME_EXISTS);
        }

        grantDefaultRole(user.getId());
        log.info("用户注册成功：userId={} username={} ip={}", user.getId(), username, clientIp);
        return user.getId();
    }

    private boolean usernameTaken(String username) {
        return sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0;
    }

    private boolean emailTaken(String email) {
        return sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email)) > 0;
    }

    /**
     * 授予默认角色 {@code USER}。
     *
     * <p>按 {@code role_code} 查角色 id，不写死 id：见
     * {@link UserDetailsServiceImpl#ROLE_USER} 的说明。
     *
     * <p>角色不存在时<b>抛异常而不是静默跳过</b>。一个没有任何角色的用户可以登录，
     * 但 {@code search:read} 为空 —— 他能搜（检索是匿名可用的），
     * 却连自己的搜索历史都读不到。这种"半个可用"的账号比注册失败更难排查。
     */
    private void grantDefaultRole(Long userId) {
        SysRole role = sysRoleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getRoleCode, UserDetailsServiceImpl.ROLE_USER));
        if (role == null) {
            log.error("默认角色 {} 不存在，注册无法完成。请检查 sql/data.sql 是否已初始化。",
                    UserDetailsServiceImpl.ROLE_USER);
            throw BizException.of(ResultCode.SYSTEM_ERROR, "系统初始化数据缺失，请联系管理员");
        }
        sysUserRoleMapper.insert(SysUserRole.builder()
                .userId(userId)
                .roleId(role.getId())
                .build());
    }
}
