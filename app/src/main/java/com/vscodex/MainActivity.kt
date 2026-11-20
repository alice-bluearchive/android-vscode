package com.vscodex

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

private enum class Phase { STARTING, RUNNING, ERROR }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var webViewRef: WebView? = null

        setContent {
            MaterialTheme {
                var phase by remember { mutableStateOf(Phase.STARTING) }
                var message by remember { mutableStateOf("正在准备环境...") }
                var lanIp by remember { mutableStateOf("") }

                // 首次进入：解压环境 + 启动 code-server（在 app 内直接运行，无浏览器）
                // 解压可能抛异常（空间不足、资产损坏等），必须接住并进入错误页，
                // 否则协程崩掉后界面永远卡在"正在准备环境..."。
                LaunchedEffect(Unit) {
                    val installError = withContext(Dispatchers.IO) {
                        try {
                            if (!BootstrapInstaller.isInstalled(this@MainActivity)) {
                                message = "首次解压基础环境和 code-server，请稍候..."
                                BootstrapInstaller.installIfNeeded(this@MainActivity)
                            }
                            null
                        } catch (e: Exception) {
                            BootstrapInstaller.lastError ?: e.message ?: "解压失败"
                        }
                    }
                    if (installError != null) {
                        message = "环境解压失败：$installError"
                        phase = Phase.ERROR
                        return@LaunchedEffect
                    }
                    message = "正在启动 code-server..."
                    val error = Orchestrator.startCodeServer(this@MainActivity)
                    if (error == null) {
                        lanIp = getLocalIpAddress()
                        phase = Phase.RUNNING
                    } else {
                        message = error ?: "未知错误"
                        phase = Phase.ERROR
                    }
                }

                when (phase) {
                    Phase.STARTING, Phase.ERROR -> StatusScreen(message = message, isError = phase == Phase.ERROR) {
                        phase = Phase.STARTING
                        message = "正在重新启动..."
                        lifecycleScope.launch {
                            val error = Orchestrator.startCodeServer(this@MainActivity)
                            if (error == null) {
                                lanIp = getLocalIpAddress()
                                phase = Phase.RUNNING
                            } else {
                                message = error
                                phase = Phase.ERROR
                            }
                        }
                    }

                    Phase.RUNNING -> {
                        val ctx = LocalContext.current
                        var showAddress by remember { mutableStateOf(true) }

                        BackHandler {
                            if (webViewRef?.canGoBack() == true) webViewRef?.goBack()
                            else finish()
                        }

                        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
                            if (showAddress) {
                                LanAddressBar(ip = lanIp, onClose = { showAddress = false }, onCopy = {
                                    copyToClipboard(ctx, "http://$lanIp:8080")
                                    Toast.makeText(ctx, "局域网地址已复制", Toast.LENGTH_SHORT).show()
                                })
                            }

                            CodeServerWebView(
                                modifier = Modifier.fillMaxSize().weight(1f),
                                onWebView = { webViewRef = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(message: String, isError: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("VS Code", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            if (isError) {
                Text(message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("重试") }
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LanAddressBar(ip: String, onClose: () -> Unit, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF252526))
            .clickable(onClick = onCopy)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "局域网: http://$ip:8080",
            color = Color(0xFF9CDCFE),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("复制", color = Color(0xFF569CD6), fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
        Text("✕", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClose))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CodeServerWebView(modifier: Modifier = Modifier, onWebView: (WebView) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                // 用桌面 UA，让 VSCode 走完整的桌面布局（含原生侧边栏）
                settings.userAgentString =
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // 页面加载完后注入脚本：加宽 sash + 允许触摸拖动侧边栏
                        view?.evaluateJavascript(WebViewScripts.SIDEBAR_TOUCH, null)
                    }

                    // 所有导航留在 WebView 内，不跳系统浏览器
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
                }
                webChromeClient = WebChromeClient()
                loadUrl("http://127.0.0.1:8080")
                onWebView(this)
            }
        }
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("lan-address", text))
}

/** 获取手机的局域网 IP（优先 Wi-Fi，排除回环和链路本地地址）。 */
private fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (ni.isLoopback || !ni.isUp) continue
            val addresses = ni.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                val host = addr.hostAddress
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address &&
                    host != null && !host.startsWith("169.254")
                ) {
                    return host
                }
            }
        }
    } catch (_: Exception) {
    }
    return "127.0.0.1"
}
