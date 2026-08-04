---
name: apktool-tool
description: APK 反编译与回编译工具。当用户需要对 APK 解包看资源/smali、修改后重新打包、或修改 AndroidManifest/图标/签名相关配置时激活。
---

# APK 反编译与回编译

## 触发条件

- "反编译这个 APK"、"改 APK 里的资源"、"回编译"、"看 smali"、"改包名/图标/权限"
- 需要修改 APK 的 manifest、资源、smali 后重新打包

## 边界

- 只处理用户自己拥有或明确授权修改的 APK
- 修改后必须如实告知：改动内容、签名状态（回编译后签名失效）、安装风险
- 不协助绕过应用的授权/付费/风控校验逻辑

## 环境与安装

优先 `env_exec` 进入内置 Ubuntu；apktool 是纯 Java 工具，arm64 无 native 依赖：

```bash
which apktool || (apt-get update -qq && apt-get install -y -qq apktool)
apktool --version
```

## 工作流

### 1. 反编译

```bash
apktool d <目标.apk> -o <输出目录> -f
```

输出结构：
- `AndroidManifest.xml` —— 权限/组件/包名
- `res/` —— 资源（图标 mipmap、布局、字符串）
- `smali/` 或 `smali_classesN/` —— Dalvik 字节码（多 dex 分目录）

### 2. 常见修改

```bash
# 看包名/权限
grep -E 'package=|uses-permission' <输出目录>/AndroidManifest.xml | head -20
# 改应用名/字符串
grep -r "应用名" <输出目录>/res/values/strings.xml
# 找图标
ls <输出目录>/res/mipmap-*/ | head
```

### 3. 回编译

```bash
apktool b <反编译目录> -o <新包.apk>
```

产物即 `<新包.apk>`。**回编译后签名丢失**：安装前需要重新签名（debug 签名可临时安装测试，正式分发必须用户自己的密钥），未签名 APK 用 `apksigner verify` 检查。

## 输出格式

1. 改动清单（改了什么、为什么）
2. 回编译/签名状态
3. 安装验证方式
