# Codex harness 多轮对话、流式输出与前端联调方案

日期：2026-10-27  
适用项目：`/Users/rwr/repo/codex-java-demo`  
前端参考项目：`/Users/rwr/repo/sinmo-web-lumx`  
状态：实施方案，尚未实现、尚未完成模型或浏览器联调。

## 1. 要完成什么

先在 Java demo 中跑通真实 Codex harness，再接截图中的项目对话体验验证：

1. 创建对话，发送第一条消息，实时看到回答增长。
2. 在同一对话追问，模型能使用前文。
3. 停止当前生成，保留已输出内容，随后仍可继续对话。
4. 新建对话不串上下文；两个对话的增量不会混在一起。
5. 刷新或服务重启后，能够区分历史恢复、生成中断和正常完成。

本次只制定文档，不修改 Java 业务代码、前端或模型配置。第一版以本机单用户、文本对话为范围，不引入数据库、登录、计费、图片生成、资产库或完整审批 UI。截图用于说明目标交互，图中的模型名称和其他按钮不构成本次的实现要求。

## 2. 仓库现状与边界

已检查 Java 项目：

| 当前内容 | 结论 |
| --- | --- |
| `pom.xml` | 项目版本 0.1.0，声明 JDK 25、Spring Boot 4.1.2，使用 Web MVC starter |
| `HealthController.java` | 只有 `GET /api/health`，返回服务与版本信息 |
| `application.properties` | 默认监听 `127.0.0.1:8081` |
| `run.sh` | 为当前进程选择 JDK 25，支持启动及 Maven 打包 |
| `docs/` | 可作为本方案和后续协议快照的存放位置 |

目前没有 Codex 子进程管理、RPC 客户端、会话管理、SSE 业务接口、持久化映射或测试。不能把“Java 服务启动成功”当成“harness 已跑通”。

已检查前端可参考的实现：

| 文件 | 可借鉴内容与注意事项 |
| --- | --- |
| `packages/conversation-main/src/api.ts` | 现有 `Agent.Chat` / `Agent.Resume` 请求与事件适配，使用 `runId`；不能直接当成 Codex thread 协议 |
| `packages/conversation-main/src/useConversationSession.ts` | 现有对话状态入口，后续接入时需继续核对发送、停止、清空、历史恢复的完整逻辑 |
| `packages/conversation-main/src/lib/sse.ts` | 已有 SSE 分帧、UTF-8 流式解码，可参考其测试；新接口不需要继承旧后端的非标准分帧兼容行为 |
| `src/api/agentChatApi.ts` | 另一套 `Chat.Simple` 流式入口，字段是 `conversationId`，与上面的 `runId` 不同 |

这些文件位于前端仓库。截图对应哪个实际路由和组件尚未确认，接前端前必须沿路由定位，不能仅凭截图把某个已有入口整体替换。

本机发现 `/usr/local/bin/codex`，但此次 `codex --version` 异常退出，退出码为 137。因此尚未核实本机 CLI 版本、可用模型、认证和协议兼容性。137 本身不能证明是内存不足或某种特定故障。

## 3. 采用什么接法

建议使用下面的链路：

