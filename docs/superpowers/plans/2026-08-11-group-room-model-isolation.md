# XINCODE 群聊隔离与模型供应商切换实施计划

> 按 `superpowers:executing-plans` 与 TDD 规范执行：每一组先写回归测试并确认失败，再做最小实现；每个阶段完成后运行对应验证。

## 全局约束

- 记忆策略固定为 B：产品团队记忆按群聊房间独立保存和召回。
- 不把任何真实 API Key 写入源码、测试、日志、APK、Git 历史或 Release。
- 不破坏旧房间的显式工作区路径；新房间使用带 ID 的唯一路径。
- 普通会话、群聊成员和功能模型配置必须保持各自的配置作用域，不能依靠进程级可变 client 状态。

## Task 1：添加失败的纯逻辑回归测试

**文件：**

- 新建 `app/src/test/java/com/xincode/app/GroupRoomIsolationTest.kt`
- 新建 `app/src/test/java/com/xincode/app/ModelSelectionTest.kt`

**覆盖：**

- 正数房间 ID映射到负数记忆作用域，非正输入不会回到全局 0。
- 同名不同 ID 得到不同工作区路径；路径保留根目录并清理危险名称。
- `<quote sender="...">...</quote>` 和控制符不会出现在最终成员回复，普通 Markdown/emoji 保留。
- 选择指定供应商/模型、跟随 active、删除指定配置后回退、快速换模型保留当前供应商的契约。

**验证：**先运行对应测试，确认因为生产辅助层不存在而失败；失败原因必须是缺少实现而不是 Gradle 配置问题。

## Task 2：实现房间、记忆和后台复盘隔离

**文件：**

- 新建 `app/src/main/java/com/xincode/app/GroupRoomIsolation.kt`
- 修改 `data/.../MemoryDao.kt`、`SessionDao.kt`、`GroupRoomEntity.kt`
- 修改 `XincodeApplication.kt`、`BackgroundReviewRunner.kt`
- 修改 `SaveMemoryTool.kt`、`GetMemoryByTitleTool.kt`
- 修改 `PresetTeam.kt`、`GroupRoomScreen.kt`、`GroupRoomEngine.kt`

**实现顺序：**

1. 加入纯逻辑辅助层和 DAO 作用域查询/修复语句。
2. 启动时修复已有群聊工作会话的负数 `projectId` 与可追溯记忆。
3. 移除预制团队按名称返回旧房间的逻辑；新建预制/自定义房间后写入唯一工作区路径。
4. 完全访问模式每轮同步成员工作会话的作用域和模型配置；`runGroupWorkTurn` 设置 `sessionProjectId`。
5. 后台复盘和技能改进恢复捕获的 `WorkspaceThreadElement`。
6. 所有群聊记忆工具按当前作用域读写，并把历史引用与工具状态改成普通文本。

**验证：**运行 `:app:testDebugUnitTest`，再执行 `git diff --check` 和针对 `getByTitle/search/getAllByProject/projectId/workspacePath` 的静态扫描。

## Task 3：实现统一有效模型选择和运行时刷新

**文件：**

- 新建或修改 `ModelSelection.kt`
- 修改 `OpenAiClient.kt`、`XincodeApplication.kt`、`MainActivity.kt`
- 修改 `SupplierConfigScreen.kt`、`ModelMarketScreen.kt`
- 修改 `SessionDao.kt` 如需原子字段更新

**实现顺序：**

1. 让 `OpenAiClient` 支持旧的“只覆盖模型”状态，并继续在每次请求前重新解析数据库配置。
2. 用 `ModelSelection` 推导主界面有效供应商、模型列表和模型标签；快速切模型保留供应商覆盖。
3. 让会话模型写入与标签刷新串行，避免异步读旧值；会话切换也使用同一解析函数。
4. 配置页/模型市场的 active 切换、创建、编辑和删除使用事务，并触发统一的 runtime 刷新。
5. 用量回调按调用所属 session 解析模型/供应商，避免后台会话使用前台标签。
6. 完全访问群聊每轮同步成员选择；轻量群聊继续使用每轮新取的成员配置。

**验证：**运行 `:app:testDebugUnitTest :provider:testDebugUnitTest`，静态检查所有模型请求仍经过动态 `resolveConfig`，不新增带 key 的日志。

## Task 4：全量验证与版本发布

1. 检查 `git status`、`git diff --check`，执行无凭据扫描。
2. 更新 `app/build.gradle.kts` 到下一个版本号，并同步变更日志（如果仓库已有对应文件）。
3. 运行本地可运行的 Gradle 验证；若本地依赖受限，以 GitHub Actions 的完整结果为最终证据，不把未运行的命令宣称为通过。
4. 提交并推送分支，创建 PR，等待编译、APK、lint 和单测全部通过。
5. 合并到 `main`，创建并推送版本 tag，等待 tag release job 生成签名 APK。
6. 核对正式 Release、合并提交、CI 运行结果和 APK 资产；任何失败继续修复，不提前宣称完成。
