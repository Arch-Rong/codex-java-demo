# Codex Java Demo

这是独立的 Java 学习示例，不修改现有 TypeScript demo。

当前版本：0.1.0。第一步先搭建能启动的 Java HTTP 服务，尚未接入 Codex 或调用模型。

## 第一步：确认环境

- JDK：25，独立安装在 `/Users/rwr/tools/codex-demo-jdk25/Contents/Home`。
- Spring Boot：4.1.2（4.1 系列正式版）。
- Maven：使用电脑已有的 Maven，本 demo 通过 `.mvn/settings.xml` 单独使用 Maven Central，不修改全局私有仓库配置。

`run.sh` 只对当前程序设置 `JAVA_HOME`，不修改全局 Java 8 或已有 JDK 26。

## 第二步：启动 Java 服务

```sh
cd /Users/rwr/repo/codex-java-demo
./run.sh
```

首次运行 Maven 需要下载依赖。看到 `Started CodexJavaDemoApplication` 表示启动完成。
然后用浏览器打开：

http://127.0.0.1:8081/api/health

返回 `status: "ok"`，并显示实际的 Java 和 Spring Boot 版本，就说明环境搭好了。
停止服务：回到运行它的终端，按 Ctrl+C。

如果 8081 被占用，可以用 `PORT=8082 ./run.sh`，访问地址也改成 8082。

## 第三步：看懂文件

| 文件 | 作用 |
| --- | --- |
| `pom.xml` | 配置 Java 版本、Spring Boot 版本和依赖，相当于前端的 package.json |
| `CodexJavaDemoApplication.java` | 启动入口，相当于程序的 main |
| `HealthController.java` | 接收 HTTP 请求，返回 JSON，前端以后就调用类似的接口 |
| `application.properties` | 服务名称、地址和端口等配置 |
| `run.sh` | 选择 JDK 25 并启动程序，省去每次手动设置环境变量 |

## 多轮对话与流式接入方案

完整实施文档见 [Codex harness 多轮对话、流式输出与前端联调方案](docs/codex-harness-integration-plan.md)。

文档按 Java 握手、多轮与中断、SSE、最小验证页、现有前端联调分阶段推进；接口契约均为拟定方案，尚未实现。

## 下一步

1. Java 启动 Codex app-server，完成握手。
2. 建立会话并发送问题，接收文字增量。
3. 通过 SSE 把文字增量发给前端。
4. 加入多轮追问、停止生成，再连接现有快速对话页面。

这里先不添加数据库、登录和计费，也不把模型 Key 写入代码或网页。

## 打包

```sh
./run.sh package
```

生成 `target/codex-java-demo-0.1.0.jar`。

## 变更记录

- 2026-10-27（文档）：新增 Codex harness 多轮对话、流式输出及前端联调方案；运行版本保持 0.1.0。

- 0.1.0：建立 JDK 25 / Spring Boot 4.1.2 基础项目，添加健康接口和中文注释。
