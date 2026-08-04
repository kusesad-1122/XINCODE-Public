---
name: LSPosed-Mod-Dev
description: LSPosed/Vector 模块开发。当用户要开发 Xposed/LSPosed 模块、排查模块不生效、迁移旧 Xposed 模块、分析 hook 失效原因时激活。只协助合法授权、学习型、兼容适配型开发。
---

# LSPosed / Vector 模块开发

## 重要现状（先读）

- **LSPosed 官方仓库已于 2026-05 归档（read-only），支持止于 Android 8.1–14**
- 活跃继承者是 **JingMatrix 分叉 → 正式改名 Vector**（v2.0+，支持 Android 10–16）
- 新项目**默认按 Vector 生态开发**；用户明确要求老 LSPosed 兼容时才按老 API
- libxposed API 版本不要写死（原技能写 API 102，Vector 2.0 自述是 API 100 era）——**让模型先探测实际环境**（看用户项目的 libxposed 依赖版本）

## 触发条件

- "开发 Xposed 模块"、"hook 不生效"、"模块不工作"、"scope 没生效"
- "迁移老 Xposed 模块"、"libxposed"、"LSPosed/Vector 模块"

## 边界（严格遵守）

允许：创建模块工程、合法 Hook 代码、分析模块不生效原因、排查 scope/ClassLoader/方法签名/生命周期、迁移旧模块、代码审查。
拒绝：绕过检测/风控/反作弊/支付/授权/版权机制、隐蔽注入/隐藏模块/规避审计、未授权修改第三方 App 行为、窃取隐私凭据、恶意控制设备/持久化后门、对 system_server/SystemUI/native 的高风险 Hook（无明确合法目的）。

## 模块工程骨架

```
<module>/
├── module.prop            # id/name/version/author/description
├── build.gradle.kts       # 依赖 libxposed:api / libxposed:service
├── src/main/AndroidManifest.xml   # meta-data xposedmodule=true + xposedminversion
├── META-INF/xposed/java_init.list # 入口类全限定名
└── 入口类 implements io.github.libxposed.api.XposedModule
```

```kotlin
// 入口示例（探测式，不写死 API 版本）
class Main : XposedModule {
    override fun onPackageLoaded(param: LoadPackageParam) {
        if (param.packageName != "目标包") return
        // 最小 hook：明确 scope、可诊断日志、安全回退
    }
}
```

## 排查不生效的固定顺序

1. `module.prop` 与 `java_init.list` 是否正确（路径/类名/编码）
2. 模块是否在框架里启用且 scope 勾选目标包
3. 目标包是否被 Xposed 放行（selinux/白名单）
4. ClassLoader：用 `param.classLoader`，别用全局类加载器
5. 方法签名/重载精确匹配；hook 时机（`afterHookedMethod` vs `before`）
6. 日志：`XposedBridge.log` 或模块自带日志，先看有没有"模块已加载"标记
7. 版本兼容：目标 App 更新后方法/类名变化导致 hook 失效

## 输出格式

1. 结论（能/不能/需用户提供 X）
2. 改动文件与关键代码
3. 验证方式（重启生效？日志位置？）
4. 风险说明（未测试部分）
