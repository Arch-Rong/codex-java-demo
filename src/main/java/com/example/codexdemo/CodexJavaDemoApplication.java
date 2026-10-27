package com.example.codexdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 程序入口：运行 main 后，Spring Boot 会启动 HTTP 服务并扫描本包下的组件。 */
@SpringBootApplication
public class CodexJavaDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodexJavaDemoApplication.class, args);
    }
}
