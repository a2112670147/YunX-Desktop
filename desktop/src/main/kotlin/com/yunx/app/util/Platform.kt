package com.yunx.app.util

/**
 * 平台判定与平台相关命名的**唯一出口**。
 *
 * 为什么要集中：项目原本是纯 Windows 应用，`os.name` 判断、`.exe` 后缀、`windows-amd64`
 * 这类字面量散落在各处（Gopeed 内核、ffmpeg 二进制、托盘、单实例窗口聚焦……）。
 * 加 Linux 支持时如果继续就地写 `if (os.name.contains("win"))`，很快就会漏。
 * 新代码一律走本对象，平台差异在一处可见。
 *
 * 注意：本项目只支持 Windows / Linux / macOS 三个桌面平台，不做 arm 细分
 * （jcefmaven 与 gopeed 的 arm 包命名不同，真有需要时在这里加，别改调用点）。
 */
object Platform {

    /** 与 `System.getProperty("os.name")` 一致的原始值，小写 */
    val osName: String = System.getProperty("os.name", "").lowercase()

    val isWindows: Boolean = osName.contains("win")
    val isLinux: Boolean = osName.contains("linux")
    val isMac: Boolean = osName.contains("mac")

    /** 可执行文件后缀：Windows 是 `.exe`，其它平台为空串 */
    val exeSuffix: String = if (isWindows) ".exe" else ""

    /**
     * 把「基础名」补成当前平台的可执行文件名。
     * 例：`binaryName("ffmpeg")` → Windows `ffmpeg.exe` / Linux `ffmpeg`
     */
    fun binaryName(base: String): String {
        // 调用点有时已经带了 .exe（历史代码里是字面量），避免出现 ffmpeg.exe.exe
        val trimmed = base.removeSuffix(".exe").removeSuffix(".bat")
        return trimmed + exeSuffix
    }

    /**
     * 发布资产里的平台标签，对应上游二进制的命名约定。
     * - `windows-amd64`：gopeed / jcefmaven 的 Windows 包
     * - `linux-amd64`：gopeed 的 Linux 包
     * - `macosx-amd64`：jcefmaven 的 macOS 包
     */
    fun osTag(): String = when {
        isWindows -> "windows-amd64"
        isLinux -> "linux-amd64"
        else -> "macosx-amd64"
    }

    /**
     * 上游资产名里的「平台」段（不含架构），各项目命名不统一，这里取最常见的
     * `windows` / `linux` / `darwin` 三值（Gopeed 官方 Release 用的就是这三个）。
     */
    fun assetPlatform(): String = when {
        isWindows -> "windows"
        isLinux -> "linux"
        else -> "darwin"
    }

    /**
     * 是否运行在 Wayland 原生会话下（而非 XWayland）。
     *
     * 预览播放器的「窗口内嵌」依赖 X11 的 XReparentWindow：在纯 Wayland 窗口上拿不到
     * 可 reparent 的 X 窗口，此时自动降级为独立播放窗口。
     * `WAYLAND_DISPLAY` 存在但 `XDG_SESSION_TYPE=x11`（XWayland 内的客户端）仍按 X11 处理。
     */
    fun isWaylandSession(): Boolean {
        if (!isLinux) return false
        val sessionType = System.getenv("XDG_SESSION_TYPE").orEmpty().lowercase()
        if (sessionType == "x11") return false
        if (sessionType == "wayland") return true
        return !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
    }
}
