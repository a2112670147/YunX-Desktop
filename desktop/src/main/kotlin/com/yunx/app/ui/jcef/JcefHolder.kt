package com.yunx.app.ui.jcef

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunx.app.AppContext
import com.yunx.app.util.Log
import com.yunx.app.util.Platform
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefSettings
import java.io.File

/**
 * JCEF（内嵌 Chromium）全局持有者。
 *
 * 初始化方式（启动优化）：
 * - Windows/Linux：后台线程初始化（initInBackground），不阻塞窗口显示。
 *   依据：CefApp 的 CEF 消息循环由内部 workTimer_（Swing Timer）在 AWT EDT 上泵动，
 *   与调用 CefInitialize 的线程无关；且消息泵本来就要等 EDT 转起来才工作。
 * - macOS：保持阻塞式主线程初始化（AppKit 要求主线程）。
 *
 * 可靠性策略：
 * - 单次初始化最多尝试 [MAX_INIT_ATTEMPTS] 次（含短退避），失败常见诱因是上次异常退出
 *   残留的 jcef_helper.exe 锁住缓存目录，每次尝试前先清理残留子进程；
 * - 失败不熔断：登录页打开时会再次调用 [initInBackground] 触发新一轮尝试，
 *   成功后 [browserReady] 翻转，登录页自动从粘贴模式切回内嵌浏览器模式。
 */
object JcefHolder {

    private const val TAG = "YunX-JCEF"
    private const val MAX_INIT_ATTEMPTS = 3

    @Volatile
    private var cefApp: CefApp? = null

    /** 最近一次初始化失败的原因（成功或未开始时为 null），登录页可用于提示 */
    @Volatile
    var initFailure: Throwable? = null
        private set

    /** Compose 可观察状态：CEF 初始化成功后为 true，登录页据此自动启用内嵌浏览器 */
    var browserReady by mutableStateOf(false)
        private set

    private val lock = Any()
    private var initThread: Thread? = null

    fun initBlocking() {
        synchronized(lock) {
            if (browserReady) return
            var lastError: Throwable? = null
            for (attempt in 1..MAX_INIT_ATTEMPTS) {
                try {
                    if (attempt > 1) {
                        Log.w(TAG, "JCEF init retry #$attempt")
                        Thread.sleep(600L * (attempt - 1))
                    }
                    killStaleJcefHelpers()
                    val builder = CefAppBuilder()
                    // Linux：chrome-sandbox 需要 setuid root，而 AppImage / 用户目录解包出来的
                    // 一份不可能带这个权限位，setuid 沙箱必然失败，统一换成命名空间沙箱。
                    // 见 [sandboxArgs] 的说明。
                    sandboxArgs().forEach { builder.addJcefArgs(it) }
                    val installDir = File(AppContext.cacheDir, "jcef-bundle")
                    builder.setInstallDir(installDir)
                    // 发布包带的是 app\jcef-natives.tar.xz（不含原生 jar）：
                    // 自己解包到 installDir 后让 jcefmaven 跳过安装步骤。开发运行无此压缩包，
                    // ensureInstalled 返回 false，仍由 jcefmaven 从 classpath 的原生 jar 安装。
                    if (JcefNativesInstaller.ensureInstalled(installDir)) {
                        builder.setSkipInstallation(true)
                    }
                    val settings = builder.getCefSettings()
                    // 发布包只随带 locales/zh-CN.pak（见 tools/JcefNativesPacker.kt），
                    // 这里显式指定 locale，避免按系统语言去找不存在的语言包
                    settings.locale = "zh-CN"
                    settings.windowless_rendering_enabled = false
                    // CEF 要求 cache_path 必须是 root_cache_path 的子目录
                    settings.root_cache_path = File(AppContext.cacheDir, "jcef").absolutePath
                    settings.cache_path = File(AppContext.cacheDir, "jcef/cache").absolutePath
                    settings.log_file = File(AppContext.filesDir, "jcef.log").absolutePath
                    settings.log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING
                    cefApp = builder.build()
                    browserReady = true
                    initFailure = null
                    Log.i(TAG, "JCEF initialized (attempt $attempt)")
                    return
                } catch (t: Throwable) {
                    lastError = t
                    Log.e(TAG, "JCEF init attempt $attempt failed", t)
                }
            }
            initFailure = lastError
            Log.e(TAG, "JCEF init failed after $MAX_INIT_ATTEMPTS attempts, fallback to paste mode")
        }
    }

