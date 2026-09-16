# XINCODE Android 自动化测试流程

> 建立日期：2026-09-15 ｜ 对象：本仓库（`XINCODE-Public`，纯 Kotlin 原生 Android 应用，8 个 Gradle 模块）
> 目标与桌面端一致：形成 **写代码 → 自动跑测试 → 按失败定位修复 → 回归** 的闭环，而不是只做静态检查。

---

## 0. 本轮结论

| 项 | 数值 |
|---|---|
| 单元测试 | **206 用例 / 0 失败**（新增 19 条后） |
| 测试类 | 33 个（原 31 + 新增 2） |
| 覆盖模块 | app / core / data / provider / security / tools |
| 真实打包 | `:app:assembleDebug` **成功**（`app-debug.apk` 79.7 MB） |
| 本轮发现并修复缺陷 | **4 个**（3 个由新用例直接打出，1 个在修复同一处时一并发现） |

---

## 1. 运行环境（本机原本一样都没有，已装好）

原本这台机器**没有 JDK、没有 Android SDK、没有 Gradle 缓存**，也没构建过。已就位：

| 组件 | 路径 | 说明 |
|---|---|---|
| JDK 17 | `<TOOLCHAIN>\jdk17` | Temurin 17.0.20.1 |
| Android SDK | `<TOOLCHAIN>\android-sdk` | platform-34 + build-tools 34.0.0 + platform-tools |
| Gradle 8.7 | `<TOOLCHAIN>\gradle-home` | 由 wrapper 自动下载 |
| ASCII 联接 | `<ASCII_LINK>` → 本仓库 | 绕开 AGP 的中文路径限制 |

### 三个必须知道的坑（缺一个都跑不起来）

1. **项目路径含中文** → AGP 直接报错并拒绝构建：
   `Your project path contains non-ASCII characters`。
   **解法**：给仓库建一个 ASCII 路径的目录联接再构建（脚本会自动建），不改项目文件、也不加 `android.overridePathCheck`。
2. **Java NIO 的 Selector 建不起来** → Gradle 报 `Unable to establish loopback connection`。
   根因不是防火墙：默认的 unix-domain socket 临时目录落在 `%TEMP%`
   （8.3 短名路径），AF_UNIX 回环管道在那条路径上 `connect` 返回 `Invalid argument`。
   **解法**：`-Djdk.net.unixdomain.tmpdir=<普通长路径>`（脚本里通过 `JAVA_TOOL_OPTIONS` 传给所有 JVM，
   否则 fork 出来的 Gradle daemon 拿不到，照样失败）。
3. **仓库里只有 `gradlew`（Unix 脚本），没有 `gradlew.bat`**。
   **解法**：直接用 JDK 跑 wrapper 主类：
   `java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ...`

> 以上三点已全部封装进 `scripts/android-test.ps1`，直接调用即可。

---

## 2. 分层策略

```
┌ 第 3 层 端到端（真机/模拟器）───────────────────────────┐
│ Android 侧当前【不可用】：本机无 adb 设备、无模拟器，       │
│ 且模拟器需要硬件虚拟化（未验证可用）。                     │
│ 可替代的"真实运行"证据：:app:assembleDebug 打成真实 APK。   │
└───────────────────────────────────────────────────────┘
┌ 第 2 层 集成（Android 框架 / Room / Compose）───────────┐
│ 当前【缺失】：未引入 Robolectric，所以碰 Android API 的    │
│ 代码只能靠 instrumented test（需设备）。                  │
│ 已有部分集成性质的数据层测试：SchemaConsistencyTest、      │
│ HarnessDrillTest。                                      │
└───────────────────────────────────────────────────────┘
┌ 第 1 层 单元（JVM, JUnit4 + kotlinx-coroutines-test）───┐
│ 33 个测试类 / 206 用例，纯逻辑与状态机，秒级完成。          │
│ 跑法：scripts/android-test.ps1                          │
└───────────────────────────────────────────────────────┘
```

**与桌面端的对照**：桌面端能做到"真实应用内跑 E2E"，是因为 VSCodium 支持
`--extensionTestsPath` 直接在真实扩展宿主里执行断言；Android 没有等价的无设备通道，
所以这里的"真实运行"边界是 **真实 APK 构建成功**，再往上必须接设备。

---

## 3. 本轮发现并修复的缺陷

### BUG-A（严重）工具缓存键 32 位哈希碰撞 → `file_read` 返回**另一个文件**的内容

- **现象**：读路径 `Aa` 缓存后，读路径 `BB` 会直接返回 `Aa` 的内容。
- **根因**：`ToolCache.argHash()` 把参数串压成 `hashCode()`。
  Java 字符串 hashCode 极易碰撞 —— `"path=Aa"` 与 `"path=BB"` 同哈希
  （`65*31+97 == 66*31+66 == 2112`），于是两者落到同一个缓存键。
- **证据**（真实测试输出）：
  ```
  哈希碰撞不得串数据：读 Aa 的缓存被当成了 BB 的内容 -> FILE-Aa
  expected null, but was:<Success(output=FILE-Aa)>
  ```
- **影响**：不是性能问题，是**正确性/数据串扰**。同类风险适用于
  `list_dir` / `shell_exec` —— 用户可能看到另一个目录或另一条命令的输出。
- **修复**：缓存键改用**参数原串**（不再做 32 位压缩）。本缓存的参数
  （路径 / 查询词 / 命令）都很短，键长代价可忽略。