```text
现有前端 / 最小联调页面
    │ HTTP 请求 + SSE 响应
    ▼
Java Spring Boot demo
    ├─ HTTP 参数校验、业务对话 ID、状态与历史视图
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

协议基线：每个连接先完成 `initialize` / `initialized`；新对话用 `thread/start`，续接用 `thread/resume`；每轮用 `turn/start`；文本来自 `item/agentMessage/delta`，最终 item 用 `item/completed` 校准；`turn/completed` 的状态区分成功、失败、中断。停止使用 `turn/interrupt`。具体字段以安装版本生成的 schema 为准。[官方 app-server 协议](https://developers.openai.com/codex/app-server/)

**下文 HTTP 路径、SSE 名称、类名、超时和文件存储方式均为本 demo 的拟定方案，不是 OpenAI 官方接口，也不代表仓库已实现。**

## 4. 阶段一：先验证 CLI 和 Java 握手

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

不要默认截图中的 `Sim-M2.5-fast` 是 Codex 可识别的模型 ID。第一版由服务端配置一个已验证的模型，UI 展示其真实名称；后续再做明确的产品模型到提供方模型的映射。

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

**本阶段验收：**Java 能启动并握手；关闭和重启不会遗留子进程；错误配置能给出明确诊断。到这里还不算多轮和流式完成。

## 5. 阶段二：跑通多轮对话和中断

### 5.1 三种 ID 要分清

| ID | 谁生成 | 用途 |
| --- | --- | --- |
| `conversationId` | Java | 产品对话；关联 projectId、标题和历史列表 |
| `threadId` | Codex | harness 上下文；保存到业务对话映射 |
| `turnId` | Codex | 一轮执行；用于停止、事件归属和状态判断 |

另外使用 `clientRequestId` 去重发送，使用 `itemId` 区分一轮中的多个输出对象。`projectId` 不是 threadId，同一项目允许有多个对话；旧接口的 `runId` 不能直接填进 threadId。

建议保存：对话 ID、项目 ID、threadId、模型 ID、标题、更新时间、消息快照、最后已知 turn 状态。第一版使用项目内 `data/` 的 JSON 文件即可，原子替换写入并加入忽略规则。不要把本地聊天内容提交到 Git。当前阶段先限定单实例写入。

### 5.2 多轮时序

1. 新建业务对话，调用 thread/start 并保存映射。
2. 第一轮发送“项目代号是青禾，先记住它”。
3. 等待这一轮明确终止。
4. 第二轮只发“项目代号是什么？”，对同一个 threadId 调用 turn/start。
5. 核对真实回答包含“青禾”。不能把前文手动拼进第二轮来替代会话验证。
6. 新建另一个对话，问相同问题，确认请求和历史没有携带前一会话内容。

每个 thread 同时只允许一个活动 turn，Java 用原子状态转换控制，不能只靠禁用按钮。并发发送返回 409；第一版不做输入排队或生成中追加指令。

注册本轮事件接收器、占用会话状态后再发送 turn/start，防止最早的事件丢失。通知可能早于请求响应被应用层处理；路由应结合线程、活动请求及已获得的 turnId，不能只在 RPC 返回后才开始收事件。

### 5.3 中断与失败

- 停止按钮调用 Java 停止接口，由 Java 发送 turn/interrupt。
- interrupt 请求已受理只表示正在停止；收到最终中断状态后才显示“已停止”。若自然完成先到，保留正常完成状态。
- 用户停止后保留已生成文本，并允许继续下一轮。
- 前端只 abort fetch 不足以证明 harness 停止；显式停止接口必须存在。
- 同一 turn 的结束处理只能生效一次，避免中断与自然完成竞争造成重复消息。
- 进程异常退出，将活动任务标为连接丢失/结果未知，不能伪造 completed。
- 启动后对已保存 thread 恢复并核对状态；无法恢复时提示新建对话，不能静默丢弃上下文。

对于首版不支持的工具审批或用户输入请求，必须用安装版 schema 定义的方式拒绝或结束当前任务，并给出可理解的提示，不能悬空等待。首轮采用专用空工作目录与受限策略；不要为了“跑通”开放不受限命令执行。这个范围不保证工具能力和完整审批链路已经接通。

**本阶段验收：**真实模型完成两轮记忆验证；新会话隔离；生成停止后可续聊；服务重启后使用保存的 thread 恢复追问。

## 6. 阶段三：通过 SSE 暴露给前端

### 6.1 拟新增 HTTP 契约

| 方法与路径 | 输入 | 输出 |
| --- | --- | --- |
| `POST /api/codex/conversations` | `{ "projectId": "demo-project" }` | `{ "conversationId": "conv-...", "title": "新对话" }` |
| `GET /api/codex/conversations?projectId=...` | 项目 ID | 该项目的业务对话列表，不暴露本机其他 Codex 记录 |
| `GET /api/codex/conversations/{id}` | 业务对话 ID | 消息、状态、活动 turnId、更新时间 |
| `POST /api/codex/conversations/{id}/turns/stream` | `{ "clientRequestId": "uuid", "message": "你好" }` | SSE |
| `POST /api/codex/conversations/{id}/turns/{turnId}/interrupt` | 路径参数 | 202 表示受理，最终状态从流或查询获取 |

“新建对话”与“新建项目”分别处理。前者生成新的 conversationId/threadId，不改项目业务。

HTTP 建流前校验：空文本或超长输入 400、对话不存在 404、活动轮次冲突 409、harness 未就绪 503。未发送 SSE 头前返回结构化错误；建流后使用错误事件，不再尝试改 HTTP 状态。

相同 clientRequestId 不得再次创建 turn：活动任务返回可查询的冲突信息，结束任务返回已有结果定位。第一版不重放 SSE，前端遇到未知结果应查询历史，不自动重发生成请求；服务重启后的不确定请求也先对账。

### 6.2 拟定 SSE 事件

```text
event: turn.started
data: {"conversationId":"conv-1","turnId":"turn-1"}

event: message.delta
data: {"conversationId":"conv-1","turnId":"turn-1","itemId":"item-1","delta":"你好"}

