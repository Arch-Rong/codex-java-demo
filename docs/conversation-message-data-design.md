# conversation 与 message 数据结构设计

日期：2026-10-28

状态：0.2.1 已接入 Docker MySQL 并由 Flyway 建表；对话业务读写、软删除接口与恢复逻辑尚未实现。  
适用范围：本机单用户、单实例的快速生成模块。

## 1. 关系与存储选择

一条 conversation 对应一个 Codex thread，包含多条 message；每次发送保存一条用户消息和一条 AI 回答占位记录，AI 回答同时记录这一轮的执行状态。无需单独建立项目表或 generation_request 表。

```text
conversation（conv-001，thread-aaa）
  ├─ message（msg-001，user：写一段雨夜城市描述）
  ├─ message（msg-002，assistant：回答，turn-001）
  ├─ message（msg-003，user：改成暖色调）
  └─ message（msg-004，assistant：回答，turn-002）
```

按用户最新要求，使用本地 Docker MySQL 8.4，已实现版本化迁移 [V1__create_conversation_and_message.sql](../src/main/resources/db/migration/V1__create_conversation_and_message.sql)。不再实施临时 JSON 文件存储。接口使用 camelCase，数据库使用 snake_case，例如 conversationId 对应 conversation_id。

表均为 InnoDB，使用 utf8mb4_0900_bin，ID 和状态比较区分大小写；MySQL 唯一索引允许多条 NULL，因此 thread_id 和 turn_id 可在绑定前为空。时间逻辑类型在本版映射为 DATETIME(6)，MySQL 服务和 JDBC 会话使用 UTC；created_at / updated_at 默认 CURRENT_TIMESTAMP(6)，后续业务更新须显式维护 updated_at 和 snapshot_version，没有自动 ON UPDATE。正文 content 不设默认值，AI 占位显式写入空字符串。

下文的时间均表示 UTC 时间点；HTTP JSON 用 ISO 8601 格式，页面按用户时区展示。字符串长度为业务上限，不代表已核实 Codex ID 的官方长度限制；上游 ID 视为不透明字符串，超限应显式报错，不能截断。服务端限制输入、items 和输出的总大小，TEXT 不表示允许无限写入。

首版使用固定的服务端模式模板，不实现模板版本管理、背景资料选择或背景快照。需要补充创作要求时，用户直接作为消息发送；多轮上下文通过复用并恢复真实 thread 维护。保留 snapshot_version 仅用于判断页面数据新旧，不是模板版本或 Codex 记忆。

## 2. conversation：保存整条对话的信息

| 字段 | 逻辑类型 | 可空 | 默认／生成规则 | 用途 |
| --- | --- | --- | --- | --- |
| `id` | VARCHAR(64) | 否 | Java 生成 UUID 字符串 | 主键；对外即 conversationId |
| `title` | VARCHAR(200) | 否 | 未命名对话 | 历史列表标题，不为自动取标题额外调用模型 |
| `thread_id` | VARCHAR(255) | 是 | null | Codex 返回的 threadId；初始化成功前为空 |
| `thread_state` | VARCHAR(20) | 否 | pending | thread 绑定状态，见下文；不表示本轮生成状态 |
| `model_id` | VARCHAR(255) | 否 | 服务端已验证模型 | 创建时固定的上游模型标识 |
| `mode` | VARCHAR(40) | 否 | creative_description | visual_inspiration、creative_description、video_idea 三选一 |
| `snapshot_version` | BIGINT | 否 | 0 | 对话内容或可见状态变化时单调递增，用于拒绝旧快照 |
| `created_at` | TIMESTAMP | 否 | 创建时间 | 对话创建时间 |
| `updated_at` | TIMESTAMP | 否 | 创建时间，后续更新 | 标题、消息或可见状态最后变更时间 |
| `deleted_at` | TIMESTAMP | 是 | null | 空表示正常，有时间表示已软删除；不使用 updated_at 是否为空判断删除 |

thread_state：

| 值 | 含义 |
| --- | --- |
| `pending` | 尚未开始绑定 thread |
| `starting` | 已登记初始化意图，正在创建 thread |
| `ready` | 已得到并保存 threadId；恢复后仍须核对真实可用性 |
| `failed` | 已确认初始化失败，且已确认没有未登记的成功结果 |
| `unknown` | 是否创建成功不明，需核对，不能直接再次调用 thread/start |

