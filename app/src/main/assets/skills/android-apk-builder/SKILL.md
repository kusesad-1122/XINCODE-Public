---
name: android-apk-builder
description: 在终端中为 Android/Gradle 项目编译 Debug/Release APK 与 AAB。当用户要构建 APK、跑 gradlew、定位构建产物、安装到设备时激活。含依赖检查、自动识别 SDK/模块、输出路径定位。
---

# Android APK / AAB 构建

## 触发条件

- "帮我编译 APK"、"构建 debug/release 包"、"gradlew 怎么跑"
- 需要构建 Android 项目、修复构建错误、定位 APK 产物、安装到设备

## 环境与前置

优先 `env_exec` 进内置 Ubuntu（或 root shell）。构建前先探明项目情况：

```bash
# 项目根：找 settings.gradle.kts / settings.gradle / build.gradle
ls <项目路径>/settings.gradle* <项目路径>/build.gradle* 2>/dev/null
# Gradle wrapper 与 JDK
ls <项目路径>/gradlew && java -version 2>&1 | head -1
# SDK 位置
echo $ANDROID_HOME; ls $ANDROID_HOME/platforms 2>/dev/null
```

缺 JDK/SDK 时先装（Android 构建需要 JDK 17+）：

```bash
apt-get install -y -qq openjdk-17-jdk-headless
```

## 工作流

### 1. 识别模块与变体

```bash
grep -E 'include' <项目路径>/settings.gradle* | head
grep -E 'buildTypes|productFlavors' <项目路径>/app/build.gradle* | head
```

### 2. 构建

```bash
cd <项目路径> && ./gradlew :app:assembleDebug --stacktrace
# Release（有签名配置时）：
./gradlew :app:assembleRelease
# 构建 AAB：
./gradlew :app:bundleRelease
```

- 长构建用后台执行，轮询进度；失败先读**第一个**真实错误（`--stacktrace` 后的 cause），别反复重试同一命令
- 依赖下载慢可加 `--offline` 试缓存，不要擅自改 gradle 配置

### 3. 定位产物

```bash
ls -la <项目路径>/app/build/outputs/apk/debug/ 2>/dev/null
ls -la <项目路径>/app/build/outputs/apk/release/ 2>/dev/null
ls -la <项目路径>/app/build/outputs/bundle/release/ 2>/dev/null
```

### 4. 安装到设备（可选）

```bash
adb devices 2>/dev/null   # 确认设备连接
adb install -r <产物.apk>
```

## 输出格式

1. 构建命令与结果（成功/失败+根因）
2. 产物绝对路径与大小
3. 若失败：第一个真实错误、已尝试的修复、未测试部分

## 注意

- 绝不修改项目的 `build.gradle.kts` / `gradle` 配置来"让构建变绿"（版本号、签名配置由项目所有者决定）
- 绝不把 `keystore.properties` / `*.jks` 的内容打印或提交
