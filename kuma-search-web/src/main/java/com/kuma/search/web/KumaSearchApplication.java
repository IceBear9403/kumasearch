package com.kuma.search.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动类。
 *
 * <h3>为什么包扫描范围要显式指定</h3>
 * 本类位于 {@code com.kuma.search.web}，而 {@code @SpringBootApplication} 默认
 * 只扫描<b>本包及其子包</b>。若不做任何处理，core 模块的 service、admin 模块的 controller
 * 都不会被注册，启动后表现为「接口全部 404」或「找不到 Bean」。
 *
 * <p>两种解法：把本类挪到 {@code com.kuma.search} 根包下（破坏"web 模块自有包"的结构），
 * 或者显式声明 {@code scanBasePackages}。这里选后者 ——
 * 让"扫描了哪些包"成为一个显式的、可读的配置，而不是由文件的物理位置隐式决定。
 * 后者的问题在于：新人调整目录结构时不会意识到自己改动了 Bean 的可见范围。
 *
 * <p>扫描范围写 {@code com.kuma.search} 而不是逐个列举 {@code .core}/{@code .admin}：
 * 以后新增模块时不需要回来改这一行。
 *
 * <h3>MapperScan 同理</h3>
 * MyBatis 的 Mapper 接口不是 Spring 组件（没有 {@code @Component} 注解），
 * 必须由 {@code @MapperScan} 注册为代理 Bean，否则注入 {@code XxxMapper} 会失败。
 */
@SpringBootApplication(scanBasePackages = "com.kuma.search")
@MapperScan("com.kuma.search.repository.mapper")
public class KumaSearchApplication {

    public static void main(String[] args) {
        String swaggerEnable = System.getenv("SWAGGER_ENABLED");
        System.out.println("SWAGGER_ENABLED = " + swaggerEnable);

        SpringApplication.run(KumaSearchApplication.class, args);
    }
}
