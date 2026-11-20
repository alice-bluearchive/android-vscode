package com.vscodex

import android.content.Context
import java.io.File

object TermuxEnv {
    fun filesDir(ctx: Context) = ctx.filesDir
    // 根目录就是 filesDir
    fun prefix(ctx: Context) = ctx.filesDir.absolutePath
    // 我们在 libproot.so 内部的 HOME 路径（/home）
    fun home(ctx: Context) = "/home"
    fun tmp(ctx: Context) = "/tmp"

    fun buildEnv(ctx: Context): Array<String> {
        val p = prefix(ctx)
        // 确保 App 真实私有目录下的 home 和 tmp 存在
        File(p, "home").mkdirs()
        File(p, "tmp").mkdirs()

        return arrayOf(
            "PREFIX=$p",
            "HOME=${home(ctx)}",
            "TMPDIR=${tmp(ctx)}",
            "PATH=/bin:/usr/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=/lib:/usr/lib",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8"
        )
    }
}
