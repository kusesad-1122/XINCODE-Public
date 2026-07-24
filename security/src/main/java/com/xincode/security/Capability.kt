package com.xincode.security

/**
 * 10 capability categories for the security gate.
 * Each maps to a switch the user can toggle (default all OFF).
 */
enum class Capability(val label: String) {
    APP("应用管理"),           // install/uninstall apps
    SYSTEM("系统设置"),         // settings, props, env
    FS("文件系统"),             // file read/write/delete
    PROCESS("进程管理"),        // kill, nice, ps
    NET("网络"),                // iptables, curl, wget
    BACKUP("备份"),             // tar, dd backup
    SCREEN("屏幕模拟"),         // input tap/swipe
    MAGISK("Magisk"),          // magisk, su modules
    KERNEL("内核"),             // insmod, sysctl, mount
    SENSOR("传感器"),           // sensor access
    UNKNOWN("未知")             // catch-all
}