package com.yunx.app.ui.jcef

import com.yunx.app.util.Log
import com.yunx.app.util.Platform
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * JCEF 原生库的「首次使用自解包」。
 *
 * 发布包不再随带 `jcef-natives-*.jar`（其中的 tar.gz 有 120MB），而是带一份
 * `app\jcef-natives.tar.xz`（同一批文件，LZMA2 压到约 90MB）。本类负责：
 * 1. 把压缩包解到 jcefmaven 的 installDir（`<缓存>/jcef-bundle`）；
 * 2. 写 `install.lock` 与就绪标记（记录压缩包大小/时间，升级包换了自动重解）；
 * 3. 之后调用方用 `CefAppBuilder.setSkipInstallation(true)` 让 jcefmaven 跳过它自己的
 *    下载/解包步骤 —— 它不会再去找 classpath 里的原生包。
 *
 * 开发运行（`gradle run`）时原生 jar 仍在 classpath 上、也没有这个压缩包，
 * [ensureInstalled] 返回 false，行为与改动前完全一致（由 jcefmaven 自己装）。
 */
object JcefNativesInstaller {

    private const val TAG = "YunX-Jcef"

    /** 随包分发的压缩包文件名（与 portable-package.ps1 产出一致） */
    private const val ARCHIVE_NAME = "jcef-natives.tar.xz"

    /** 就绪标记：内容为解包时的「压缩包大小-修改时间」，压缩包变了就重解 */
    private const val READY_MARKER = ".yunx-natives-ready"

    private const val BUFFER_SIZE = 1 shl 16

    /**
     * 确保 installDir 里有可用的 CEF 原生库。
     * @return true = 原生库已就绪（调用方应 setSkipInstallation(true)）；
     *         false = 发布包没带压缩包（开发运行）或解包失败，交给 jcefmaven 走原流程。
     */
    fun ensureInstalled(installDir: File): Boolean {
        val archive = findArchive() ?: return false
        val stamp = "${archive.length()}-${archive.lastModified()}"
        if (isReady(installDir, stamp)) return true
        return try {
            extract(archive, installDir)
            File(installDir, "install.lock").createNewFile()
            File(installDir, READY_MARKER).writeText(stamp)
            Log.i(TAG, "CEF natives extracted: ${installDir.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "CEF natives extract failed (${archive.absolutePath})", t)
            false
        }
    }

    private fun isReady(installDir: File, stamp: String): Boolean {
        if (!File(installDir, "install.lock").isFile) return false
        if (!File(installDir, "build_meta.json").isFile) return false
        val marker = File(installDir, READY_MARKER)
        return marker.isFile && marker.readText().trim() == stamp
    }

    /**
     * 找到随包分发的压缩包。发布包里它就在 `app\` 下：
     * - 启动器已把 `app\` 设为 java.library.path 首项；
     * - 兜底再用 `jpackage.app-path`（指向 `<根>\YunX-Desktop.exe`）/ 工作目录推算。
     */
    private fun findArchive(): File? {
        val candidates = LinkedHashSet<File>()
        System.getProperty("java.library.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .forEach { candidates += File(it, ARCHIVE_NAME) }
        System.getProperty("jpackage.app-path")?.let { p ->
            File(p).parentFile?.let { candidates += File(it, "app/$ARCHIVE_NAME") }
        }
        candidates += File(System.getProperty("user.dir") ?: ".", "app/$ARCHIVE_NAME")
        candidates += File(System.getProperty("user.dir") ?: ".", ARCHIVE_NAME)
        return candidates.firstOrNull { it.isFile }
    }

    /** 解包（tar + xz）；条目名以 `./` 开头，落盘前统一规范化并拒绝越界路径 */
    private fun extract(archive: File, installDir: File) {
        if (installDir.exists() && !installDir.deleteRecursively()) {
            error("无法清理旧的 CEF 目录: ${installDir.absolutePath}")
        }
        if (!installDir.mkdirs() && !installDir.isDirectory) {
            error("无法创建 CEF 目录: ${installDir.absolutePath}")
        }
        val root = installDir.canonicalFile
        XZInputStream(BufferedInputStream(FileInputStream(archive), BUFFER_SIZE)).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                var written = 0L
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    val name = entry.name.replace('\\', '/').removePrefix("./")
                    if (name.isBlank() || name == ".") continue
                    val target = File(root, name).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator) && target != root) {
                        Log.w(TAG, "跳过越界条目: $name")
                        continue
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        val copied = tar.copyTo(out, BUFFER_SIZE)
                        written += copied
                    }
                    // ★ 必须还原权限位：Linux 的 CEF 包里有 chrome_crashpad_handler、
                    //   jcef_helper 等需要可执行位的子进程，tar 解出来不带权限就是 0644，
                    //   随后 CefInitialize 会以「无法启动子进程」失败（报错不直观，很难查）。
                    //   Windows 没有权限位概念，跳过。
                    applyTarMode(target, entry.mode)
                }
                Log.i(TAG, "CEF natives unpacked: ${written / 1024 / 1024} MB")
            }
        }
    }

    /**
     * 按 tar 头部记录的权限位设置文件权限（仅 POSIX 平台）。
     *
     * 只还原读/写/执行位，忽略 setuid/setgid/sticky —— 从压缩包里解出来的文件
     * 不该带特权位（CEF 的 chrome-sandbox 就是 4755，这里刻意降级为普通可执行文件，
     * 沙箱改走 user namespace，见 JcefHolder.sandboxArgs）。
     */
    private fun applyTarMode(target: File, mode: Int) {
        if (Platform.isWindows || mode == 0) return
        val perms = HashSet<PosixFilePermission>(9)
        val bits = listOf(
            0b100000000 to PosixFilePermission.OWNER_READ,
            0b010000000 to PosixFilePermission.OWNER_WRITE,
            0b001000000 to PosixFilePermission.OWNER_EXECUTE,
            0b000100000 to PosixFilePermission.GROUP_READ,
            0b000010000 to PosixFilePermission.GROUP_WRITE,
            0b000001000 to PosixFilePermission.GROUP_EXECUTE,
            0b000000100 to PosixFilePermission.OTHERS_READ,
            0b000000010 to PosixFilePermission.OTHERS_WRITE,
            0b000000001 to PosixFilePermission.OTHERS_EXECUTE
        )
        for ((bit, perm) in bits) {
            if (mode and bit != 0) perms += perm
        }
        runCatching { Files.setPosixFilePermissions(target.toPath(), perms) }
            .onFailure { Log.w(TAG, "设置权限失败 ${target.name}: ${it.message}") }
    }
}