约束与索引：

- 主键 id；非空 thread_id 唯一，一个 thread 不能绑定给两个业务对话。暂不支持多个 Codex 存储实例，未来增加实例时需重新确定 thread ID 的唯一范围。
- ready 必须有 thread_id；绑定后不能静默替换。恢复失败保留原 ID 和记录。
- 历史列表先过滤 deleted_at 为空，再按 updated_at + id 排序；MySQL V1 已建立 deleted_at + updated_at + id 索引，后续结合查询计划优化，以 id 作为同时间记录的稳定排序键。
- snapshot_version 非负，更新消息和版本必须处于同一原子操作。它不是 Codex 协议字段，也不替代并发状态校验。
- 暂不保存最后一轮 turnId 或生成状态的第二份副本；从 AI 消息查询，避免两处状态不一致。

### 2.1 对话软删除规则

- 只给 conversation 添加 deleted_at；不添加 is_deleted、deleted_by，也不给 message 增加删除字段。首版不提供单条消息删除、回收站、恢复或物理清理功能。
- 删除前在同一会话锁／事务内检查：不得有 accepted、starting、running、stopping 或 unknown 的 AI 消息；thread 初始化处于 starting 或 unknown 时也不允许删除。生成中的对话先调用停止接口并确认真实终态，再由用户重新发起删除，不因停止受理就自动标记删除。
- 允许删除时，以同一个服务端 UTC 时间填写 deleted_at 与 updated_at，并原子递增 snapshot_version。写入失败不返回成功；不得清空 updated_at 或修改消息正文、消息时间及 threadId。
- 普通列表排除已删除对话；详情、消息／请求查询、发送（含同发送编号的重试）、修改和停止接口均先检查所属对话，已删除返回 404。内部对账仍可读取保留记录，但不能向普通用户查询暴露它。
- DELETE 重复调用对已删除记录返回 204，保留首次 deleted_at，不重复更新时间或版本；不存在的 ID 返回 404。删除是按 ID 操作，仍须遵守归属校验。
- 删除与发送／thread 初始化共用同一会话并发控制，必须在锁内再次检查删除标记；已删除记录的迟到回调不能重新激活会话、清空 deleted_at 或创建新 turn。前端解绑订阅并过滤旧视图回调，不以浏览器断流代替删除成功。
- MySQL 只更新对话记录，不级联删除消息。软删除不清除 Codex thread、认证文件或上游上下文；未来彻底清理需另行设计。

## 3. message：保存消息与 AI 回答的执行信息

| 字段 | 逻辑类型 | 可空 | 默认／生成规则 | 用途 |
| --- | --- | --- | --- | --- |
| `id` | VARCHAR(64) | 否 | Java 生成 UUID 字符串 | 消息主键，生成前即可得到 AI 消息 ID |
| `conversation_id` | VARCHAR(64) | 否 | 所属对话 ID | 关联 conversation.id，不另存项目 ID |
| `role` | VARCHAR(16) | 否 | user 或 assistant | 区分用户消息和 AI 回答 |
| `sequence` | BIGINT | 否 | 对话内从 1 递增 | 显示顺序；不依赖时间戳排序 |
| `content` | TEXT | 否 | 用户输入／AI 初始为空字符串 | 用户正文，或按运行时 item 顺序组合的 AI 展示正文 |
| `client_request_id` | VARCHAR(64) | 否 | 前端为一次发送生成 UUID | 同一问答的用户与 AI 消息保存同一个值；重试复用，新发送换新值 |
| `turn_id` | VARCHAR(255) | 是 | Codex 返回后补齐 | AI 本轮执行编号；尚未启动时为空，不能使用本地 ID 代替 |
| `state` | VARCHAR(20) | 是 | AI 为 accepted；用户为空 | AI 执行状态，见下文 |
| `error_code` | VARCHAR(64) | 是 | null | 稳定业务错误码，如 HARNESS_DISCONNECTED |
| `error_message` | VARCHAR(500) | 是 | null | 脱敏、可展示的错误说明，不放凭据、完整路径或原始协议载荷 |
| `created_at` | TIMESTAMP | 否 | 创建时间 | 消息创建时间 |
| `updated_at` | TIMESTAMP | 否 | 创建时间，后续更新 | 正文、执行状态或错误最后变更时间 |

