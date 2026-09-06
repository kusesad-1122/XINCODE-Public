# XINCODE × Codex Harness 完整优化方案

> 依据：① 11分钟拆解视频全文转写（博主 FRANK，讲的是 **Codex Harness** 开源，
> 不是 Cortex——视频开场即“Codex 把背后的 Harness 全面开源，同时驱动 App/CLI/IDE 插件”）；
> ② 本地 `openai-opensource/codex` 源码实测（177 crate Rust workspace）；
> ③ XINCODE v1.13 现状（`core` 调度循环 / `security` 权限门控 / `data` Room记忆 / `service` 前台服务）。
> 视频转写原文：`../openai-opensource/cortex-video/transcript.txt`，关键帧同目录 `frame_*.jpg`。

## 一、视频核心链路（10 环，方案的骨架）

1. 最小 Agent Loop 简陋得“冒犯智商”：上下文→模型→要调工具就执行→结果返回→无新调用则结束。
只抄这几步得到的是“会用工具的金鱼”：转头就忘，还会顺手改数据库。
2. 真实产品 three 难：任务跑十几分钟、要汇报进度、接受打断、请求审批——普通一问一答接口接不住。
3. App Server 双向层：产品可启动/中断/发新输入/回审批；Codex 推模型输出/工具进度/文件改动/审批请求。
4. Thread/Turn 任务身份：Thread=长期任务档案，Turn=其中一轮（如修登录Bug：T1查根因→T2按证据改码，继承前文）。
先知道“在继续哪件事”，后续才有归属。
5. Codex Core + RunTurn：收历史+系统指令+输入+工具定义+输出要求，只问模型“下一步做什么”；
模型回 ToolCall→Turn 进等待态，模型暂停、工具系统接管。
6. ToolRouter=工具调度器：只回答“交给谁”（读文件→文件工具，跑命令→终端工具，内部系统→业务工具）。
7. 停止条件 NeedsFollowUp：有 ToolCall 就必须让模型看到结果再调模型；无新动作才停。
不数固定轮数，不让模型假装工具成功。
8. ToolOrchestrator=执行管理器：审批→选沙箱→运行→拒后按 policy 定。Router 管分发，Orchestrator 管边界，
三态：允许/拒绝/需人工（approval request 经 App Server 转界面，Turn 真停等答复；同意继续、拒绝则重判）。
审批是执行链的一部分，不是心理按摩。
9. Event/Rollout/ThreadStore：事件按序写 Rollout（运行流水）；ThreadStore 长存；恢复=读取→解析→重建 Thread，
不是读内存快照。Fork=从某点复制分支换方案续跑。压缩只压模型上下文，原始事件永留 Rollout。
10. 物流案例验证：Shipment #SH-2847 延误→查运力/比路线/找承运商→重订舱触发人工审批→结果写回 Thread→看板按完成事件刷新。
结论：Harness 的重点不在 Loop（Loop 只是接力），而在任务状态+工具调度+全链审批+恢复机制；
开发者保留界面/数据/工具/审批规则，复用运行循环+状态系统。

## 二、XINCODE 现状对照（差距即工作量）

| Harness 环节 | XINCODE 已有 | 缺口 |
|---|---|---|
| Agent Loop（core 调度循环） | 有：工具调用循环+结构化输出+Prompt缓存 | 停止条件多为轮数/无调用即停，缺 NeedsFollowUp 语义（结果必须回模型） |
| 双向通信层 | service 前台服务+通知+指挥室动画 | 缺统一 App Server 语义：中断/打断/审批回执/事件推送各自为政 |
| Thread/Turn 身份 | 项目内对话隔离、Goal/Work 多任务 | 缺 Turn 链：同任务多轮执行无继承关系，恢复靠整段历史重灌 |
| ToolRouter | tools 工具注册表+`/技能名`+`@MCP` | 路由与执行耦合：审批/沙箱逻辑散在各工具 |
| ToolOrchestrator | security 权限模式+敏感操作确认卡+审计 | 确认卡多为“前端弹框”，链路未真停；无三态 policy；root/Ubuntu 终端缺沙箱分级 |
| Rollout 事件溯源 | 审计日志+记忆沉淀 | 审计≠Rollout：无“按序事件→重建运行态”，断线/杀进程后只能重跑 |
| Fork | 协作模式子智能体并行 | 子智能体是“另起一摊”，不是从某 Turn 复制分支 |
| 压缩 | 压缩阈值+上下文圆环+成本显示 | 压缩即丢弃：无“模型上下文可压、原始事件永存”的双层设计 |

