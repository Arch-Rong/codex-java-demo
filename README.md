# Codex Java Demo

这是独立的 Java 学习示例，不修改现有 TypeScript demo。

当前版本：0.2.1。已接入本地 Docker MySQL、JDBC 连接池与 Flyway 建表；尚未接入 Codex、调用模型或实现对话 CRUD。

## AI 开发规范

所有参与本项目的 AI 编码助手，开始工作前应先阅读并遵守根目录 [AGENTS.md](AGENTS.md)。其中统一约定开发范围、协议核实、Java 编码、会话与流式处理、安全、验证和版本记录要求。

## 第一步：确认环境

- JDK：25，独立安装在 `/Users/rwr/tools/codex-demo-jdk25/Contents/Home`。
- Spring Boot：4.1.2（4.1 系列正式版）。
- Maven：使用电脑已有的 Maven，本 demo 通过 `.mvn/settings.xml` 单独使用 Maven Central，不修改全局私有仓库配置。

`run.sh` 只对当前程序设置 `JAVA_HOME`，不修改全局 Java 8 或已有 JDK 26。

## 第二步：启动本地 MySQL

确保 Docker Desktop 已启动，在项目目录执行：

```sh
./db.sh init
./db.sh up
```

init 只在 .env 不存在时生成两份随机密码，保留原配置，文件权限为 600；密码不提交 Git。Compose 和 Java 共用这份文件，Java 将它作为 properties 读取，不执行其中的 shell 内容。修改时保持 KEY=value 格式，不加引号、export 或变量嵌套。格式参考 [.env.example](.env.example)。

默认连接信息：

| 项目 | 值 |
| --- | --- |
| MySQL | Docker 官方 mysql:8.4，独立 Compose 项目 codex-java-demo |
| 地址 | 127.0.0.1:3307，仅监听本机 |
| 数据库 | codex_demo |
| 应用用户 | codex_demo，限定当前数据库；Java 不用 root |
| 密码 | 本地 .env 中的 MYSQL_PASSWORD，root 使用另一随机密码 |
| 持久化 | Docker 命名卷 codex-java-demo_mysql-data |

端口冲突时，在首次启动前修改 .env 的 MYSQL_PORT，Java 同步使用该值。数据库名、用户和密码仅在空数据卷初始化时生效；已有数据卷时修改 .env 不会修改数据库账户，需通过 MySQL 管理操作同步，不能为换密码直接删卷。

```sh
./db.sh status   # 查看状态
./db.sh client   # 以应用用户进入 MySQL，不把密码放进命令行参数
./db.sh stop     # 停止容器，保留数据卷
```

启动脚本不会修改其他项目容器。需要恢复时重新执行 ./db.sh up。不要使用 docker compose down -v，它会删除数据库卷。

## 第三步：启动 Java 服务

```sh
cd /Users/rwr/repo/codex-java-demo
./run.sh
```

首次运行 Maven 需要下载依赖。启动前必须有可用的 MySQL 和本地密码配置；Flyway 自动执行 db/migration 下的版本脚本，创建 conversation、message 和 flyway_schema_history。迁移或连接失败将阻止应用正常启动，不自动清库。看到 `Started CodexJavaDemoApplication` 表示启动完成。
然后用浏览器打开：

http://127.0.0.1:8081/api/health

