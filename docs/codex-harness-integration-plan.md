# Codex harness 快速生成模块接入方案

日期：2026-10-28

适用项目：`/Users/rwr/repo/codex-java-demo`  
前端参考项目：`/Users/rwr/repo/sinmo-web-lumx`  
状态：0.2.1 已完成 Docker MySQL 连接与两表迁移；Codex、多轮／SSE、业务 CRUD 和浏览器联调尚未实现。

## 1. 要完成什么

首版围绕截图中的“快速生成”模块交付：**选择项目 → 输入创作需求 → 流式生成内容 → 项目内继续修改 → 停止 → 查看历史**。这里的生成内容指文案、画面描述和视频创意等文本；图片、视频实际生成属于后续接入范围。

用户可见的完成标准：

1. 创建项目及其唯一会话，发送第一条消息，实时看到回答增长。
2. 在同一对话追问，模型能使用前文。
3. 停止当前生成，保留已输出内容，随后仍可继续对话。
4. 新建项目创建独立 thread；两个项目的增量和上下文不会混在一起。
5. 刷新或服务重启后，能够区分历史恢复、生成中断和正常完成。
6. “寻找画面灵感、打磨创作描述、探索视频创意”三个入口能够生成对应内容，并支持后续修改。

运行版本已为 0.2.1，按用户要求接入本地 Docker MySQL；不修改其他前端仓库或模型配置。第一版以本机单用户、文本创作为范围，不引入登录、计费、图片生成、资产库或完整审批 UI。截图中的其他按钮和模型名称不自动构成实现要求。

### 1.1 三个入口共用一条对话链路

| 产品入口 | 业务 mode | 首版输出目标 |
| --- | --- | --- |
| 寻找画面灵感 | `visual_inspiration` | 若干可选择的画面方向及简短说明 |
| 打磨创作描述 | `creative_description` | 可直接复制、继续修改的创作描述 |
| 探索视频创意 | `video_idea` | 视频概念、镜头建议和节奏描述 |

使用轻量提示词模板实现三个入口，不为每个入口建立独立 Agent。入口选择只预填模式与输入提示，不立即调用模型；用户确认输入后发送。自由输入默认采用 `creative_description`。模式在创建项目时确定，追问沿用该模式；首版切换模式需新建项目。

首版输出采用普通文本或 Markdown，支持复制正文和继续追问，不依赖解析模型正文来触发业务操作。不加入结构化结果、自动标题生成或多 Agent 编排等非必要模型调用。

## 2. 仓库现状与边界

已检查 Java 项目：

| 当前内容 | 结论 |
| --- | --- |
| `pom.xml` | 项目版本 0.2.1，JDK 25、Spring Boot 4.1.2，Web MVC、JDBC 与 Flyway |
| `HealthController.java` | 保留 GET /api/health；新增 GET /api/health/database 检查真实连接 |
| `application.yml` / `application-dev.yml` / `application-prod.yml` | 默认 dev，仅 dev 读取 .env；默认监听 `127.0.0.1:8081` |
| `run.sh` | 为当前进程选择 JDK 25，支持启动及 Maven 打包 |
| `docs/` | 可作为本方案和后续协议快照的存放位置 |

已实现 Compose MySQL、JDBC 连接池、Flyway 建表与数据库测试；目前没有 Codex 子进程管理、RPC 客户端、会话管理、SSE 业务接口或业务持久化映射。不能把“Java 服务启动成功”当成“harness 已跑通”。

前次方案记录的前端参考实现如下；本次文档优化未重新读取前端仓库，实施时需核实文件和行为：

| 文件 | 可借鉴内容与注意事项 |
| --- | --- |
| `packages/conversation-main/src/api.ts` | 现有 `Agent.Chat` / `Agent.Resume` 请求与事件适配，使用 `runId`；不能直接当成 Codex thread 协议 |
| `packages/conversation-main/src/useConversationSession.ts` | 现有对话状态入口，后续接入时需继续核对发送、停止、清空、历史恢复的完整逻辑 |
| `packages/conversation-main/src/lib/sse.ts` | 已有 SSE 分帧、UTF-8 流式解码，可参考其测试；新接口不需要继承旧后端的非标准分帧兼容行为 |
| `src/api/agentChatApi.ts` | 另一套 `Chat.Simple` 流式入口，历史记录中的字段是 `conversationId`，与上面的 `runId` 不同；这是旧接口事实，不是新方案的业务 ID |

这些文件位于前端仓库。截图对应哪个实际路由和组件尚未确认，接前端前必须沿路由定位，不能仅凭截图把某个已有入口整体替换。

前次方案记录：本机发现 `/usr/local/bin/codex`，当时 `codex --version` 异常退出，退出码为 137。本次未复测，不能将这条历史记录当作当前环境结论；实施前仍需核实 CLI 版本、可用模型、认证和协议兼容性。137 本身不能证明是内存不足或某种特定故障。

## 3. 采用什么接法

建议使用下面的链路：