## 三、完整优化方案（8 项改造，按依赖排序）

### A. 建 App Server 双向层（P0，先行）
- 在 `service` 内立 `AgentServer` 单例， four 上行接口：`startTask / interrupt / sendInput / resolveApproval`；
four 下行事件流：`modelOutput / toolProgress / fileDiff / approvalRequest`（Kotlin Flow/SharedFlow）。
- 指挥室 WebView、通知栏、定时任务全部只订阅事件流，不再直调 core。
- 验收：任务运行中按 Home/杀 UI 进程，重进后进度不丢；通知栏可中断任务并即时生效。

### B. Thread/Turn 身份（P0，与 A 并行）
- Room 新增 `threads(id, goal, createdAt)` + `turns(id, threadId, parentTurnId, status, summary)`；
Turn 状态机：`running → waiting_tool / waiting_approval → done`。
- 同 Thread 新 Turn 只带“前 Turn 摘要+关键证据”，不再重灌全文（呼应视频 T1 调查→T2 改码）。
- 验收：修 Bug 类任务第二轮能引用第一轮根因结论，且 token 消耗下降。

### C. 收敛 RunTurn 主函数（P0）
- `core` 内收敛唯一入口 `runTurn(turn)`：组装（历史摘要+系统提示+用户输入+工具定义+输出schema）→
只问模型“下一步做什么”→若回 ToolCall 则 Turn 置 `waiting_tool` 并挂起（suspend），绝不由模型直调工具。
- 验收：grep 全仓，工具执行调用点只剩 Orchestrator 一处。

### D. 拆分 Router 与 Orchestrator（P1，核心）
- `ToolRouter`：纯函数式 `route(toolCall)->ToolExecutor`，只答“交给谁”，无副作用、无权限判断。
- `ToolOrchestrator`：唯一执行边界，固定顺序：查 policy→需审批则发 `approvalRequest` 并 suspend→
选执行域（普通/Ubuntu chroot/root 分级即“沙箱”）→运行→被拒按 policy 降级或返回。
- root/Ubuntu 终端、MCP 工具、企业业务工具全部经此链；每新增工具零权限代码。
- 验收：新增一个工具时，security 相关代码行数增加为 0。

### E. 三态审批+真暂停（P1）
- policy 三态：`allow / deny / needApproval`（按工具×参数×项目三维查表，抄 codex `permission_profile` 思路）。
- `needApproval` 时 Orchestrator 挂起协程，经 AgentServer 推确认卡；用户同意→继续，拒绝→结果写回 Turn，
模型必须基于拒绝重判（UI 上禁用“假同意”路径：后端无回执=不执行）。
- 验收：飞行模式下点“同意”无效（无回执不执行）；拒绝后模型能改方案而非重试同一调用。

### F. NeedsFollowUp 停止条件（P1，小改大效）
- 循环条件改为：`while(本轮产生ToolCall || 审批待定)`；工具结果无论成败必须再调一次模型；
另设 `maxTurns` 纯兜底（防烧钱）+ 每轮成本累计显示（已有人民币显示直接复用）。
- 验收：构造“工具返回失败”用例，模型必须看到失败输出；杜绝“模型假装已执行”。

