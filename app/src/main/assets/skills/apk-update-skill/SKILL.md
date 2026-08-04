---
name: apk-update-skill
description: APK 自动化更新管理。当用户要检查 APK/LSPosed 模块是否有新版本、从 GitHub Release 下载、安装更新、清理残留时激活。纯命令行实现，不依赖第三方脚本。
license: MIT
---

# APK 更新管理

## 触发条件

- "检查 XX 有没有更新"、"更新这个模块/APK"、"下载最新版"、"装不上/版本对不上"

## 工作流

### 1. 查本地版本

```bash
dumpsys package <包名> | grep -E "versionCode|versionName"
# 或
pm dump <包名> | grep versionName
```

### 2. 查远程最新版本（GitHub Release）

```bash
# 取最新 release 的 tag 与资产名
curl -s https://api.github.com/repos/<owner>/<repo>/releases/latest | \
  python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('tag_name')); [print(a['name'], a['browser_download_url']) for a in d.get('assets',[])]"
```

### 3. 版本对比（semver，内联脚本）

```bash
python3 -c "
import sys
def cmp(a,b):
    import re
    def k(x): return [int(p) if p.isdigit() else p for p in re.split(r'[._-]', x.lower())]
    return (k(a)>k(b)) - (k(a)<k(b))
print(cmp('$本地版','$远程版'))   # 1=有更新 0=相同 -1=本地更高
"
```

### 4. 下载并安装

```bash
curl -L -o /data/local/tmp/<文件名> '<下载URL>'
# 校验：优先对比 release 页面给的 sha256（有则必须验）
pm install -r /data/local/tmp/<文件名>
```

- 安装失败先看错误类别：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不一致）/ `VERSION_DOWNGRADE`（版本回退）/ 磁盘空间
- FUSE 权限问题（Android 14+）：把 APK 先放 `/data/local/tmp` 再 `pm install -r`，不要直接装共享存储路径

### 5. 清理残留

```bash
rm -f /data/local/tmp/<文件名>
```

## 输出格式

1. 本地版 → 远程版（有无更新）
2. 下载校验结果
3. 安装结果与下一步
