package com.vscodex

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object BootstrapInstaller {
    private const val ASSET_NAME = "bootstrap-aarch64.zip"
    private const val CS_ASSET = "code-server.tar.gz"
    private const val MARKER = ".bootstrap_done"

    // code-server 包内的关键文件，用于判断环境是否真的解压完整
    private const val CS_BIN = "code-server-4.91.1-linux-arm64/bin/code-server"
    private const val CS_ENTRY = "code-server-4.91.1-linux-arm64/out/node/entry.js"
    // 官方 tar 包内自带的 glibc node：Android 上跑不了，解压时直接跳过，省 95MB 空间
    private const val CS_GLIBC_NODE = "code-server-4.91.1-linux-arm64/lib/node"

    /** 最近一次解压失败的原始错误，成功时为 null。供启动器显示给用户。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 是否安装完整。不只看标记，还校验关键文件真实存在——
     * 这样即使旧版本留下了"假完成"的标记，也会被判定为未安装并重新解压。
     * 注意：不校验包内 glibc node（我们跳过不解压它）。
     */
    fun isInstalled(ctx: Context): Boolean {
        val marker = File(ctx.filesDir, MARKER)
        if (!marker.exists()) return false
        val bin = File(ctx.filesDir, CS_BIN)
        val entry = File(ctx.filesDir, CS_ENTRY)
        val node = File(ctx.filesDir, "lib/libnode.so")
        return bin.isFile && bin.length() > 0 &&
                entry.isFile && entry.length() > 0 &&
                node.isFile && node.length() > 0
    }

    /** 强制重新安装（忽略完成标记），用于环境损坏时自愈。 */
    fun reinstall(ctx: Context) {
        File(ctx.filesDir, MARKER).delete()
        installIfNeeded(ctx)
    }

    fun installIfNeeded(ctx: Context) {
        if (isInstalled(ctx)) return
        lastError = null
        val filesDir = ctx.filesDir
        // 重装时保留 home/：用户的项目代码可能存在里面，绝不能跟着环境一起清掉
        filesDir.listFiles()?.forEach { if (it.name != "home") it.deleteRecursively() }
        val symlinks = mutableListOf<Pair<String, String>>()

        try {
            // 0. 把 assets 里的 lib*.so（Termux 为 Android 编译的 node + 其依赖链）
            //    拷到私有目录 lib/ 下。必须拷出来再执行：nativeLibraryDir 里的文件
            //    受 SELinux 限制不可 exec，Termux 系二进制都放这里跑。
            val libDir = File(filesDir, "lib").apply { mkdirs() }
            val assetsList = ctx.assets.list("") ?: emptyArray()
            for (fileName in assetsList) {
                if (fileName.startsWith("lib") && fileName.contains(".so")) {
                    ctx.assets.open(fileName).use { input ->
                        File(libDir, fileName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    File(libDir, fileName).setExecutable(true, false)
                }
            }

            // 1. 解压 bootstrap（普通 zip，无链接）
            ctx.assets.open(ASSET_NAME).use { input ->
                ZipInputStream(input.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == "SYMLINKS.txt") {
                            val out = ByteArrayOutputStream()
                            zis.copyTo(out)
                            out.toString(Charsets.UTF_8.name()).lines().forEach { line ->
                                if (line.isNotBlank()) {
                                    val parts = line.split("←")
                                    if (parts.size == 2) symlinks.add(Pair(parts[0], parts[1]))
                                }
                            }
                        } else if (!name.endsWith("/")) {
                            val out = File(filesDir, name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                            if (name.startsWith("bin/") || name.startsWith("lib/")) {
                                out.setExecutable(true, false)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            // 2. 创建 bootstrap 软链接（ln -sf 在部分设备被限制，改用 Os.symlink 并先删旧链接）
            symlinks.forEach { (target, linkName) ->
                val linkFile = File(filesDir, linkName)
                linkFile.parentFile?.mkdirs()
                try {
                    if (linkFile.exists() || isBrokenSymlink(linkFile)) linkFile.delete()
                    Os.symlink(target, linkFile.absolutePath)
                } catch (_: Exception) {
                    // 个别链接失败不影响主体，忽略
                }
            }

            // 3. 解压 code-server.tar.gz（含符号链接/硬链接，必须分类处理；跳过 glibc node）
            val pendingHardLinks = mutableListOf<Pair<File, String>>()
            ctx.assets.open(CS_ASSET).use { input ->
                GZIPInputStream(BufferedInputStream(input)).use { gis ->
                    TarArchiveInputStream(gis).use { tis ->
                        var entry = tis.nextEntry
                        while (entry != null) {
                            val outFile = File(filesDir, entry.name)
                            when {
                                // glibc node：Android 无法运行，跳过（Termux node 已拷到 lib/）
                                entry.name == CS_GLIBC_NODE -> { /* skip */ }

                                entry.isDirectory -> outFile.mkdirs()

                                entry.isSymbolicLink -> {
                                    // 符号链接：写成真链接，否则 node_modules.asar 等会变成空文件导致启动失败
                                    outFile.parentFile?.mkdirs()
                                    try {
                                        if (outFile.exists() || isBrokenSymlink(outFile)) outFile.delete()
                                        Os.symlink(entry.linkName, outFile.absolutePath)
                                    } catch (_: Exception) {
                                        // 链接目标不存在时退化为写空文件，不影响关键路径
                                        outFile.parentFile?.mkdirs()
                                        outFile.writeBytes(ByteArray(0))
                                    }
                                }

                                entry.isLink -> {
                                    // 硬链接：目标可能稍后才解压出来，先记录，最后统一处理
                                    pendingHardLinks.add(Pair(outFile, entry.linkName))
                                }

                                else -> {
                                    outFile.parentFile?.mkdirs()
                                    outFile.outputStream().use { tis.copyTo(it) }
                                    // tar 里的脚本/node 都需要执行权限；Android 解压后默认没有，全部打开
                                    outFile.setExecutable(true, false)
                                }
                            }
                            entry = tis.nextEntry
                        }
                    }
                }
            }

            // 4. 补做硬链接（目标此时已存在）
            pendingHardLinks.forEach { (linkFile, linkTargetName) ->
                val target = if (linkTargetName.startsWith("/")) File(linkTargetName)
                else File(filesDir, linkTargetName)
                linkFile.parentFile?.mkdirs()
                try {
                    if (linkFile.exists()) linkFile.delete()
                    if (target.exists()) Os.link(target.absolutePath, linkFile.absolutePath)
                    else linkFile.writeBytes(ByteArray(0))
                } catch (_: Exception) {
                    // 硬链接失败不影响运行（都是编译产物的 obj.target 副本），忽略
                }
            }

            // 5. 关键文件校验：任何一个缺失/为空都视为失败，不写完成标记
            val bin = File(filesDir, CS_BIN)
            val entryJs = File(filesDir, CS_ENTRY)
            val node = File(libDir, "libnode.so")
            if (!bin.isFile || bin.length() == 0L ||
                !entryJs.isFile || entryJs.length() == 0L ||
                !node.isFile || node.length() == 0L
            ) {
                throw IOException(
                    "解压完成但关键文件缺失: code-server=${bin.length()}B, " +
                        "entry.js=${entryJs.length()}B, termux-node=${node.length()}B"
                )
            }

            File(filesDir, MARKER).createNewFile()
            lastError = null
        } catch (e: Exception) {
            // 失败时清掉标记，保证下次启动会重新解压，而不是卡在"假完成"状态
            File(filesDir, MARKER).delete()
            lastError = e.message ?: e.toString()
            throw e
        }
    }

    /** 判断路径是否为一个已断开的符号链接（exists() 对断链返回 false）。 */
    private fun isBrokenSymlink(file: File): Boolean =
        try {
            Os.lstat(file.absolutePath)
            true
        } catch (_: Exception) {
            false
        }
}