### G. Rollout 事件溯源+Fork+双层压缩（P2，最深）
- Room 新增 `events(seq, turnId, type, payload, ts)`（type：userInput/modelOutput/toolCall/toolResult/
approvalRequest/approvalResult/done）；只追加不改写。
- 恢复= `读取 Rollout→解析→重建 Turn 状态`（进行到哪一步、等什么、已拿到什么结果），覆盖：进程被杀、
手机重启、WorkManager 定时任务续跑。
- Fork：从任一已完成 Turn 复制 `turns+events` 前缀为新 Thread（协作模式“换方案重试”即 Fork，不再另起）。
- 压缩双层：模型上下文按阈值压缩（已有能力保留），`events` 原始记录永久保留且可导出（学 codex findings.json）。
- 验收：`adb shell am force-stop` 后任务可从断点恢复；同一任务 Fork 两方案各跑一遍。

### H. 一个业务 Demo 验证全链（P2，学物流案例）
- 做“异常恢复工作台”式 Demo（如：定时任务失败→Agent 查日志/比方案/修配置→高危改动弹审批→结果写回→
指挥室按完成事件刷新），Demo 每一步必须能在 A–G 的链路里指到位置。
- 验收：Demo 走一遍即回归一遍 Harness。

## 四、安全与 CI（直接复用上轮三件套结论）

- 触发鉴权：定时任务/外部分享入口抄 `codex-action` 的 write-access 思想：默认仅机主，`allow-users` 白名单。
- 输入消毒：联网搜索抓回的正文、MCP 远端工具描述视为不可信输入，进提示词前做来源标注+指令与数据分段，
提示词注入（让模型关审批/提权）进审计并 deny。
- 密钥：API Key 只存 Keystore/EncryptedSharedPreferences，日志/记忆抽取/崩溃上报三处脱敏（抄 security 的
“仓内容一律当数据”）。
- CI：`codex-action`（`:read-only` 先跑通）做 PR review bot；`codex-security scan --dry-run` 再 standard，
高危 findings 进 `security` 模块的 deny 表。

## 五、路线图

- P0（1–2 周）：A App Server 事件流 + B Thread/Turn 表 + C runTurn 收敛。产出：长任务可中断可续。
- P1（2–3 周）：D Router/Orchestrator 拆分 + E 三态真审批 + F 停止条件。产出：新工具零权限代码、审批可信。
- P2（3–4 周）：G 事件溯源/Fork/双层压缩 + H 业务 Demo。产出：杀进程可恢复、Fork 换方案。
- 每阶段门禁：对应验收项全过 + `./gradlew :app:assembleDebug` + 核心用例（失败工具、拒绝审批、杀进程、Fork）。

## 六、一句话总结

Loop 只是接力，Harness 才是系统：XINCODE 已有“跑起来”的全部零件，方案做的只是把视频里的四堵墙
（任务身份、调度与边界分离、真审批、事件溯源恢复）砌起来，砌完即可一边保留自己的界面/工具/审批规则，
一边得到和 Codex 同级的运行可靠性。

## 七、H 落地说明（2026-09-06 实施记录）

业务看板 Demo 因无编译器在手改为等价可执行回归，二者覆盖同一链路：

- `data/.../HarnessDrillTest`（`./gradlew :data:testDebugUnitTest`）：
开 Thread → T1 查根因 → 事件落盘 → T2 继承 → 重建（完成动作/未决审批/卡点）→
未裁决审批可见 → Fork 换方案（前缀复制、未来事件不串）→ 导出 Rollout → 悬挂回收。
- `core/.../ToolRouterTest`（`./gradlew :core:testDebugUnitTest`）：直命中/纠偏/未知三裁决。
- `SchemaConsistencyTest`（零改动自动覆盖）：harness 三表 + 五索引的迁移↔实体一致性，
升级用户启动即崩类问题编译期前置发现。
- 真机四用例（失败工具、拒绝审批、杀进程、Fork）留终检在有 SDK 的机器上跑。
