---
name: ghidra-analysis
description: 逆向分析专家。当用户需要分析二进制/APK/SO/ELF/DEX 文件，或提到静态分析/反编译/逆向/脱壳/固件时激活。分层使用 jadx / apktool / Ghidra headless 完成手机上的静态分析。
---

# 二进制与 APK 静态分析

## 触发条件

用户提到以下内容时激活本技能：
- "分析XX文件"、"逆向"、"反编译"、"静态分析"、"看下这个 so"
- 提到 ELF/SO/DEX/APK/二进制/固件/提权/注入等逆向相关关键词
- 询问文件/二进制/程序的功能、逻辑、行为

## 硬性边界

- 只分析**用户自己拥有或明确授权**的文件，先问清来源再动手
- 不提供绕过检测、反作弊、支付风控的"隐藏"手段；分析目的是理解与学习
- 分析结果只做行为说明，不给攻击滥用步骤

## 分层策略（先识别文件类型，再选工具）

| 文件类型 | 主力工具 | 说明 |
|---|---|---|
| APK / DEX | **jadx** | 反编译成 Java 可读源码，先读逻辑 |
| APK 资源/smali 修改 | **apktool** | 反编译/回编译，配合签名 |
| `.so` / ELF / 固件 | **Ghidra headless** | 只有到这一层才需要 Ghidra |

原则：能用 jadx 读懂的绝不上 Ghidra（手机上 Ghidra 慢、重）；Ghidra 装不起来时降级 `objdump -T` / `readelf` 看导出符号。

## 环境准备（XINCODE 内置 Ubuntu）

优先用 `env_exec` 进入内置 Ubuntu 环境执行（root+chroot，apt 可用）；root shell（`su_exec`）仅在需要读受保护文件时用。

工具安装与探测（都先探测已装的，别重复装）：

```bash
# jadx / apktool：纯 Java，无 native 依赖，装完即用
which jadx apktool || (apt-get update -qq && apt-get install -y -qq jadx apktool)
# Ghidra：下载 release zip 解压到 /opt，或探测已有安装
ls -d /opt/ghidra* 2>/dev/null || echo "Ghidra 未安装"
```

**版本一律探测，不写死**：

```bash
ls -d /opt/ghidra* 2>/dev/null; java -version 2>&1 | head -1
```

## 工作流

### 1. 基本信息

```bash
file <目标文件>          # 格式与架构
readelf -h <so文件>      # ELF 头（架构/入口）
```

### 2. APK / DEX → jadx

```bash
jadx -d <输出目录> <目标.apk>     # 输出 Java 源码
# 只看关键类：
jadx --no-res -d <输出目录> --single-class <全限定类名> <目标.apk>
grep -rn "关键字符串" <输出目录>/sources | head -20
```

### 3. APK 资源/smali → apktool

```bash
apktool d <目标.apk> -o <输出目录>   # 反编译（资源+smali）
apktool b <反编译目录>               # 回编译，产物在 <目录>/dist/
# 回编译后的 APK 未签名，安装前需签名（用户要求安装时再处理）
```

### 4. `.so` / ELF → Ghidra headless

Ghidra 首次启动加载慢（30-60 秒），命令超时建议 300000ms。headless 基本用法：

```bash
GHIDRA_HOME=$(ls -d /opt/ghidra* | head -1)
"$GHIDRA_HOME/support/analyzeHeadless" /tmp/ghidra_proj Proj -import <目标.so> \
  -analysisTimeoutPerFile 300 -scriptPath /tmp/ghidra_scripts -postScript GhidraDump.java <输出目录>
```

无 Java 脚本环境时，退而求其次：

```bash
# 导出符号与反汇编
readelf -Ws <目标.so> | head -40          # 动态符号
objdump -d --no-show-raw-insn <目标.so> | head -100
```

## 输出格式

1. 文件基础信息（格式/架构/入口点/加壳迹象：`packer`、自定义段、异常少的导出表）
2. 可疑 API / 行为标记（mount/fork/popen/inotify/execve/文件重定向）
3. 核心函数/逻辑说明（标 file:offset）
4. 行为推断
5. 进一步分析建议

## 局限

- 不支持 GUI 交互调试；不支持动态分析
- 超大文件（>5000 条指令的函数）可能超时
- 加壳 APK 需要先脱壳（脱壳工具现状：FART/youpk/BlackDex 均停更且不支持 Android 13+，遇加壳如实说明，不推荐死路工具）
