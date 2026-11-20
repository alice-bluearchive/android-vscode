package com.vscodex
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object Orchestrator {
    var codeServerProcess: Process? = null
    suspend fun startCodeServer(ctx: Context): String? = withContext(Dispatchers.IO) {
        val filesDir = ctx.filesDir
        val codeServerBin = filesDir.walkTopDown().filter { it.isFile && it.name == "code-server" }.firstOrNull()
            ?: return@withContext "找不到 code-server"
        val csRootDir = codeServerBin.parentFile?.parentFile?.absolutePath ?: filesDir.absolutePath
        val entryJs = File(csRootDir, "out/node/entry.js")
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
        val rootfsPath = filesDir.absolutePath
        val homeDir = File(rootfsPath, "home").apply { mkdirs() }

        val userDir = File(homeDir, ".local/share/code-server/User").apply { mkdirs() }
        val settingsFile = File(userDir, "settings.json")
        settingsFile.writeText("""
        {
            "workbench.colorTheme": "Default Dark+",
            "window.zoomLevel": -0.5, 
            "workbench.activityBar.location": "default",
            "workbench.sideBar.width": 220,
            "workbench.editor.showTabs": true,
            "editor.fontSize": 14,
            "editor.wordWrap": "on",
            "editor.minimap.enabled": false
        }
        """.trimIndent())

        val nodePath = "$nativeLibDir/libnode.so"
        val bashPath = "$nativeLibDir/libbash.so"
        val confEnv = if (File(rootfsPath, "etc/tls/openssl.cnf").exists()) "$rootfsPath/etc/tls/openssl.cnf" else "/dev/null"

        val cmd = "export HOME=$homeDir; export OPENSSL_CONF=$confEnv; export PATH=$csRootDir/bin:$rootfsPath/bin:/system/bin; $nodePath $entryJs --auth none --bind-addr 0.0.0.0:8080 --disable-workspace-trust --disable-telemetry"
        
        val pb = ProcessBuilder(bashPath, "-c", cmd)
        pb.environment().clear()
        pb.environment()["HOME"] = homeDir.absolutePath
        pb.environment()["OPENSSL_CONF"] = confEnv
        pb.environment()["PATH"] = "$csRootDir/bin:$rootfsPath/bin:/system/bin"
        pb.environment()["LD_LIBRARY_PATH"] = "$rootfsPath/lib:$nativeLibDir"
        pb.directory(homeDir)
        pb.redirectErrorStream(true)

        try {
            codeServerProcess?.destroy()
            val proc = pb.start()
            val exited = proc.waitFor(5, TimeUnit.SECONDS)
            if (exited) {
                val errorMsg = proc.inputStream.bufferedReader().readText()
                return@withContext "启动退出:\n$errorMsg"
            }
            codeServerProcess = proc
            return@withContext null
        } catch (e: Exception) {
            return@withContext "异常: ${e.message}"
        }
    }
}
