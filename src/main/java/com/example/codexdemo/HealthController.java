package com.example.codexdemo;

import org.springframework.boot.SpringBootVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 第一个练习接口：先验证 Java 服务可用，暂时不调用模型。 */
@RestController
public class HealthController {
    // 浏览器访问这个地址时，Spring 会调用 health，并把结果转换为 JSON。
    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("ok", "Java 服务已启动", Runtime.version().toString(),
                SpringBootVersion.getVersion());
    }

    // record 是简单的数据对象，自动生成构造方法和字段访问方法。
    public record HealthResponse(String status, String message, String javaVersion,
                                 String springBootVersion) {}
}