    /**
     * 后台线程初始化：立即返回，不阻塞调用线程。
     * - 已就绪或已有初始化线程在跑时调用是空操作；
     * - 上一次尝试失败后再次调用会开启新一轮重试（登录页打开时调用，见 CookieLoginContent）。
     * 完成后 [browserReady] 置为 true，登录页自动切换为内嵌浏览器模式。
     */
    fun initInBackground() {
        synchronized(lock) {
            if (browserReady) return
            if (initThread?.isAlive == true) return
            initThread = Thread({ initBlocking() }, "JCEF-Init").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun app(): CefApp? = cefApp

    fun isAvailable(): Boolean = browserReady

    /**
     * Linux 专属的 CEF 沙箱参数（其它平台返回空）。
     *
     * 背景：CEF/Chromium 在 Linux 上有两种沙箱 ——
     * 1. **setuid 沙箱**：`chrome-sandbox` 必须 root 所有且权限 4755。AppImage 解包在
     *    用户目录下，拿不到 setuid 位（也不该去要），这条路径必然失败；
     * 2. **命名空间沙箱**：靠 unprivileged user namespace，无需特权。绝大多数发行版可用。
     *
     * 所以 Linux 上固定加 `--disable-setuid-sandbox` 走第 2 条；只有命名空间沙箱也不可用
     * （`unprivileged_userns_clone=0`，常见于部分 Debian/Ubuntu 与容器）或用户显式设置
     * 环境变量 `YUNX_CEF_NO_SANDBOX=1` 时，才整体关闭沙箱 —— 此时内嵌浏览器仍能工作，
     * 只是网页进程不再隔离，日志里会明确提示。
     */
    private fun sandboxArgs(): List<String> {
        if (!Platform.isLinux) return emptyList()
        val args = mutableListOf("--disable-setuid-sandbox")
        val forced = System.getenv("YUNX_CEF_NO_SANDBOX") == "1"
        if (forced || !userNamespacesAvailable()) {
            args += "--no-sandbox"
            Log.w(
                TAG,
                if (forced) "YUNX_CEF_NO_SANDBOX=1，已关闭 CEF 沙箱"
                else "当前内核禁用非特权 user namespace，回退 --no-sandbox（网页进程不隔离）"
            )
        }
        return args
    }

    /** 非特权 user namespace 是否可用；读不到 sysctl 时按可用处理（不轻易降级安全） */
    private fun userNamespacesAvailable(): Boolean {
        val f = File("/proc/sys/kernel/unprivileged_userns_clone")
        if (!f.isFile) return true
        return runCatching { f.readText().trim() != "0" }.getOrDefault(true)
    }

    /**
     * 清理上次异常退出残留的 CEF 子进程：它们持有缓存目录/配置锁，
     * 不清理会导致下一次 CefInitialize 间歇性失败（"内嵌浏览器经常初始化失败"的根因）。
     * 只杀可执行文件位于本应用 jcef-bundle 目录下的进程，不误伤系统其他 CEF 应用。
     */
    private fun killStaleJcefHelpers() {
        runCatching {
            // 路径分隔符按平台归一化：Windows 的 ProcessHandle 命令串用 '\'，Linux 用 '/'
            val sep = if (Platform.isWindows) '\\' else '/'
            val bundleDir = File(AppContext.cacheDir, "jcef-bundle")
                .canonicalPath.lowercase().replace('/', sep)
            ProcessHandle.allProcesses().forEach { ph ->
                runCatching {
                    val cmd = ph.info().command().orElse("").lowercase().replace('/', sep)
                    if (cmd.startsWith(bundleDir)) {
                        Log.w(TAG, "kill stale jcef helper pid=${ph.pid()}: $cmd")
                        ph.destroyForcibly()
                    }
                }
            }
            // 给被杀进程一点时间释放文件锁
            Thread.sleep(200)
        }
    }

    /** 进程退出前释放（尽力而为；进程即将结束，失败无碍） */
    fun disposeQuietly() {
        runCatching { cefApp?.dispose() }.onFailure {
            Log.w(TAG, "CefApp dispose failed: ${it.message}")
        }
        cefApp = null
    }
}
