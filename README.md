# XINCODE

**纯 Kotlin 原生 Android AI 智能体。** 一个运行在手机上的自主 AI Agent:多供应商接入、工具调用循环、子智能体并行协作、长期记忆、内置 root/Ubuntu 环境与终端,以及像素风「智能体指挥室」实时动画。从零构建,无跨端框架。

> 版本:**1.12**(versionCode 122) · 许可:**GPL-3.0-or-later**

---

## ✨ 主要能力

- **智能体核心** — OpenAI Chat Completions / Responses API / DeepSeek / Anthropic 等多供应商,自定义端点与模型清单;工具调用循环 + 结构化输出 + Prompt 缓存纪律。
- **计划 / 协作模式** — 计划模式可视化任务卡;协作模式下主脑把任务并行派发给多个专职子智能体,汇总回主脑。
- **指挥室** — 像素风「智能体指挥室」(WebView + HTML5 Canvas),每个子智能体一个工位与像素小人,派活即联动动画。
- **环境与终端** — root 终端;内置 Ubuntu 环境(root + chroot 自动部署),可直接执行命令;一键部署常用开发环境。
- **记忆** — 长期记忆(FTS 全文检索 + 向量语义检索,自动沉淀);普通对话之间**记忆互通**,项目内对话**按项目隔离**;精编两文件记忆(用户画像 / 近况)由后台复盘分身维护。
- **上下文与成本** — 输入框旁上下文圆环(绿→蓝→黄→红)、实时 token / 缓存命中、可配置压缩阈值、人民币成本显示(缓存感知)。
- **效率与自动化** — 多引擎联网搜索(必应 / 百度 / 搜狗 / DuckDuckGo 融合 + 正文抓取)、Goal/Work 多任务、定时任务(cron / WorkManager)、语音转写、视觉与深度推理委托副模型。
- **技能与 MCP** — 内置基础技能 + 技能管理,`/技能名` 主动调用;MCP 支持(stdio 本地传输 + HTTP),`@服务器名` 优先使用其工具。
- **安全** — 权限模式(询问 / 完全访问)、敏感操作确认卡、审计日志。

完整清单见 [`CHANGELOG.md`](CHANGELOG.md)。

---

## 🧱 工程结构

多模块 Gradle 工程(Kotlin DSL):

| 模块 | 职责 |
| --- | --- |
| `app` | Android 入口、Compose UI、智能体编排、指挥室 WebView |
| `core` | AgentCore、工具注册表、调度循环 |
| `provider` | 各 LLM 供应商客户端(OpenAI 兼容 / Anthropic 等) |
| `data` | Room 持久化、记忆(FTS + 向量)、记忆抽取 |
| `security` | 权限模式、敏感操作门控、审计 |
| `tools` | 内置工具实现 |
| `service` | 前台服务、后台任务、通知 |
| `ui` | 共享 UI 组件与主题 |

- 语言:Kotlin · UI:Jetpack Compose · 存储:Room
- `minSdk 28` · `targetSdk 34` · `compileSdk 34`

---

## 🔨 从源码构建

### Debug 包(无需签名密钥)

```bash
git clone <this-repo-url>
cd XINCODE
./gradlew :app:assembleDebug
# 产物:app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 与 Android SDK(Platform 34)。若本机未装 SDK,在项目根新建 `local.properties` 指向 SDK 路径:`sdk.dir=/path/to/Android/Sdk`。

### Release 签名包(在你自己的电脑上)

签名密钥是你的私有机密,**永远不进仓库**(`keystore.properties`、`*.jks`、`*.keystore` 均已在 `.gitignore` 中)。

1. 复制模板:`cp keystore.properties.example keystore.properties`
2. 若还没有密钥,生成一个:
   ```bash
   keytool -genkeypair -v -keystore xincode-release.jks \
     -alias xincode -keyalg RSA -keysize 2048 -validity 10000
   ```
3. 把 `keystore.properties` 里的四项填成你的真实值(`storeFile` / `storePassword` / `keyAlias` / `keyPassword`)。
4. 打签名包:
   ```bash
   ./gradlew :app:assembleRelease
   # 产物:app/build/outputs/apk/release/app-release.apk
   ```

> ⚠️ 请**离线备份** `.jks` 与这些口令——一旦丢失,将无法再为同一应用发布更新。
> 若 `keystore.properties` 不存在,构建脚本会自动跳过签名配置(Debug 仍可正常构建)。

---

## 🔐 权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 调用 LLM API、联网搜索、抓取正文 |
| `RECORD_AUDIO` | 语音转写(仅在你主动使用语音输入时) |
| `POST_NOTIFICATIONS` | 后台任务完成通知 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 后台推进长任务(Goal/Work、定时任务) |

应用不含广告与第三方追踪。API Key 等敏感配置仅保存在本机;`android:allowBackup="false"` 已关闭系统自动备份。

---

## ⚙️ 使用前配置

首次启动后,进入设置填写你自己的 LLM 供应商信息(端点 + API Key + 模型)。需要 OpenAI Responses API 时选择「OpenAI Responses」路径；它会调用 `/v1/responses`，并将工具调用、结构化输出和流式事件转换为应用内部格式。XINCODE 不内置任何托管密钥，API Key 只保存在设备 Keystore 加密配置中，正式包不包含任何 Key。

---

## 📄 许可与第三方声明

- 本项目主体代码采用 **GNU General Public License v3.0 or later**(见 [`LICENSE`](LICENSE))。
- 打包/引用的第三方字体、像素素材与 Maven 依赖,依其各自许可使用,详见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 与 [`licenses/`](licenses/)。
  - JetBrains Mono 字体 — SIL OFL 1.1
  - 角色 sprite(MetroCity)— CC0 1.0
  - 地板/家具/装饰/地毯(pixel-agents)— MIT

## 🙏 致谢

XINCODE 在设计过程中参考了多个开源 AI 智能体的公开设计思路(*inspired by*,并未搬运/移植其代码):

- [xai-org/grok](https://github.com/xai-org) — 工具调用循环与终端 agent 交互思路
- [NousResearch](https://github.com/NousResearch) Hermes 系列 — 自进化学习闭环、精编记忆、零上下文工具-RPC 等设计思路
- [pixel-agents-hq/pixel-agents](https://github.com/pixel-agents-hq/pixel-agents) — 像素办公室场景灵感与素材来源