event: message.completed
data: {"conversationId":"conv-1","turnId":"turn-1","itemId":"item-1","text":"你好，很高兴和你一起创作。"}

event: turn.finished
data: {"conversationId":"conv-1","turnId":"turn-1","status":"completed"}

```

规则：

- 每个事件以空行结束，data 使用 JSON 编码，正文中的换行与空格原样保存。
- message.delta 追加到对应 item，message.completed 按 itemId 校准全文，不再次追加。
- turn.finished 的 status 可为 completed、failed、interrupted；只有 completed 显示成功。
- 错误详情用 `event: stream.error`，包含稳定 code、用户可读 message、retryable 和已有的关联 ID；不要用 message 字段自动拼聊天正文。
- 若已知轮次失败，错误事件之后仍发送 failed 终态；若进程丢失导致真实结果未知，用 stream.error 结束并查询恢复，不能冒充 Codex 的 failed 通知。
- 只有工具进度、没有正文时显示“正在处理”，不把工具输出和推理内部数据混成回答。
- 设置 `Content-Type: text/event-stream` 与禁用缓存；定时发送注释心跳，代理层关闭缓冲并放宽读超时。
- 建议握手/RPC 受理超时 15 秒、轮次上限 10 分钟、心跳 15 秒；均可配置，轮次上限触发中断流程。首次输出慢不应直接判为失败。

Java 保留现有 Web MVC 结构，可用该版本支持的 SSE emitter 实现。stdout 读取循环只分发事件，浏览器写入由独立发送队列承担；每会话队列要有上限。慢客户端断开后不能卡住其他会话或无限占内存。

### 6.3 断流不是成功

首版约定：浏览器连接丢失时，Java 尽力中断对应活动 turn；如果中断未确认，则保留待核对状态。历史查询可找回已保存的完整或部分结果。SSE emitter 的正常关闭回调须区分“已发终态”和“提前断流”，避免正常完成时误发中断。

不承诺断点续传，也不实现 Last-Event-ID 重放。前端没有收到终态就遇到 EOF 时，显示“连接已中断，正在核对结果”，查询对话状态；不能直接调用成功回调，也不能自动再次提交用户消息。

**本阶段验收：**`curl -N` 能在回答结束前收到增量；中文、空格和换行正确；断流、错误、中断均能正确收尾。

## 7. 阶段四：再连接前端

### 7.1 先做最小验证页

建议先增加 Java 静态联调页 `src/main/resources/static/codex-debug.html`：只有新对话、消息区、输入框、发送、停止和连接状态。与 Java 同源，先排除跨域、现有鉴权、复杂项目状态的干扰。它是联调工具，不是替代产品页面。

浏览器用 POST fetch 读取 SSE 响应，不使用只支持常规 GET 建连方式的原生 EventSource 来发送本方案的消息请求。处理 HTTP 非成功、缺少响应流、跨块 UTF-8、跨块事件、正常终态和提前 EOF。

### 7.2 再接现有项目对话页面

1. 沿截图页面实际路由定位组件与会话 hook。
2. 新增独立 Codex adapter，转换为现有消息视图需要的状态；不要直接复用旧 Agent 的 runId 或完成事件语义。
3. 用显式本地配置开关启用；使用独立 `/api/codex` 前缀，不替换全局 `/agent` 代理。
4. 本地开发优先代理到 `127.0.0.1:8081`；端口冲突可使用 `PORT=8082 ./run.sh` 并同步代理目标。
5. 业务对话按 projectId 保存；切项目、选历史、新建对话时正确绑定 conversationId。
6. 模型选择器只展示已验证选项；未接通的参考图、免费问答、提示词优化等能力保持原接口或清晰禁用，不能假装转给 Codex。

截图中的交互对应关系：

| 用户动作 | 接入行为 |
| --- | --- |
| 输入框发送 / 推荐问题 | 发送当前对话的新 turn；未有对话则先创建 |
| 回答逐渐出现 | 按 conversationId + turnId + itemId 更新消息 |
| 新建对话 | 新建业务对话，清空当前展示，保持项目关联 |
| 历史对话 | 从 Java 的业务列表选择，恢复消息和当前状态 |
| 停止按钮 | 发 interrupt，展示“停止中”，最终确认后恢复发送 |
| 切换项目 | 切换会话集合，旧流回调不得覆盖新项目视图 |

快速切换时使用订阅解绑或视图代次标识过滤迟到回调；会话 ID 不能只存在组件临时 state。刷新后先查询恢复，再允许新一轮发送。生成中的长回答支持滚动阅读，用户向上阅读时不强制滚回底部。

**本阶段验收：**在截图对应的真实页面完成两轮追问、新建、历史恢复、停止及错误提示；Network 可看到新接口而非旧 Agent 请求。

## 8. 建议文件拆分

以 `src/main/java/com/example/codexdemo/` 为根，按实际实现规模调整：

```text
codex/CodexProcessManager.java       启停、握手、stdout/stderr
codex/CodexRpcClient.java            请求 ID、future、响应/通知/服务端请求分流
codex/CodexEventDispatcher.java      会话事件路由
conversation/ConversationService.java  映射、轮次串行、中断、恢复
conversation/ConversationStore.java    本地快照存取
conversation/ConversationController.java  HTTP 和 SSE
conversation/dto/                   参数与前端事件对象
```

不要一开始引入复杂通用框架。先把进程、协议、业务会话和 HTTP 输出四个责任分开即可。配置建议包含可执行文件绝对路径、专用工作目录、模型、超时、存储目录和队列上限；浏览器不能指定任意可执行路径或工作目录。

## 9. 验收清单与执行证据

以下全部是待执行项；此次写文档没有执行真实推理或浏览器联调。

| 场景 | 通过条件 |
| --- | --- |
| 首轮流式 | 结束前已出现多个增量，记录发送、首增量和终态时间 |
| 同线程追问 | 第二轮不重传全文，仍正确使用“青禾”上下文 |
| 新对话隔离 | 新 thread，未带入前会话消息，事件与历史不串线 |
| 停止后续聊 | 收到 interrupted，保留部分文字，下一轮可发 |
| 刷新 / Java 重启 | 找回映射与历史，恢复 thread 后继续追问 |
| 连点发送 | 一个 clientRequestId 至多创建一轮，活动轮冲突明确 |
| 中文与碎片 | UTF-8 被拆分、事件跨 chunk、空格与换行均不损坏 |
| 最终校准 | item 全文不重复追加，一轮多个 item 分别更新 |
| 鉴权 / 模型不可用 | 错误可读，不无限 loading、不伪装完成 |
| 浏览器断网 / 进程退出 | 部分文本保留，状态可核对，不自动重复推理 |
| 两会话交错 | 各自消息、状态、停止操作互不污染 |
| 前端实际页面 | 新建、历史、停止、切项目均调用预期 adapter |

推荐测试分层：模拟 app-server 的乱序响应、通知先到、退出和服务端请求，验证 RPC 分流；对会话状态竞争与 SSE 分帧做自动测试；再用真实模型做少量端到端验收。模拟测试不能代替真实多轮验证。

实现接口后，手工验证示例（现在执行会因接口未实现而失败）：

```sh
curl -sS -X POST http://127.0.0.1:8081/api/codex/conversations \
  -H 'Content-Type: application/json' \
  -d '{"projectId":"demo-project"}'

