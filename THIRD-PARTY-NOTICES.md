# 第三方声明 / Third-Party Notices

XINCODE 主体代码采用 **GPL-3.0-or-later** 许可(见 LICENSE)。除此之外,以下第三方素材与依赖被打包/引用,依其各自许可协议使用与再分发。

## 字体 / Fonts

- **JetBrains Mono** — Copyright 2020 The JetBrains Mono Project Authors。
  - 许可:SIL Open Font License 1.1。
  - 全文:[`licenses/JetBrainsMono-OFL-1.1.txt`](licenses/JetBrainsMono-OFL-1.1.txt)
  - 位置:`app/src/main/res/font/jetbrains_mono.ttf`

## 像素素材 / Pixel Assets

- **角色 sprite(characters)** — JIK-A-4「MetroCity」免费 topdown 角色包(https://jik-a-4.itch.io/metrocity-free-topdown-character-pack)。
  - 许可:Creative Commons Zero v1.0 Universal(CC0)。
  - 全文:[`licenses/MetroCity-CC0.txt`](licenses/MetroCity-CC0.txt)
  - 位置:`app/src/main/assets/pixel/characters/`

- **地板 / 家具 / 装饰 / 地毯** — 来自 pixel-agents(https://github.com/pixel-agents-hq/pixel-agents),Copyright (c) 2026 Pablo De Lucca。
  - 许可:MIT License。
  - 全文:[`licenses/pixel-agents-MIT.txt`](licenses/pixel-agents-MIT.txt)
  - 位置:`app/src/main/assets/pixel/floors/`、`furniture/`、`decor/`、`carpets/`
  - 详见 [`app/src/main/assets/pixel/ATTRIBUTION.txt`](app/src/main/assets/pixel/ATTRIBUTION.txt)

## Maven 依赖 / Maven Dependencies

- **AndroidX & Jetpack Compose**(activity-compose、material3、compose-ui、room、work-runtime-ktx 等)、**Kotlin stdlib / coroutines**、**OkHttp** — Apache License 2.0
- **libsu**(com.github.topjohnwu.libsu:core) — Apache License 2.0
- **Rhino**(org.mozilla:rhino:1.7.14) — Mozilla Public License 2.0(弱 copyleft:仅依赖使用只需归属;若修改 Rhino 源码本身需按 MPL 披露被改文件)
- **jsoup**(如引用) — MIT License

各依赖具体版本以 `app/build.gradle.kts`、模块 `build.gradle.kts` 为准。

## 启动图标 / Launcher Icon

`app/src/main/res/mipmap-*/ic_launcher.png` 与 `ic_launcher_round.png` 由项目作者提供,版权与授权与项目主体一致(GPL-3.0-or-later)。

## AI 供应商品牌图标 / AI Provider Brand Icons

- **Lobe Icons static PNG 1.95.0** — Copyright (c) 2023 LobeHub。
  - 许可:MIT License。
  - 全文:[`licenses/lobe-icons-MIT.txt`](licenses/lobe-icons-MIT.txt)
  - 来源:[`lobehub/lobe-icons`](https://github.com/lobehub/lobe-icons)
  - 位置:`app/src/main/res/drawable-nodpi/provider_*.png`

这些图标用于识别用户所配置的 AI 服务提供商。DeepSeek、OpenAI、Anthropic、Groq、智谱、通义千问、Moonshot、百度、Ollama、Nous、OpenRouter、xAI、ModelScope、SiliconFlow 与 OpenCode 等名称及标志分别属于其权利人；XINCODE 与这些提供商不存在暗示的隶属或背书关系。

## 致谢 / Acknowledgements

XINCODE 在设计过程中参考了多个开源 AI 智能体的公开设计思路,以下项目对本工程的架构与能力选型有启发,谨此致敬:

- [xai-org/grok](https://github.com/xai-org)(工具调用循环与终端 agent 交互思路)
- [NousResearch](https://github.com/NousResearch)Hermes 系列(自进化学习闭环、精编记忆、零上下文工具-RPC 等设计思路)
- [pixel-agents-hq/pixel-agents](https://github.com/pixel-agents-hq/pixel-agents)(像素办公室场景灵感与素材来源)

XINCODE 为纯 Kotlin 原生 Android 实现,并未搬运/移植上述任何项目的代码——上述致谢仅表明部分能力思路受其启发(inspired by)。