```text
现有前端 / 最小联调页面
    │ HTTP 请求 + SSE 响应
    ▼
Java Spring Boot demo
    ├─ HTTP 参数校验、业务会话 ID、状态与历史视图
    ├─ RPC 请求关联、事件分发、停止与错误处理
    └─ 子进程生命周期管理
           │ stdin / stdout：逐行 JSON
           ▼
      codex app-server
           │
           ▼
      已配置的模型服务
```

Java 对接 app-server，不自行重写模型工具循环。harness 管理模型侧上下文；Java 管理产品侧对话、请求和展示状态。这也是本方案选择 app-server 而不是每条消息启动一次 CLI 的原因。[官方 harness 集成定位](https://developers.openai.com/blog/codex-as-a-platform)

| 层 | 责任 |
| --- | --- |
| 前端 | 项目及消息展示、模式选择、输入、复制结果、停止及恢复展示 |
| Java | 业务 ID、固定模式模板、消息组装、请求去重、历史与业务工具入口 |
| Codex harness | Agent 执行循环、模型上下文、工具调用与执行事件 |
| 后续图片／视频服务 | 实际生成任务、任务状态和产物；首版未接入 |

接通 harness 不代表接通媒体生成服务。后续需要图片、视频能力时，再设计明确的业务工具、授权和产物回写；不把聊天文本中的“生成成功”当成真实产物。

协议基线：每个连接先完成 `initialize` / `initialized`；新项目的唯一会话用 `thread/start`，续接用 `thread/resume`；每轮用 `turn/start`；文本来自 `item/agentMessage/delta`，最终 item 用 `item/completed` 校准；`turn/completed` 的状态区分成功、失败、中断。停止使用 `turn/interrupt`。具体字段以安装版本生成的 schema 为准。[官方 app-server 协议](https://developers.openai.com/codex/app-server/)

**下文对话 HTTP 路径、SSE 名称、类名、超时和业务持久化流程均为本 demo 的拟定方案，不是 OpenAI 官方接口。MySQL 连接、两表迁移和数据库健康接口已实现，其余能力以文首状态说明为准。**

## 4. 环境预检与 Java 握手

### 4.1 环境预检

实施时依次执行并记录结果：

```sh
cd /Users/rwr/repo/codex-java-demo
./run.sh package
codex --version
codex app-server --help
codex app-server generate-json-schema --out ./docs/codex-schema
```

最后一条会创建文件。记录 CLI 版本，并保存该版本 schema；如果本机命令不可执行，先修复 CLI 环境，不继续按在线示例猜字段。当前会话没有可调用的 Context7 工具，Codex 协议已使用官方文档核对；实施 Spring 或前端库的具体 API 时，应先获取可用的 Context7 文档，缺失时再核对对应版本官方文档。

认证沿用本机经确认可用的 Codex 配置。先核实 demo 进程使用的系统用户、配置目录和模型提供方；不要复制密钥到文档、Java 源码或浏览器。若另设配置目录，必须单独完成该目录的认证与模型配置验证。

不要默认截图中的 `Sim-M2.5-fast` 是 Codex 可识别的模型 ID。第一版由服务端固定一个真实调用验证通过的模型，UI 展示其真实名称。后续结合 `model/list` 返回的目录和服务端允许列表开放选择，明确产品展示名与提供方模型 ID 的映射；目录存在不等于当前认证一定能成功推理。具体目录字段以安装版 schema 为准。[官方模型目录说明](https://learn.chatgpt.com/docs/app-server#models)

### 4.2 Java 子进程与 RPC

建议一个 Java 实例管理一个长期运行的 app-server 子进程，支持多条 thread；先限定本机单用户。后续多租户部署需要另行设计身份、工作目录和凭据隔离。

实现要求：

- 使用参数数组启动进程；不把用户输入拼成 shell 命令。
- 独立读取 stdout 与 stderr，不能把日志合并进 RPC 数据流。
- stdout 由唯一读取循环逐行解析，不能让每个 HTTP 请求各读一份。
- 写入 stdin 时串行写完整 JSON 行并 flush。
- 每个 RPC 请求使用唯一 ID，发出前登记待完成请求，再按响应 ID 完成 future。
- 区分响应、通知和服务端请求；服务端请求同样可能带 ID，不能误当响应。
- 响应超时、解析失败、进程退出时清理 pending 请求，不能永久等待。
- 未知通知记录类型后忽略；不把整包原始内容当聊天正文。
- 应用关闭时清理子进程、线程和定时任务。

握手顺序是：启动 → 发送 initialize → 等响应成功 → 发送 initialized → 标记 ready。健康检查保留；另设 readiness 能力检查，至少区分 Java 存活、harness 就绪和最近一次推理验证结果。握手成功不代表模型访问成功。

**环境验收：**Java 能启动并握手，真实单轮推理成功；关闭和重启不会遗留子进程；错误配置能给出明确诊断。到这里还不算快速生成模块完成。

## 5. 会话、创作上下文与中断

### 5.1 统一使用 conversationId，一个业务会话对应一个 thread

首版维持“一个页面项目 = 一个业务会话 = 一个 Codex thread”的关系。按用户最新要求，业务 ID 统一命名为 conversationId，不额外引入项目 ID 或项目与对话的层级；页面即使沿用“项目”名称，接口、事件、存储和服务均使用 conversation 命名。这是业务命名统一，不改变同一会话追问复用 thread、每轮生成新 turn 的规则，与 AGENTS.md 的 ID 约定一致。

| ID | 谁生成 | 用途 |
| --- | --- | --- |
| `conversationId` | Java | 唯一业务会话 ID；关联标题、历史和全部消息 |
| `threadId` | Codex | 该项目唯一的 harness 上下文；追问始终复用 |
| `turnId` | Codex | 项目里一次提问与执行；每轮产生新的 ID |

```text
业务会话 conv-001 → thread-aaa
  ├─ 用户：写一段雨夜城市描述 → turn-001
  └─ 用户：改成暖色调         → turn-002
业务会话 conv-002 → thread-bbb
  └─ 用户：写一段海边日出描述 → turn-003
```

另外使用 clientRequestId 标识每次发送，重试复用原值、新提问使用新值；itemId 区分一轮中的多个输出对象。旧接口的 runId 不能直接填进 threadId。项目背景不会因为保存了 conversationId 就自动进入模型。

按用户最新要求采用 Docker MySQL，已通过 Flyway 创建 conversation 和 message 两张表；不再实施临时 JSON 存储，不单独建立 project 或 generation_request 表。部署方式见 README，实际 SQL 位于 src/main/resources/db/migration/。

| 数据对象／未来表 | 主要字段 | 说明 |
| --- | --- | --- |
| `conversation` | id、title、thread_id、thread_state、model_id、mode、snapshot_version、created_at、updated_at、deleted_at | id 即 conversationId；thread_id 初始化时可空，绑定后不静默替换；deleted_at 为空才对普通查询可见 |
| `message` | id、conversation_id、role、sequence、content、client_request_id、turn_id、state、error_code、error_message、created_at、updated_at | 保存用户输入与 AI 回答；AI 回答记录同时承担本轮执行状态 |

完整字段类型、可空规则、默认值、主外键、唯一约束、状态和写入顺序见 [conversation 与 message 数据结构设计](conversation-message-data-design.md)。该文档定义字段与业务规则，V1 SQL 已实现建表；业务写入、查询、原子状态和恢复尚未实现。

每次发送在一个原子写入中保存用户消息和空的 AI 回答，两者使用相同 client_request_id；按 conversation_id + client_request_id + role 唯一约束防止重复。问答通过同编号配对，不另存问题引用。去重直接比较对应用户消息的正文，不保存摘要。turn_id、state 和错误信息仅保存在 AI 消息；执行接口中的 request 是请求定位概念，不代表独立建表。

运行时按 itemId 在内存保存各正文对象并分别校准，只把组合后的 content 落盘，不保存 items 字段；Java 重启后不能从正文推断 item 归属，必须核对并重建真实上游结果，无法可靠重建时保留部分正文与待核对状态，不盲目追加。

持久化和重启恢复可以在核心页面可用后补齐，但从首次实现起就预留存储边界；仅有内存状态的中间版本不得宣称支持重启防重复提交或历史恢复。

### 5.2 多轮时序

1. 新建项目，调用 thread/start 并绑定唯一 threadId；同一项目重复初始化必须复用已有映射，不能并发创建多个 thread。创建结果未知时先标记待核对，不自动再建 thread。
2. 第一轮发送“项目代号是青禾，先记住它”。
3. 等待这一轮明确终止。
4. 第二轮只发“项目代号是什么？”，对同一个 threadId 调用 turn/start。
5. 核对真实回答包含“青禾”。不能把前文手动拼进第二轮来替代会话验证。
6. 新建另一个项目，问相同问题，确认请求和历史没有携带前一会话内容。

每个 thread 同时只允许一个活动 turn，Java 用原子状态转换控制，不能只靠禁用按钮。并发发送返回 409；第一版不做输入排队或生成中追加指令。

注册本轮事件接收器、占用会话状态后再发送 turn/start，防止最早的事件丢失。通知可能早于请求响应被应用层处理；路由应结合线程、活动请求及已获得的 turnId，不能只在 RPC 返回后才开始收事件。

### 5.3 中断与失败

- 停止按钮调用 Java 停止接口，由 Java 发送 turn/interrupt。
- 用户可能在 turn/start 返回真实 turnId 前点击停止。停止接口使用 conversationId + clientRequestId 定位任务，先原子将 AI 消息改为 stopping 并落盘；若尚未发送上游请求则取消本地任务，若已经发送则在获得真实 turnId 后立即中断。不伪造 turnId，也不中断该对话后续的新任务。
- 不保存独立停止标志。迟到的 turn/start 响应或开始通知只能补齐 ID，不能把 stopping 改回 running；取得真实 turnId 后立即请求中断。服务重启也先恢复停止意图并核对真实状态。
- interrupt 请求已受理只表示正在停止；收到最终中断状态后才显示“已停止”。若自然完成先到，保留正常完成状态。
- 用户停止后保留已生成文本，并允许继续下一轮。
- 前端只 abort fetch 不足以证明 harness 停止；显式停止接口必须存在。
- 同一 turn 的结束处理只能生效一次，避免中断与自然完成竞争造成重复消息。
- 进程异常退出，将活动任务标为连接丢失/结果未知，不能伪造 completed。
- 启动后对已保存 thread 恢复并核对状态；无法恢复时提示新建项目；保留原项目和记录，不静默替换其 threadId，不能静默丢弃上下文。

对于首版不支持的工具审批或用户输入请求，必须用安装版 schema 定义的方式拒绝或结束当前任务，并给出可理解的提示，不能悬空等待。首轮采用专用空工作目录与受限策略；不要为了“跑通”开放不受限命令执行。这个范围不保证工具能力和完整审批链路已经接通。

**核心交互验收：**真实模型完成两轮记忆验证；项目间上下文隔离；生成停止后可续聊；turnId 返回前停止有效。服务重启后的恢复列入首版最终交付验收。

### 5.4 固定模板与用户消息

- Java 根据 mode 选择固定模板，明确创作目标、语言和输出要求；模板由服务端配置管理，不允许客户端传入任意系统指令或模板路径。首版不实现模板版本管理，也不在 conversation 保存模板版本。
- 初始化 thread 时通过安装版协议支持的指令字段传递固定规则，具体字段实施时核实。追问与恢复沿用 thread 上下文，不在每次恢复时重新注入修改后的模板；首版模板固定，未来需要在线更新时另行设计对已有会话的影响。
- 首版不实现项目背景资料选择、自动读取、版本管理或背景快照。用户有品牌、风格等要求时，直接在消息中提供；正文随 message 保存，无需另设背景字段。
- conversationId 只定位业务会话，不会自动让模型知道页面项目的其他资料。追问复用同一 thread，只发送新增消息，不重复拼接整段历史；恢复依靠真实 thread，不靠额外背景快照模拟记忆。
- 不自动携带其他对话、全部资产或整个目录。用户材料作为待处理数据，与服务端规则区分，不把材料中的指令自动当作执行授权；首版文本模式不开放任意本机文件读取或写入工具。
- 保留 snapshot_version 作为页面快照序号，对外字段为 snapshotVersion；内容或可见状态变化时递增，用来阻止迟到的旧查询覆盖新数据，与模板和背景版本无关。

### 5.5 请求状态与持久化边界

下面的状态是 Java 业务状态，不是 Codex 官方 turn 状态：

| 状态 | 含义与后续处理 |
| --- | --- |
| `accepted` | 已保存用户消息与 AI 回答占位记录并占用项目会话，尚未发送上游 |
| `starting` | 已准备发送或已发送 turn/start，可能尚未拿到 turnId |
| `running` | 已关联真实 turnId，接收增量 |
| `stopping` | 已登记停止意图，等待取消或上游终态 |
| `completed` / `failed` / `interrupted` | 已明确结算的终态 |
| `unknown` | 上游是否受理或最终结果不明，阻止新任务直至核对 |

业务请求以 conversationId + clientRequestId 定位问答对；表内再加 role 区分用户和 AI 两条记录。相同请求编号先比较对应用户正文，内容相同返回原 AI 消息及状态，内容不同返回 409；保留空格和换行，不保存摘要。

在发送 turn/start 前，持久化用户消息、AI 回答占位记录和 `starting` 状态；获得 turnId 后补齐 AI 消息上的映射。消息保存失败时不发送上游调用；运行中写入失败应报告状态持久化异常，保留内存关联并尽力停止，不能对外承诺结果已保存。进程在发送与保存响应之间退出时，恢复后未登记停止的任务按 unknown 核对，已登记停止的任务保留 stopping 并核对，不能自动重试。上游没有经验证的幂等能力时，不承诺跨崩溃的端到端 exactly-once。

正文增量先在内存中按 item 累积，按有界周期保存快照，item 完成和任务终态时保存最终内容；不每个 token 都同步刷盘。刷新查询返回当前内存快照，服务重启只能恢复已落盘快照，未知期间可能缺失的内容和 item 归属需通过真实 thread／turn 结果核对；若不能完整重建则保留部分正文和待核对状态，不自动恢复增量追加。恢复先加载映射，按需恢复 thread；不能因打开历史而干扰其他活动对话。

## 6. HTTP / SSE 与断流恢复

### 6.1 拟新增 HTTP 契约

| 方法与路径 | 输入 | 输出 |
| --- | --- | --- |
| `POST /api/codex/conversations` | `{ "title": "雨夜城市", "mode": "creative_description" }` | `{ "conversationId": "conv-...", "title": "雨夜城市", "mode": "creative_description" }` |
| `GET /api/codex/conversations` | 无 | 未软删除的业务会话历史列表；每个项目只有一条消息时间线，不列出本机其他 Codex 记录 |
| `GET /api/codex/conversations/{id}` | 业务会话 ID | 消息快照、mode、活动 clientRequestId、可空 turnId、业务状态、snapshotVersion、更新时间 |
| `POST /api/codex/conversations/{id}/turns/stream` | `{ "clientRequestId": "uuid", "message": "你好" }` | SSE；每次调用在项目的同一 thread 上新增一轮 |
| `GET /api/codex/conversations/{id}/requests/{clientRequestId}` | 请求定位 | 对应 AI 消息承载的任务状态、可空 turnId、已有结果与错误（停止意图由 stopping 状态表达） |
| `POST /api/codex/conversations/{id}/requests/{clientRequestId}/interrupt` | 请求定位 | 202 表示已登记停止意图；已终结任务返回 200 和真实状态，不影响新任务 |
| `DELETE /api/codex/conversations/{id}` | 业务会话 ID | 204 表示软删除成功或已经删除；不存在 404；有活动／待核对任务或 thread 初始化未决时 409 |

创建接口只创建一条业务会话并绑定独立 thread。页面的“新项目”或“新建对话”共用该创建行为；打开已有条目使用保存的 conversationId 恢复原 thread。任何“重新开始”行为都不得静默清空现有会话或替换 threadId。旧系统如有其他 ID，由服务端 adapter 明确转换，不在新契约中增加另一套业务 ID。

mode 在创建项目时可省略，默认 creative_description；title 可省略，默认“未命名项目”。发送消息只传 clientRequestId 与 message，避免追问时改变模式。相比前版草案，HTTP 前缀改为 /api/codex/conversations，事件统一携带 conversationId；停止仍按业务请求定位，覆盖 turnId 尚未返回的阶段。以上接口尚未实现，实施前需同步前端 adapter。

HTTP 建流前校验：空文本、超长输入或未知 mode 返回 400，项目／请求不存在返回 404，活动轮次或待核对状态冲突返回 409，harness 未就绪返回 503；存储异常返回 500。所有查询和操作校验消息和请求属于路径中的项目。未发送 SSE 头前返回结构化错误；建流后使用错误事件，不再尝试改 HTTP 状态。

相同 clientRequestId 不得再次创建 turn：同内容重复请求返回 HTTP 200 的 JSON 任务定位与已有状态，不重新建立生成流；内容冲突返回 409。前端先检查 Content-Type，再选择读取 JSON 或 SSE。第一版不重放 SSE，前端遇到未知结果应查询历史，不自动重发生成请求；服务重启后的不确定请求也先对账。

### 6.1.1 对话软删除

只在 conversation 保存 deleted_at，空表示正常，填写时间表示软删除；updated_at 始终保留最后修改时间的含义。删除原子设置两个时间为同一服务端 UTC 时间并递增 snapshot_version；重复删除返回 204，不再次更新时间或版本。message 不添加删除字段、不物理删除，Codex thread 也不自动清理。

有活动任务、unknown 结果或未决 thread 初始化时返回 409。用户须先停止并确认结束再重新发起删除，202 停止受理不代表已满足删除条件。删除与发送共用同一会话锁／事务，锁内检查状态与删除标记，避免检查后又启动新任务。

列表过滤已删除记录；详情、消息与请求查询、发送（包括重复请求）、修改和停止操作均先校验 deleted_at，已删除返回 404。内部核对与重复 DELETE 可访问原始记录，但不能绕过归属限制。迟到回调不恢复已删除记录；前端收到 204 后移除列表项并解绑原视图。首版不提供单条消息删除、回收站或恢复功能。deleted_at 列已迁移创建，真实删除接口和访问过滤尚未实现。

### 6.2 拟定 SSE 事件

```text
event: request.accepted
data: {"conversationId":"conv-1","clientRequestId":"req-1","turnId":null,"state":"accepted"}

event: turn.started
data: {"conversationId":"conv-1","clientRequestId":"req-1","turnId":"turn-1"}

event: message.delta
data: {"conversationId":"conv-1","clientRequestId":"req-1","turnId":"turn-1","itemId":"item-1","delta":"你好"}

event: message.completed
data: {"conversationId":"conv-1","clientRequestId":"req-1","turnId":"turn-1","itemId":"item-1","text":"你好，很高兴和你一起创作。"}

event: turn.finished
data: {"conversationId":"conv-1","clientRequestId":"req-1","turnId":"turn-1","status":"completed"}

```

规则：

- 每个事件以空行结束，data 使用 JSON 编码，正文中的换行与空格原样保存。
- 所有业务事件携带 conversationId 与 clientRequestId；turnId 未知时可空，已知后始终携带。
- request.accepted 仅表示业务请求已登记，不表示上游已开始。若在上游调用前取消，发送 `request.finished`，包含业务 `state: "interrupted"`、`turnId: null` 和 `reason: "cancelled_before_start"`，不能伪造 Codex turn/completed。
- message.delta 追加到对应 item，message.completed 按 itemId 校准全文，不再次追加。
- turn.finished 的 status 可为 completed、failed、interrupted；只有 completed 显示成功。
- 错误详情用 `event: stream.error`，包含稳定 code、用户可读 message、retryable 和已有的关联 ID；不要用 message 字段自动拼聊天正文。
- 若已知轮次失败，错误事件之后仍发送 failed 终态；若进程丢失导致真实结果未知，用 stream.error 结束并查询恢复，不能冒充 Codex 的 failed 通知。
- 只有工具进度、没有正文时显示“正在处理”，不把工具输出和推理内部数据混成回答。
- 设置 `Content-Type: text/event-stream` 与禁用缓存；定时发送注释心跳，代理层关闭缓冲并放宽读超时。
- 建议握手/RPC 受理超时 15 秒、轮次上限 10 分钟、心跳 15 秒；均可配置，轮次上限触发中断流程。首次输出慢不应直接判为失败。

Java 保留现有 Web MVC 结构，可用该版本支持的 SSE emitter 实现。stdout 读取循环只分发事件，浏览器写入由独立发送队列承担；每会话队列要有上限。慢客户端断开后不能卡住其他会话或无限占内存。

### 6.3 断流与任务生命周期分开

首版产品约定：点击停止才明确请求中断；刷新、切项目、切历史和网络断开只解绑浏览器订阅，任务在 Java 与 harness 均存活时继续执行，直到真实终态或配置的轮次上限。此约定替代前版草案的“浏览器断流即尽力中断”。Java 关闭或子进程退出仍可能中止任务，不能承诺跨进程继续生成。

浏览器连接不拥有任务。SSE 断开、超时和发送队列溢出只清理订阅、发送队列与心跳，不删除任务路由、状态或快照；无订阅时仍保存结果。每个任务的执行期限独立于 SSE 生命周期，上限到达后走中断流程；中断确认也需有界超时，无法确认时保留 stopping 并记录待核对错误，清理有界网络等待；不丢弃停止意图或允许新发送。未请求停止的未知任务才使用 unknown。

不承诺断点续传，也不实现 Last-Event-ID 重放。前端没有收到终态就遇到 EOF 时，显示“连接已中断，正在核对结果”，查询对话与请求状态，不能直接调用成功回调，也不能自动再次提交用户消息。活动任务按可配置间隔查询（建议 1–2 秒），每次用完整快照替换对应 item；使用单个在途查询和单调递增 snapshotVersion 防止旧快照覆盖新内容，不把快照再次追加到 SSE 正文。

重新进入对话时先查询状态：活动任务显示已有内容并继续查询，停止按钮仍可用；终态任务展示最终结果；unknown 或附带待核对错误的 stopping 显示“结果待核对”并阻止重复提交，不无限自动轮询。离开视图后停止轮询，迟到结果按会话和视图代次过滤。刷新恢复展示不代表 Java 重启恢复已通过，两者分别验收。

**流式验收：**`curl -N` 能在回答结束前收到增量；中文、空格和换行正确；断流不取消任务，查询能恢复快照；错误、中断均能正确收尾。

## 7. 最小验证页与快速生成页面

### 7.1 先做最小验证页

建议先增加 Java 静态联调页 `src/main/resources/static/codex-debug.html`：只有新项目、消息区、输入框、发送、停止和连接状态。与 Java 同源，先排除跨域、现有鉴权、复杂项目状态的干扰。它是联调工具，不是替代产品页面。

浏览器用 POST fetch 读取 SSE 响应，不使用只支持常规 GET 建连方式的原生 EventSource 来发送本方案的消息请求。处理 HTTP 非成功、缺少响应流、跨块 UTF-8、跨块事件、正常终态和提前 EOF。

### 7.2 再接现有项目对话页面

1. 沿截图页面实际路由定位组件与会话 hook。
2. 新增独立 Codex adapter，转换为现有消息视图需要的状态；不要直接复用旧 Agent 的 runId 或完成事件语义。
3. 用显式本地配置开关启用；使用独立 `/api/codex` 前缀，不替换全局 `/agent` 代理。
4. 本地开发优先代理到 `127.0.0.1:8081`；端口冲突可使用 `PORT=8082 ./run.sh` 并同步代理目标。
5. 以 conversationId 作为唯一业务会话 ID；切项目和选历史时绑定该项目保存的 thread，不再生成额外对话 ID。
6. 模型选择器只展示已验证选项；未接通的参考图、免费问答、提示词优化等能力保持原接口或清晰禁用，不能假装转给 Codex。

截图中的交互对应关系：

| 用户动作 | 接入行为 |
| --- | --- |
| 三个创作入口 | 选择 mode 并预填输入提示，无当前项目时发送创建对应模式的项目；已有项目沿用原模式，切换模式需新建项目 |
| 输入框发送 / 追问 | 发送当前对话的新 turn；未有项目则先创建项目 |
| 回答逐渐出现 | 按 conversationId + turnId + itemId 更新消息 |
| 新项目 | 创建新 conversationId 和独立 thread，原项目与消息保留 |
| 新建对话按钮 | 首版在 Codex 模式下隐藏或改名“新项目”，与新项目入口共用创建会话行为；保留原会话，切换到新的 conversationId/threadId |
| 历史对话入口 | 首版展示业务会话历史，选中 conversationId 后恢复唯一时间线；若旧界面有多对话历史则不自动合并数据 |
| 删除对话 | 调用 DELETE，204 后移除列表项；409 时提示先停止并核对终态，不直接清空消息或伪装删除成功 |
| 停止按钮 | 发 interrupt，展示“停止中”，最终确认后恢复发送 |
| 切换项目 | 切换项目唯一消息时间线，旧流回调不得覆盖新项目视图 |
| 刷新 / 切换历史后返回 | 查询快照和任务状态，活动任务继续轮询，不自动重发 |
| 复制结果 | 复制当前 item 正文，不带工具日志或内部状态 |

快速切换时使用订阅解绑或视图代次标识过滤迟到回调；会话 ID 不能只存在组件临时 state。刷新后先查询恢复，再允许新一轮发送。生成中的长回答支持滚动阅读，用户向上阅读时不强制滚回底部。

**本阶段验收：**在截图对应的真实页面完成两轮追问、新建、历史恢复、停止及错误提示；Network 可看到新接口而非旧 Agent 请求。

## 8. 建议文件拆分

以 `src/main/java/com/example/codexdemo/` 为根，按实际实现规模调整：

```text
codex/CodexProcessManager.java       启停、握手、stdout/stderr
codex/CodexRpcClient.java            请求 ID、future、响应/通知/服务端请求分流
codex/CodexEventDispatcher.java      会话事件路由
conversation/ConversationService.java  映射、轮次串行、中断、恢复
conversation/ConversationStore.java    MySQL 业务读写与快照版本
conversation/ConversationController.java  HTTP 和 SSE
conversation/dto/                   参数与前端事件对象
generation/GenerationMode.java       三种模式与服务端校验
generation/GenerationInputBuilder.java  固定模式模板与用户消息组装
```

不要一开始引入复杂通用框架。先把进程、协议、业务会话和 HTTP 输出四个责任分开即可。配置建议包含可执行文件绝对路径、专用工作目录、模型、超时、存储目录和队列上限；浏览器不能指定任意可执行路径或工作目录。

## 9. 验收清单与执行证据

以下全部是待执行项；此次写文档没有执行真实推理或浏览器联调。

| 场景 | 通过条件 |
| --- | --- |
| 首轮流式 | 结束前已出现多个增量，记录发送、首增量和终态时间 |
| 同线程追问 | 第二轮不重传全文，仍正确使用“青禾”上下文 |
| 新项目隔离 | 新 thread，未带入前会话消息，事件与历史不串线 |
| 停止后续聊 | 收到 interrupted，保留部分文字，下一轮可发 |
| 刷新 / Java 重启 | 找回映射与历史，恢复 thread 后继续追问 |
| 连点发送 | 一个 clientRequestId 至多创建一轮，活动轮冲突明确 |
| 重复请求 / 崩溃窗口 | 同键同内容返回已有任务，同键异内容 409；上游是否受理未知时不自动重发 |
| 受理前后立即停止 | turnId 未返回也能登记停止；本地取消不伪造上游终态，迟到停止不影响新任务 |
| 中文与碎片 | UTF-8 被拆分、事件跨 chunk、空格与换行均不损坏 |
| 最终校准 | item 全文不重复追加，一轮多个 item 分别更新 |
| 鉴权 / 模型不可用 | 错误可读，不无限 loading、不伪装完成 |
| 浏览器断网 / 进程退出 | 部分文本保留，状态可核对，不自动重复推理 |
| 两会话交错 | 各自消息、状态、停止操作互不污染 |
| 切项目 / 刷新 / 断网 | 不取消仍存活的后台任务，返回后按快照恢复，不重复追加或再次推理 |
| 固定模式与补充要求 | 三种入口符合输出目标；用户通过消息补充风格要求，追问复用同一 thread；不宣称自动读取项目背景 |
| 精简字段后的恢复 | 两种角色共享发送编号且不重复入库；迟到通知不覆盖 stopping；重启缺少 item 边界时不盲目追加或伪造完成 |
| 对话软删除 | 已删除对话不进列表，详情／消息／请求查询与发送／停止返回 404；重复删除 204 不更改首次时间，消息与 thread 绑定保留 |
| 删除竞争与存储失败 | 活动／未知任务及未决初始化阻止删除；发送和删除互斥，迟到事件不恢复记录；删除写入失败不返回 204 |
| 存储失败 | 发送前写入失败不调用模型；运行中失败明确报告，不宣称结果已保存 |
| 无订阅任务 | 仍受执行期限约束，终态能保存，慢客户端不阻塞其他任务 |
| 前端实际页面 | 新建、历史、停止、切项目均调用预期 adapter |

推荐测试分层：模拟 app-server 的乱序响应、通知先到、退出和服务端请求，验证 RPC 分流；对会话状态竞争与 SSE 分帧做自动测试；再用真实模型做少量端到端验收。模拟测试不能代替真实多轮验证。

实现接口后，手工验证示例（现在执行会因接口未实现而失败）：

```sh
curl -sS -X POST http://127.0.0.1:8081/api/codex/conversations \
  -H 'Content-Type: application/json' \
  -d '{"title":"青禾创意","mode":"creative_description"}'

# 将 CONVERSATION_ID 替换为上一步实际返回值。
curl -N -X POST http://127.0.0.1:8081/api/codex/conversations/CONVERSATION_ID/turns/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"clientRequestId":"manual-test-001","message":"项目代号是青禾，请记住。"}'
```

第二轮沿用相同 conversationId，换新 clientRequestId，发送“项目代号是什么？”。验收记录只保留必要关联 ID、时间、事件顺序与结果，不记录认证内容。

快速生成业务验收再增加真实示例：创建“打磨创作描述”项目，发送“为雨夜城市写一段画面描述”，收到增量与成功终态后追问“保留场景，改成暖色调并缩短到 80 字以内”。再分别验证另外两个创作入口，以及停止、切项目后返回和刷新恢复。协议记忆测试与创作场景测试分别记录，不能相互替代。

实现完成后执行 Java 的 `./run.sh test` 和 `./run.sh package`；已有真实 MySQL 约束与健康检查测试，执行前先启动本地数据库；Codex 相关测试随功能补充，空测试运行不算验证。改动前端后运行受影响测试、`pnpm typecheck`、相应前端构建及浏览器验证。与本改动无关的既有失败单独记录。若涉及页面布局，再检查 1280、1440、1728、1920 宽度。

## 10. 推荐实施顺序和完成标准

| 次序 | 交付物 | 进入下一步的条件 |
| --- | --- | --- |
| 1 | CLI 版本、schema、Java 握手、真实单轮 | Java 可稳定连接，真实模型可响应 |
| 2 | 核心会话服务、HTTP/SSE、最小验证页 | curl 与浏览器真实流式；两轮追问、隔离、立即停止及异常收尾通过 |
| 3 | 快速生成 adapter、三种模板入口、复制与追问 | 在目标页面生成和修改创作内容；切项目不串消息、不误停任务 |
| 4 | MySQL 业务读写、请求去重落库、刷新／重启恢复、断流核对 | 第 9 节完整验收通过，达到首版交付条件 |
| 后续 | 参考图、图片／视频业务工具、资产入库 | 另定接口、授权、产物与费用边界后逐项实现 |

第 4 节至第 8 节是各部分的设计约束，实际施工按上表推进。先让真实页面出现可用结果，再补齐持久化与恢复；第 2、3 步只是中间演示版本，不得宣称首版完成。ID、状态、去重和存储接口从第 2 步确定，避免后续改变契约。

每阶段先保留可运行结果再扩展。不要先做大面积 UI 改版，也不要以“一次回答成功”替代整条链路验收。记录请求受理、首增量、终态和停止确认耗时，先建立实测基线，不在缺少数据时承诺响应时延。完成业务代码后按 AGENTS.md 更新 README 变更记录及项目版本；本次数据库能力扩展已升至 0.2.0；完整快速生成能力仍待实现。

接下来最小的一步是：重新确认本机 CLI 可执行；若仍异常则定位修复，锁定 schema，然后实现 Java 子进程握手和真实单轮文本输出。

## 11. 本次文档变更与核实范围

- 2026-10-28（0.2.0）：按用户要求落地 Docker MySQL、JDBC 连接池、Flyway 两表迁移与连接检查；存储方案由临时 JSON 改为 MySQL。历史记录中的“仅文档、未建表”仅代表当时状态，不代表当前数据库状态。

- 2026-10-28（软删除设计）：conversation 增加 deleted_at，保留 updated_at 原意；message 不加删除字段。同步拟定 DELETE 契约、访问过滤、重复删除与并发验收；未实际建表或实现删除。

- 2026-10-28（消息字段简化）：message 精简为 12 个字段；问答共享发送编号，唯一约束增加 role；停止仅用 state，item 仅在内存处理，不保存请求摘要及执行起止时间。同步配对、去重、停止竞争和重启恢复边界。

- 2026-10-28（字段收敛）：移除 conversation 的模板版本、背景版本和背景快照字段；首版采用固定模板，补充要求由用户消息提供。保留 snapshot_version 用于页面新旧快照判断，同步数据设计、查询返回值和验收范围。

- 2026-10-27（命名统一）：按用户最新要求使用 conversationId 作为唯一业务会话 ID，维持一个页面项目对应一个 threadId；每轮对应新的 turnId。数据按 conversation 和 message 两类设计，不另建项目或执行请求表；请求状态放在 AI 消息记录中，接口、事件、页面映射和示例同步调整。

- 2026-10-27：围绕快速生成文本闭环收敛首版，增加三种模式和职责边界；当时规划的模板版本与项目背景功能已在 2026-10-28 的字段收敛中移出首版。
- 调整停止接口为业务请求定位；补充早期停止、持久化去重、崩溃窗口与 unknown 状态。
- 断流改为解绑订阅、后台继续执行，通过快照查询恢复；完整恢复仍为首版交付前必验项。
- 实施顺序调整为环境、最小闭环、产品页面、完整持久化与恢复。前端参考信息和 CLI 137 均标注历史来源，待实施时复核。
- 参考资料沿用本轮方案评审查阅的官方 OpenAI 文档；当前无可调用的 Context7 工具。本次不新增安装版协议字段结论，仍以实施时生成的 schema 为准。
- 数据库接入已完成真实 MySQL 约束测试与健康接口测试，共 8 项通过；`./run.sh test`、`./run.sh package` 成功，8082 端口数据库健康接口返回 HTTP 200。CLI 复测、真实模型调用和浏览器联调未执行。