约束与索引：

- 主键 id；conversation_id 外键关联 conversation.id，不自动级联删除历史。
- conversation_id + sequence 唯一，同时支持按对话读取和分页；sequence 在同一对话锁／事务中分配，不能无锁读取最大值后加一。
- conversation_id + client_request_id + role 唯一，约束每次发送每种角色最多一条；服务端通过原子写入保证 user 与 assistant 成对保存。两条记录通过相同发送编号配对，不依赖相邻位置猜测。重新生成使用新发送编号和新的消息对，不覆盖旧回答。
- client_request_id 两种角色均必填；AI 的 state 必填；用户的 state、turn_id 和错误字段为空。
- 非空 conversation_id + turn_id 唯一，防止同一轮被登记成多条 AI 回答；同轮多个正文 item 在内存分别整理，最终组合成这条 AI 消息的 content。
- 同一对话最多一个处于 accepted、starting、running、stopping 或 unknown 的 AI 记录。单实例先用每对话原子锁，并在锁内检查存储状态；普通唯一索引不能直接表达这条规则。服务恢复时先核对该记录，再允许新发送。
- 终态结算只生效一次。只有真实完成才能写 completed；浏览器 EOF 不改变执行成功状态。

## 4. AI 回答状态

| state | 含义 | 允许新一轮 |
| --- | --- | --- |
| `accepted` | 用户消息和 AI 占位记录已保存 | 否 |
| `starting` | 准备发送或已发送 turn/start，turnId 可能尚未返回 | 否 |
| `running` | 已确认真实执行，持续接收增量 | 否 |
| `stopping` | 已登记停止意图，等待本地取消或上游终态 | 否 |
| `completed` | 确认上游成功完成 | 是 |
| `failed` | 已确认本地启动失败或上游执行失败 | 是 |
| `interrupted` | 上游确认中断，或确认请求尚未发送即本地取消 | 是 |
| `unknown` | 上游受理或最终结果未知，待核对 | 否 |

这些都是业务状态。尤其本地取消可出现 turn_id 为空的 interrupted，不得伪造 Codex 的中断通知。unknown 后可能核对为 running 或真实终态；自然完成与停止竞争时以确认的真实结果结算。

### 4.1 停止意图只保存在 state

停止接口在会话锁内把对应 AI 消息改为 stopping 并落盘，不再保存独立停止标志。尚未发出上游请求时直接本地取消为 interrupted；已发出但 turnId 未返回时保持 stopping，响应或通知补齐 turnId 后立即发中断。迟到的“开始／增量”事件可以补齐 ID 和正文，不能把 stopping 覆盖成 running。真实完成先到时按真实终态结算，不强行标记已中断。

服务重启时保留 stopping 并先核对真实 turn；确认仍在执行则再次按该 turnId 请求停止，确认已结束则保存真实终态。停止确认超时或连接丢失时保留 stopping，并用 error_code / error_message 表示结果待核对，清理有界网络等待；不能改成 running 或 unknown 而丢掉停止意图，也不能允许新发送。普通未登记停止的未知任务才使用 unknown。停止中也不表示一定已停止。

### 4.2 item 仅存在于运行时内存

itemId 是 Codex 正文输出对象的标识，一轮可能有多个。运行时按 itemId 维护顺序、增量与最终全文，item 完成只校准对应对象，然后把组合后的完整正文写入 content。工具活动、原始推理和无必要日志不进入正文。数据库记录不保存 items 字段，也不把每个增量当成新消息。

浏览器刷新但 Java 未重启时，内存 item 仍在，可查询已组合的 content。Java 重启后只剩已落盘正文，不能从 content 推断 item 边界，更不能把上游增量直接追加上去。必须用安装版支持的真实 thread／turn 读取能力取得可完整重建的正文及归属，再整体替换对应 AI 消息。能力与字段实施时核实；未能可靠重建时保留部分正文，非停止任务标为 unknown，停止任务保留 stopping 并显示待核对，阻止新发送，不承诺接续旧流或伪造完成。

## 5. 一次发送怎样保存