- **回归**：`ToolCacheTest.hashCollidingPaths_mustNotShareEntry`

### BUG-B（中）`ToolCache(maxEntries = 0)` 时 `put` 抛 `NoSuchElementException`

- **根因**：`if (cache.size >= maxEntries)` 对空 map 成立后直接 `iter.next()`，空迭代器抛异常。
- **修复**：容量 ≤ 0 直接不缓存；淘汰前补 `hasNext()` 判断。
- **回归**：`ToolCacheTest.boundary_zeroCapacity_doesNotThrow`

### BUG-C（中）更新已有缓存键会误淘汰无关条目

- **根因**：`put` 不区分"新增"与"更新"，只要 `size >= maxEntries` 就先淘汰最旧的一条。
  更新一个已存在的键会凭空挤掉一个无关条目，缓存命中率无谓下降。
- **修复**：仅在插入**新键**时淘汰（`!cache.containsKey(k)`）。
- **回归**：`ToolCacheTest.lru_updatingExistingKey_doesNotEvictOthers`

### BUG-D（中）错误文案可能为空

- **现象**：`ApiError.from(IOException())`（无 message）产出**空字符串**文案，UI 显示一片空白，
  用户既看不懂也报不了。
- **根因**：`IOException` 分支的 `else -> UnknownError(msg, e)` 直接用了可能为空的 `msg`；
  兜底分支 `e.message ?: "未知错误"` 也挡不住**空串**（只挡 null）。
- **修复**：两处都改用 `ifBlank` / `takeIf { isNotBlank() }` 兜底。
- **回归**：`ApiErrorTest.exception_ioExceptionWithoutMessage_mustNotRenderBlank`、
  `exception_nullMessage_stillProducesReadableText`

### 新增用例覆盖的其它行为（这些本来就是对的，纳入回归防止将来退化）
- `ToolCache`：命中、参数顺序无关、副作用工具不缓存、错误结果不缓存、不同参数不串、LRU 淘汰、`invalidate` 不误伤、空参数
- `ApiError`：401/403/4xx/5xx 分类、`SocketTimeout`/`UnknownHost`/`Connect`/`JSON` 异常分类、
  服务器原文透出、无 detail 的 HTTP 错误、已分类异常直通、未知状态码

---

## 4. 怎么跑

```powershell
# 单元测试（全部模块）
pwsh scripts/android-test.ps1

# 单元测试 + 真实打包（APK）
pwsh scripts/android-test.ps1 -Apk

# 只跑某个测试类
pwsh scripts/android-test.ps1 -Task ":core:testDebugUnitTest" -Filter "*ToolCacheTest"
```

前置：`-ToolchainRoot`（默认 `F:\_toolchain`）下有 JDK17 / android-sdk；脚本会检查并给出明确报错。
脚本会自动建 ASCII 联接、设置 `JAVA_TOOL_OPTIONS` 等环境变量。

---

## 5. 剩余风险与缺口

| # | 缺口 | 影响 | 建议 |
|---|---|---|---|
| 1 | **没有端到端层**（无设备/模拟器） | UI 交互、Activity 生命周期、通知栏审批、前台服务等**未被任何自动化覆盖** | 接一台真机（`adb`）或开模拟器，补 `androidTest`（Espresso / Compose UI Test） |
| 2 | **没有 Robolectric** | 碰 Android API 的逻辑（Keystore、Room、SharedPreferences、Compose 状态）只能在设备上测 | 引入 Robolectric 后可把大量集成测试拉回 JVM |
| 3 | `ui` 与 `service` 模块**零测试** | 共享 UI 组件与前台服务/后台任务无回归保护 | 优先给 `service`（前台服务、WorkManager）补测试 —— 后台逻辑最容易出"只有真机才现"的问题 |
| 4 | 缓存修复后**键变长** | `ToolCache` 的键不再定长；极端长参数（如 `shell_exec` 命令）会占更多内存 | 若将来担心内存，可换成 SHA-256 摘要（仍是抗碰撞的，非 32 位） |
| 5 | 未做**变异测试 / 覆盖率统计** | 206 个用例"全绿"不代表覆盖率高，可能存在大量未被断言的分支 | 接 JaCoCo 看覆盖率，再针对低覆盖模块补用例 |
| 6 | 构建依赖**外部网络**首次下载 | 干净机器上首次运行需下载 Gradle + 依赖（约数分钟） | CI 里做依赖缓存（`GRADLE_USER_HOME` 缓存） |
| 7 | 中文路径仍需联接 | 直接在本仓库路径下跑 Gradle 会失败 | 长期建议：把仓库放到纯 ASCII 路径，或接受联接方案 |

---

## 6. 与桌面端流程的差异小结

| 维度 | 桌面端（XINCODE Studio） | 手机端（Android） |
|---|---|---|
| 单元 | 235 用例（Node test runner） | 206 用例（JUnit4） |
| 跨边界契约测试 | 有（webview ⇄ 扩展宿主，14 条） | 暂无（对应物是 UI ⇄ ViewModel） |
| 真实运行 E2E | **有**（真实 XINCODE.exe 内跑 77 条） | **缺**（需设备/模拟器） |
| 真实产物校验 | `verify:artifact` 比对 SHA-256 | `assembleDebug` 产出真实 APK |
| 环境依赖 | 一个 exe，无额外安装 | JDK + SDK + Gradle（已装好） |
