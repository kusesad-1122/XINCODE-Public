# XINCODE Responses API 适配设计

## 目标

让 XINCODE 在供应商配置选择 Responses API 时，主 Agent 以及所有依赖同一供应商配置的内部调用都使用 OpenAI Responses 协议；保留现有 Chat Completions、Anthropic 和自定义端点行为。适配必须覆盖普通文本、流式文本、函数工具调用、结构化输出、推理摘要、上下文历史转换和 usage 统计。

API Key 只从用户本机的 Keystore 或 CI 的 Secret 读取。仓库、APK、测试夹具、日志、提交记录和 Release 资产都不得包含任何真实 Key。

## 现状与问题

- `OpenAiClient.agentStream()` 已有 Responses 分支，但 `chat()` 和 `chatStream()` 仍按 Chat Completions 响应结构解析。
- `AuxModels` 直接拼接 `/v1/chat/completions`，功能模型配置指向 Responses 时会失败。
- `JudgeService` 直接读取 `choices[0].message.content`，Goal 裁判无法使用 Responses。
- Responses 流解析缺少 `response.function_call_arguments.done` 的兜底，且把 `response.incomplete` 当作完整成功结束，可能把被截断的结果交给 AgentCore。
- 供应商配置界面没有 Responses 选项，用户只能手改数据才能启用该协议。

## 方案

采用“共享协议转换器 + 现有客户端保留供应商分支”的方案。

1. 在 `provider` 模块增加纯协议层，负责：
   - base URL 与 `responses` 端点拼接；
   - OpenAI 形态 messages 到 Responses `instructions`、`input` 的转换；
   - Chat Completions 工具 schema 到 Responses 扁平 function schema 的转换；
   - `response_format` 到 `text.format` 的转换；
   - 非流式 Responses 文本/函数调用/usage 提取；
   - Responses SSE 事件聚合。
2. `OpenAiClient` 使用共享协议层实现 `chat()`、`chatStream()` 和 `agentStream()` 的 Responses 路径，统一处理请求头、错误、取消和截断状态。
3. `AuxModels` 与 `JudgeService` 增加协议类型字段，并复用同一套 Responses 请求/响应规则；旧配置默认仍为 Chat Completions。
4. 配置 UI 增加“OpenAI Responses（/v1/responses）”选择和一个 OpenAI Responses 预设；数据库沿用已有 `apiPathType` 字段，不新增迁移。
5. 使用协议层纯单元测试覆盖请求转换和标准 SSE 事件序列。测试数据只使用假 Key 字符串或不带认证的本地伪服务器，禁止真实 API 请求。

## 数据流

```text
ProviderConfigEntity.apiPathType
        ↓
OpenAiClient / AuxModels / JudgeService
        ↓
ResponsesProtocol
  ├─ request: instructions + input + tools + text.format
  └─ response: output_text / function_call / usage / SSE lifecycle
        ↓
AgentStreamResult 或文本 Result
        ↓
AgentCore 工具循环、UI、用量记录
```

## 协议约束

- Responses 请求端点为 `<base>/v1/responses`；若 base URL 已以版本段（如 `/v1`）结尾，只追加 `/responses`。
- 工具使用扁平结构：`{type:"function",name,description,parameters}`。
- 模型工具调用映射为 `function_call`，工具结果映射为 `function_call_output`，二者通过 `call_id` 关联。
- 结构化输出使用 `text: {format: {type:"json_schema",name,schema,strict}}`。
- 流式文本消费 `response.output_text.delta`；工具参数消费 `response.output_item.added`、`response.function_call_arguments.delta`、`response.function_call_arguments.done` 和 `response.output_item.done`。
- 只有 `response.completed` 表示完整成功；`response.incomplete` 标记为截断并保留 usage，`response.failed` 和 `error` 走 API 错误回调。
- usage 统一映射为 `prompt_tokens`、`completion_tokens`、`total_tokens`，并保留 Responses 的 `input_tokens_details` / `output_tokens_details` 供缓存统计。

## 错误与兼容

- 保持非 Responses 路径不变，未知 `apiPathType` 仍按旧 OpenAI 兼容路径处理。
- 4xx/5xx 提取 Responses `error.message`，不把具体原因压成单独的 HTTP 状态码。
- 流没有终止事件时返回 `truncated=true`，由 AgentCore 的既有续跑/熔断逻辑处理。
- 取消异常原样抛出；其他异常必须回调 `onError`，避免调用方永久等待。
- Responses 不发送 Chat Completions 专属字段（`messages`、`max_tokens`、`stream_options`、`response_format`）。

## 验收标准

1. 协议单元测试能证明 endpoint、input、工具、结构化输出、文本提取、函数调用聚合和 usage 映射正确。
2. `:provider:testDebugUnitTest`、`compileDebugKotlin`、`assembleDebug`、`lintDebug` 和 `testDebugUnitTest` 在 GitHub Actions 通过。
3. Release 工作流成功生成签名 APK 并上传到正式 GitHub Release。
4. `rg` 扫描源码、构建产物和提交差异，不出现真实 API Key；CI 只使用 GitHub 内置 `GITHUB_TOKEN` 上传 Release，不把 OpenAI Key 注入 APK 构建。