# 将 CONVERSATION_ID 替换为上一步实际返回值。
curl -N -X POST http://127.0.0.1:8081/api/codex/conversations/CONVERSATION_ID/turns/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"clientRequestId":"manual-test-001","message":"项目代号是青禾，请记住。"}'
```

第二轮沿用相同 conversationId，换新 clientRequestId，发送“项目代号是什么？”。验收记录只保留必要关联 ID、时间、事件顺序与结果，不记录认证内容。

实现完成后执行 Java 的 `./run.sh test` 和 `./run.sh package`；前者需先补上有意义的测试依赖与测试，空测试运行不算验证。改动前端后运行受影响测试、`pnpm typecheck`、相应前端构建及浏览器验证。与本改动无关的既有失败单独记录。若涉及页面布局，再检查 1280、1440、1728、1920 宽度。

## 10. 推荐实施顺序和完成标准

| 次序 | 交付物 | 进入下一步的条件 |
| --- | --- | --- |
| 1 | CLI 版本、schema、握手日志 | Java 可稳定连接，真实模型可响应 |
| 2 | 会话服务、存储、中断 | 多轮、隔离、停止、重启恢复通过 |
| 3 | HTTP/SSE 接口、最小验证页 | curl 与浏览器均真实流式，异常能收尾 |
| 4 | 前端 adapter 与页面联调 | 目标页面完整走通验收清单 |

每阶段先保留可运行结果再扩展。不要先做大面积 UI 改版，也不要以“一次回答成功”替代整条链路验收。完成业务代码后更新 README 变更记录及项目版本；本次仅添加方案和文档入口，运行版本继续为 0.1.0。

接下来最小的一步是：修复并确认本机 CLI 可执行，锁定 schema，然后实现 Java 子进程握手和真实单轮文本输出。