1. 校验会话与输入，在会话锁内先检查 deleted_at；非空返回 404。然后查 conversation_id + client_request_id：找到同编号的 user 消息，直接逐字比较已保存正文与本次输入；相同则返回对应 assistant 消息和状态，不同返回 409；重复检查优先于活动任务冲突判断。
2. 校验 thread 已绑定且没有活动或 unknown 记录，分配相邻 sequence，原子保存带同一 client_request_id 的用户消息与空 AI 回答，AI 状态为 accepted，同时递增 snapshot_version。
3. 持久化 starting 后才发送 turn/start；保存失败不调用 Codex。通过响应或可关联事件取得真实 turnId 后写入 AI 记录，并核对真实状态，不能覆盖更早到达的终态。
4. 流式增量先更新内存中的 item 缓冲，再定期保存组合后的 content 和快照版本。停止仅把对应 AI 记录改为 stopping 并落盘；turnId 未返回时保留该状态，获得 ID 后处理。
5. 收到终态后原子保存最终正文、state、updated_at 及对话版本。中断保留部分正文。未登记停止的任务结果未知写 unknown；已登记停止则保留 stopping 并记录待核对错误，两者均不自动重新发送。

业务写入采用 MySQL 事务：锁定当前 conversation 行并检查 deleted_at、thread 与活动状态，在同一事务中保存消息对和递增版本。不得把 Codex 远程调用放在持有数据库锁的事务内。当前仅建好表，以上服务方法、事务和恢复流程尚未实现。

不保存请求摘要，直接比较同一发送编号关联的用户正文；保留空格和换行，不擅自 trim 后比较。发送时先按既定规则校验长度并保存最终实际发送的正文，重试应用相同规则。首版不保存执行开始／完成时间，created_at 是消息建立时间，updated_at 是最后变更时间，不能据此声称有精确生成耗时统计。

## 6. 示例数据

| conversation.id | title | thread_id | mode | deleted_at |
| --- | --- | --- | --- | --- |
| conv-001 | 雨夜城市 | thread-aaa | creative_description | null |

| message.id | conversation_id | role | sequence | content | client_request_id | turn_id | state |
| --- | --- | --- | --- | --- | --- | --- | --- |
| msg-001 | conv-001 | user | 1 | 写一段雨夜城市描述 | send-001 | null | null |
| msg-002 | conv-001 | assistant | 2 | 雨水映着街边的霓虹…… | send-001 | turn-001 | completed |
| msg-003 | conv-001 | user | 3 | 改成暖色调 | send-002 | null | null |
| msg-004 | conv-001 | assistant | 4 | 暖黄色灯光洒在街道上…… | send-002 | turn-002 | completed |

示例 ID 为便于阅读的占位值，真实业务主键和发送编号使用前文约定的 UUID。

## 7. 验证与未实现范围

2026-10-28（软删除设计）：conversation 增加 deleted_at，共 10 个字段；message 保持 12 个字段。删除只标记对话，保留消息和 thread 绑定；同步活动冲突、访问过滤、重复删除及并发规则。删除接口和存储逻辑尚未实现，未执行数据库或端到端验证。

2026-10-28（消息字段简化）：message 收敛为 12 个字段，保留 sequence 与 client_request_id；删除问题引用、请求摘要、停止标志、item 快照及执行起止时间字段。问答按同一发送编号配对，停止由 state 表达，item 仅在内存处理；同步去重约束、恢复边界和示例。

2026-10-28（文档变更）：移除模板版本、背景版本和背景快照三个字段，conversation 保留 9 个字段；当时 message 为 18 个字段，现按本节的消息字段简化记录调整。保留 snapshot_version 防止旧页面快照覆盖新内容，并同步简化请求摘要与首版范围。

0.2.0 已新增实际迁移、MySQL 连接配置、数据库健康检查及测试。DDL 实现唯一性、外键、枚举／角色状态和非负版本检查；同会话单活动任务、发送配对原子性、更新版本、软删除访问过滤仍需后续服务层事务实现，不能把建表当作这些业务能力已完成。

本次数据库验证范围包括迁移、真实约束、中文与 emoji 读写和软删除 SQL 的消息保留；尚未进行真实 harness、完整对话 CRUD、删除接口或前端联调。历史文档变更记录中的“未建表”描述仅代表当时状态。