返回 `status: "ok"`，并显示实际的 Java 和 Spring Boot 版本，就说明 Java 服务可用。数据库连接另用 [数据库检查](http://127.0.0.1:8081/api/health/database)，正常返回 HTTP 200；运行期间数据库不可用时返回 HTTP 503 和脱敏提示。
停止服务：回到运行它的终端，按 Ctrl+C。

如果 8081 被占用，可以用 `PORT=8082 ./run.sh`，访问地址也改成 8082。

连接配置在 `src/main/resources/application-dev.yml`，共用配置在 `application.yml`。默认启用 dev，直接 `./run.sh` 即可，也可用 `SPRING_PROFILES_ACTIVE=dev ./run.sh` 显式选择。

生产环境用 `SPRING_PROFILES_ACTIVE=prod ./run.sh`，由部署环境注入 `MYSQL_HOST`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`；`MYSQL_PORT` 默认 3306。prod 不读取本地 .env，仍默认仅监听本机。生产数据库连接与部署未验证。

当前测试对真实本地 MySQL 执行并回滚，沿用 dev 配置；尚未建立独立测试数据库，因此不添加空的 `application-test.yml`。

## 第四步：看懂文件

| 文件 | 作用 |
| --- | --- |
| `pom.xml` | 配置 Java 版本、Spring Boot 版本和依赖，相当于前端的 package.json |
| `CodexJavaDemoApplication.java` | 启动入口，相当于程序的 main |
| `HealthController.java` | 接收 HTTP 请求，返回 JSON，前端以后就调用类似的接口 |
| `application.yml` | 通用配置，默认使用 dev 环境 |
| `application-dev.yml` | 本地 MySQL 连接，读取 .env 中的密码 |
| `application-prod.yml` | 生产 MySQL 连接，从部署环境变量读取参数 |
| `run.sh` | 选择 JDK 25 并启动程序，省去每次手动设置环境变量 |
| `compose.yaml` / `db.sh` | 本地 MySQL 的启动、状态、停止与客户端入口 |
| `DatabaseHealthController.java` | 独立检查连接，保留原健康接口契约 |
| `src/main/resources/db/migration/` | Flyway 版本化建表，后续改表新增 V2 等脚本，不修改已应用的 V1 |

## 快速生成模块接入方案

完整实施文档见 [Codex harness 快速生成模块接入方案](docs/codex-harness-integration-plan.md)。

两类数据的完整字段、类型、约束和示例见 [conversation 与 message 数据结构设计](docs/conversation-message-data-design.md)；MySQL 两张表及其索引、外键和 CHECK 约束已通过 Flyway 创建，业务读写与恢复逻辑尚未实现。

对话删除采用软删除设计：仅在 conversation 增加 deleted_at，保留消息和 thread 绑定；活动任务须先停止并确认结束。deleted_at 列已建好，删除接口、列表过滤与业务存储逻辑尚未实现。

首版目标是“选择项目 → 输入创作需求 → 流式生成文本 → 项目内继续修改 → 停止 → 查看历史”，覆盖画面灵感、创作描述和视频创意三个入口；不包含实际图片或视频生成。实施按环境核实、最小页面闭环、快速生成页面、完整持久化与恢复推进。首版一个页面项目对应一个业务会话，统一以 conversationId 标识并关联唯一 Codex threadId；每次提问产生新的 turnId。数据使用 MySQL conversation 和 message 两张表，不再实施临时 JSON 存储；表结构已落地，对话接口、模板和恢复策略尚未实现。

## 下一步

1. Java 启动 Codex app-server，完成握手。
2. 在最小页面跑通 SSE、两轮追问和真实停止。
3. 连接现有快速生成页面，加入三个创作入口、复制和继续修改。
4. 基于 MySQL 实现历史读写、请求去重、断流与重启恢复，完成首版验收。

这里先不添加登录和计费，也不把模型 Key 写入代码或网页。

## 测试与打包

先启动 MySQL，再执行 ./run.sh test。测试直接连接上述本地库，验证真实迁移、问答编号唯一性、中文／emoji、角色状态、外键及软删除后的消息保留；测试数据在事务内自动回滚，不清空已有数据。测试还验证数据库健康检查的成功与脱敏 503 分支。不要将该测试配置指向生产数据库。


```sh
./run.sh package
```

生成 `target/codex-java-demo-0.2.1.jar`。

## 变更记录

- 2026-10-28（0.2.1）：将 properties 配置迁移为 application.yml、application-dev.yml 和 application-prod.yml；默认 dev，只有 dev 读取本地 .env，prod 通过部署环境变量连接数据库。已执行 `./run.sh clean test`，8 项测试通过；`./run.sh package` 成功。生产环境数据库连接未验证。

- 2026-10-28（0.2.0）：新增 Docker MySQL、随机本地凭据、JDBC 连接池、Flyway V1 迁移和数据库健康接口；conversation 10 列、message 12 列正式落地。真实 MySQL 约束测试和健康接口测试共 8 项通过，`./run.sh test`、`./run.sh package` 成功；在 8082 端口验证数据库健康接口返回 HTTP 200。Codex 和业务 CRUD 尚未实现。

- 2026-10-28（文档变更）：conversation 增加 deleted_at，共 10 个字段；message 保持 12 个字段。补充软删除契约、列表与接口过滤、活动冲突及重复删除规则。已检查字段一致性、文档差异与链接；未执行测试、打包、建表或删除联调，运行版本保持 0.1.0。

- 2026-10-28（文档变更）：message 精简为 12 个字段，问答共用 client_request_id 配对并增加角色唯一约束；停止统一使用 state，item 仅在内存处理，删除请求摘要与执行起止时间。同步停止竞争、重启核对和示例；已检查文档一致性与链接，未执行测试、打包或模型联调。运行版本保持 0.1.0。

- 2026-10-28（文档变更）：简化 conversation 为 9 个字段，移除模板版本、背景版本和背景快照，保留 snapshot_version；同步固定模板、请求摘要、接口说明和验收范围。已检查字段一致性、文档差异与链接；未执行测试、打包或模型联调。运行版本保持 0.1.0。

- 2026-10-27（文档变更）：新增 conversation、message 完整字段与结构设计，明确类型、可空规则、索引、主外键、请求去重和 AI 状态流转；同步接入方案。已检查文档一致性与链接；未建表，未执行测试、打包或数据库验证。运行版本保持 0.1.0。

- 2026-10-27（文档变更）：统一 conversationId 为唯一业务会话 ID，一个页面项目对应一个 threadId；数据按 conversation 和 message 两类设计，请求状态由 AI 消息承载，同步接口、事件、页面行为及示例。仅检查文档，未执行测试、打包或模型联调；运行版本保持 0.1.0。

- 2026-10-27（文档变更）：优化快速生成模块方案，明确三种创作模式、项目上下文、早期停止、持久化去重及断流后后台继续与查询恢复；调整实施顺序。已检查文档差异与本地链接；未执行 Java 测试、打包、真实模型或浏览器联调。运行版本保持 0.1.0。

- 2026-10-27（文档）：新增仓库级 `AGENTS.md`，统一 AI 开发规范；运行版本保持 0.1.0。

- 2026-10-27（文档）：新增 Codex harness 多轮对话、流式输出及前端联调方案；运行版本保持 0.1.0。

- 0.1.0：建立 JDK 25 / Spring Boot 4.1.2 基础项目，添加健康接口和中文注释。
