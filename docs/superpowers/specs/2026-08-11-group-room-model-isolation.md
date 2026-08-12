# 群聊房间隔离与模型/供应商切换设计

## 目标

修复 XINCODE 群聊和模型配置链路中的跨作用域问题：

- 同名预制团队每次安装都创建独立房间、工作区、工作会话和记忆作用域。
- 产品团队的长期记忆按房间独立保存和召回，不进入普通 Agent 的全局记忆池。
- 普通会话和群聊成员的模型/供应商选择，在数据库、运行中的 Agent、请求客户端和界面之间保持一致。
- 群聊对用户只展示产品文本；内部引用协议、工具调试标记和装饰性控制符不能泄漏到成员互相对话中。
- 不改变 API Key 边界：真实密钥只允许来自本机 Keystore 或 CI Secret，不进入源码、测试夹具、日志、APK 或 Release。

## 已确认的根因

### 群聊隔离

1. `PresetTeam.install()` 按房间名查重，同名团队第二次点击直接返回旧房间。
2. 自动工作区只按安全房间名拼接，同名房间会共享目录。
3. 群聊完全访问模式创建的 `SessionEntity` 没有 `projectId`，所以工作会话落入全局记忆作用域；已有工作会话也没有在成员配置变化后同步。
4. `runGroupWorkTurn()` 只设置工作区根，没有设置会话记忆作用域。
5. 后台复盘从独立 IO scope 启动，丢失了触发它的会话 `WorkspaceThreadElement`，复盘写入可能回到全局。
6. `get_memory_by_title` 和 `save_memory(note, remove)` 仍使用未带项目条件的 DAO 查询。

### 模型与供应商切换

1. `OpenAiClient` 每次请求前读取配置，这部分是动态的；但主界面仍只读全局 active 配置，展示的供应商、模型列表可能与当前会话覆盖不一致。
2. 主界面快速切模型传入 `providerConfigId = null`，会把当前会话已经选择的供应商覆盖清掉。
3. `OpenAiClient` 原实现无法消费“只覆盖模型、供应商跟随 active”的会话状态：`providerConfigId = null` 时忽略 `currentModelId`。
4. 切换会话模型和刷新标签各自启动异步任务，后者可能先读到旧数据库行，导致标签回退；配置页切换 active 后也没有统一刷新运行时标签。
5. 群聊轻量模式每轮重新取成员配置，完全访问模式却只复用已创建的工作会话，成员后来选择的供应商/模型不会进入该会话。
6. 用量回调使用进程级当前模型/供应商名，后台会话可能被前台会话的标签覆盖。

## 设计

### 1. 房间作用域

新增 `GroupRoomIsolation` 纯逻辑辅助层：

- `memoryScopeId(roomId)`：生产房间 ID 映射到负数项目 ID，例如房间 8 使用 `-8`；不会与真实项目 ID 冲突。
- `defaultWorkspacePath(root, roomName, roomId)`：新房间工作区使用安全名称加房间 ID，避免同名碰撞。
- `cleanReplyText(text)`：移除内部 `<quote ...>` 标签和控制字符，保留正常 Markdown、emoji 与用户内容。

新建房间或安装预制团队后，用插入返回的房间 ID回写唯一工作区路径。已有空 `workspacePath` 的老房间继续使用原来的按名称回退路径，避免升级时移动用户文件。

### 2. 记忆隔离与迁移修复

- 新建或复用群聊工作会话时，把 `SessionEntity.projectId` 设置为该房间的负数作用域，并同步 `modelProviderConfigId/currentModelId/identityId`。
- `runGroupWorkTurn()` 在发送前再次绑定工作区根和房间记忆作用域，保证成员后台执行不受前台切换影响。
- 启动时枚举所有带 `workSessionId` 的成员：修复可追溯的工作会话 `projectId`，并把来源消息可追溯的旧全局自动记忆迁移到对应房间；没有来源消息的人工 note 不做猜测式搬迁。
- 所有群聊工具的记忆查询使用 `WorkspaceContext.projectId`；标题精确取回、近似搜索、note 删除都必须带作用域。
- 后台复盘在启动新的 job 时捕获当前 `WorkspaceContext.workspaceRoot/projectId`，用 `WorkspaceThreadElement` 恢复这两个值。

### 3. 有效模型选择

新增纯逻辑 `ModelSelection` 契约：

- 会话 `modelProviderConfigId > 0` 时使用指定配置，否则使用 active 配置。
- 会话 `currentModelId` 非空时使用会话模型，否则使用有效配置默认模型。
- 指定配置被删除时回退 active；旧的“只覆盖模型”数据仍保留模型覆盖。
- 快速切模型保留当前会话供应商覆盖；若会话跟随 active，则锁定当前 active 配置 ID，避免 active 在异步写入期间变化造成模型和供应商错配。

主界面从可观察的 provider 配置和当前 session 行推导供应商、模型列表、模型标签；不再只显示 active 配置。会话模型写入完成后再刷新标签。配置页和模型市场统一用数据库事务切换 active，并通过回调刷新应用运行时状态。用量记录按发生调用的 session 解析有效配置。

### 4. 群成员模型同步

成员保存的 `providerConfigId/model` 是唯一来源。完全访问模式每轮开始都把它们同步到成员工作会话；轻量模式继续在本轮直接使用成员配置。成员未指定配置时跟随当时 active 配置，未指定模型时使用该配置默认模型。

### 5. 可见文本

- 群聊历史引用使用普通的“引用消息（发送者：…）”文本，不注入 XML 协议标签。
- 轻量/完全访问系统提示明确禁止复制内部协议标记。
- 工具事件、错误和流式状态使用普通中文标签；内部 JSON 工具行仍保留在工作台过滤链路中，不进入普通成员气泡。

## 数据流

```text
Room ID
  ├─→ negative memory scope ─→ WorkspaceContext ─→ recall/save/review
  └─→ unique workspace path ─→ group work session ─→ tools/files

Session + ProviderConfig + active config
  └─→ ModelSelection ─→ UI labels/model list + OpenAiClient request config
```

## 验收标准

1. 纯单测证明同名房间路径、负数记忆作用域、可见文本清理和会话模型选择规则。
2. 代码检查证明所有群聊记忆读写入口都按当前作用域过滤，完全访问模式会同步成员模型/供应商。
3. `compileDebugKotlin`、`assembleDebug`、`lintDebug`、`testDebugUnitTest` 和 CI 全部通过。
4. Release tag 对应的签名 APK 不包含 API Key；仓库和构建日志不暴露真实凭据。
