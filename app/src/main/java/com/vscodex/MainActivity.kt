package com.vscodex

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var log by remember { mutableStateOf("准备就绪") }
                var busy by remember { mutableStateOf(false) }
                var isRunning by remember { mutableStateOf(false) }
                var localIp by remember { mutableStateOf("获取中...") }

                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!isRunning) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                                        log = "请授予全盘文件权限！"
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                            intent.addCategory("android.intent.category.DEFAULT")
                                            intent.data = Uri.parse("package:$packageName")
                                            startActivity(intent)
                                        } catch (e: Exception) {
                                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                        }
                                        return@Button
                                    }

                                    busy = true
                                    log = "正在启动环境..."
                                    lifecycleScope.launch {
                                        try {
                                            if (!BootstrapInstaller.isInstalled(this@MainActivity)) {
                                                log = "首次解压环境..."
                                                withContext(Dispatchers.IO) { BootstrapInstaller.installIfNeeded(this@MainActivity) }
                                            }
                                            
                                            val errorMsg = Orchestrator.startCodeServer(this@MainActivity)
                                            if (errorMsg == null) {
                                                log = "启动成功！"
                                                localIp = getLocalIpAddress()
                                                isRunning = true
                                            } else {
                                                log = "启动失败:\n$errorMsg"
                                            }
                                        } catch (e: Exception) {
                                            log = "异常: ${e.message}"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                modifier = Modifier.width(220.dp).height(60.dp)
                            ) { Text(if (busy) "启动中..." else "启动 VS Code 服务器") }
                        } else {
                            Text("局域网访问地址:", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Text("http://$localIp:8080", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            
                            Spacer(Modifier.height(24.dp))
                            
                            Button(
                                onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8080"))) },
                                modifier = Modifier.width(220.dp).height(60.dp)
                            ) { Text("本机浏览器打开", style = MaterialTheme.typography.titleMedium) }
                            
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = {
                                    Orchestrator.codeServerProcess?.destroy()
                                    isRunning = false
                                    log = "已停止服务"
                                },
                                modifier = Modifier.width(220.dp).height(50.dp)
                            ) { Text("停止后台服务") }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(log)
                    }
                }
            }
        }
    }
}

fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    return address.hostAddress ?: "Unknown"
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "127.0.0.1"
}
