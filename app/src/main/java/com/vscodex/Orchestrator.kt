package com.vscodex

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object Orchestrator {
    var codeServerProcess: Process? = null

    // 固定路径比全目录遍历快得多（解压后 3 万+ 文件，walkTopDown 每次启动都要数秒）
    private const val CS_ROOT = "code-server-4.91.1-linux-arm64"

    /**
     * 启动 code-server。成功返回 null，失败返回错误信息。
     *
     * 运行方式：用 Termux 为 Android (Bionic) 编译的 node 直接执行
     * code-server 的 JS 入口。官方 tar 包自带的 node 是 glibc 版，
     * 在 Android 上无法运行（interpreter /lib/ld-linux-aarch64.so.1 不存在），
     * 这就是之前"打不开"的根因。
     */
    suspend fun startCodeServer(ctx: Context): String? = withContext(Dispatchers.IO) {
        val filesDir = ctx.filesDir
        val libDir = File(filesDir, "lib")
        val termuxNode = File(libDir, "libnode.so")
        val csRoot = File(filesDir, CS_ROOT)
        val entryJs = File(csRoot, "out/node/entry.js")

        // 自愈：关键文件缺失时强制重新解压一次
        if (!termuxNode.isFile || termuxNode.length() == 0L ||
            !entryJs.isFile || entryJs.length() == 0L
        ) {
            try {
                BootstrapInstaller.reinstall(ctx)
            } catch (e: Exception) {
                val cause = BootstrapInstaller.lastError ?: e.message ?: "未知原因"
                val freeMb = filesDir.usableSpace / (1024 * 1024)
                return@withContext "环境解压失败：$cause\n（可用空间约 ${freeMb}MB，code-server 需要约 300MB）"
            }
        }

        if (!termuxNode.isFile || termuxNode.length() == 0L) {
            return@withContext "找不到 Termux node：${termuxNode.absolutePath}"
        }
        if (!entryJs.isFile || entryJs.length() == 0L) {
            return@withContext "找不到 code-server 入口：${entryJs.absolutePath}"
        }
        termuxNode.setExecutable(true, false)

        val rootfsPath = filesDir.absolutePath
        val homeDir = File(rootfsPath, "home").apply { mkdirs() }
        val tmpDir = File(rootfsPath, "tmp").apply { mkdirs() }
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir

        // 写入用户设置：原生侧边栏 + 不锁宽度（不写 workbench.sideBar.width 固定值）。
        // code-server 的 User 目录在不同版本可能是 .local/share 或 .config，两个都写以保险。
        val settingsJson = """
            {
                "workbench.colorTheme": "Default Dark+",
                "window.zoomLevel": -0.5,
                "workbench.activityBar.location": "default",
                "workbench.sideBar.location": "left",
                "workbench.editor.showTabs": true,
                "editor.fontSize": 14,
                "editor.wordWrap": "on",
                "editor.minimap.enabled": false
            }
        """.trimIndent()
        listOf(
            File(homeDir, ".local/share/code-server/User"),
            File(homeDir, ".config/Code/User")
        ).forEach { dir ->
            dir.mkdirs()
            File(dir, "settings.json").writeText(settingsJson)
        }

        val confEnv = if (File(rootfsPath, "etc/tls/openssl.cnf").exists())
            "$rootfsPath/etc/tls/openssl.cnf" else "/dev/null"

        // Termux node 的依赖（libcrypto.so.3 / libicu*.so 等）都在 libDir，
        // 通过 LD_LIBRARY_PATH 告诉链接器去那里找；libc/libm/libdl 用系统的。
        val pb = ProcessBuilder(
            termuxNode.absolutePath,
            entryJs.absolutePath,
            "--auth", "none",
            "--bind-addr", "0.0.0.0:8080",
            "--disable-workspace-trust",
            "--disable-telemetry"
        )
        pb.environment().apply {
            this["HOME"] = homeDir.absolutePath
            this["PATH"] = "$rootfsPath/bin:/system/bin:/system/xbin"
            this["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:$nativeLibDir"
            this["TMPDIR"] = tmpDir.absolutePath
            this["PREFIX"] = rootfsPath
            this["OPENSSL_CONF"] = confEnv
        }
        pb.directory(homeDir)
        pb.redirectErrorStream(true)

        try {
            codeServerProcess?.destroy()
            val proc = pb.start()
            codeServerProcess = proc

            // 等一下确认进程没有立刻退出。code-server 常驻，waitFor 超时返回 false 即正常。
            val earlyExit = proc.waitFor(5, TimeUnit.SECONDS)
            if (earlyExit) {
                val log = proc.inputStream.bufferedReader().readText()
                return@withContext "code-server 启动失败 (退出码 ${proc.exitValue()}):\n${log.take(2000)}"
            }

            // 健康检查：进程活着不代表服务可用。轮询本机端口，真正 ready 了再切界面，
            // 避免 WebView 加载一个还没起来的服务一直白屏。
            val ready = pollPort("http://127.0.0.1:8080", attempts = 10, intervalMs = 1000)
            if (!ready) {
                val alive = proc.waitFor(1, TimeUnit.MILLISECONDS).not()
                val log = if (!alive) proc.inputStream.bufferedReader().readText() else ""
                return@withContext if (alive)
                    "code-server 进程在跑，但 8080 端口 10 秒内无响应（可能被防火墙拦截）"
                else
                    "code-server 中途退出:\n${log.take(2000)}"
            }
            null
        } catch (e: Exception) {
            return@withContext "启动异常: ${e.message}"
        }
    }

    /** 轮询本机 HTTP 端口，任何 HTTP 响应（含 302/401/404）都算服务已起来。 */
    private fun pollPort(url: String, attempts: Int, intervalMs: Long): Boolean {
        repeat(attempts) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                conn.disconnect()
                if (code in 100..599) return true
            } catch (_: Exception) {
                // 连接拒绝/超时 = 还没起来，继续等
            }
            Thread.sleep(intervalMs)
        }
        return false
    }
}
