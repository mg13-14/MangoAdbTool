package com.mango.adbtool.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Base64
import com.mango.adbtool.adb.AdbClient
import com.mango.adbtool.adb.AdbCrypto
import com.mango.adbtool.adb.AdbPairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.security.KeyPair
import java.util.concurrent.TimeUnit

class MangoManager(private val context: Context) {
    companion object {
        const val PKG = "com.mango.adbtool"
        private const val SERVER_MAIN = "com.mango.adbtool.server.ServerMain"
        private const val DEX_SD = "/storage/emulated/0/Android/data/$PKG/files/mango-server.dex"
        private const val DEX_TMP = "/data/local/tmp/mango-server.dex"
        private val START_CMD =
            "cp $DEX_SD $DEX_TMP && chmod 644 $DEX_TMP && " +
            "nohup env CLASSPATH=$DEX_TMP app_process /system/bin " +
            "--nice-name=mango_server $SERVER_MAIN >/dev/null 2>&1 &"
    }

    val state = MutableStateFlow(MangoState.OFFLINE)
    val error = MutableStateFlow<String?>(null) // 具体错误原因，UI 直接展示
    private var connection: MangoConnection? = null
    private var pairHost: String = "127.0.0.1"
    private var pairPort: Int = 0
    private var keyPair: KeyPair? = null

    private suspend fun fail(msg: String) {
        error.value = msg
        state.value = MangoState.FAILED
    }

    private fun getKeyPair(): KeyPair =
        keyPair ?: AdbCrypto.loadOrGenerate(File(context.filesDir, "adb")).also { keyPair = it }

    fun deployServerDex(): File {
        val out = File(context.getExternalFilesDir(null), "mango-server.dex")
        if (!out.exists() || out.length() == 0L) {
            context.assets.open("mango-server.dex").use { ins -> out.outputStream().use { ins.copyTo(it) } }
        }
        return out
    }

    /**
     * 通过 Root 一键拉起服务 (类似 Shizuku 的 Root 启动)
     */
    suspend fun startViaRoot() = withContext(Dispatchers.IO) {
        error.value = null
        state.value = MangoState.STARTING
        try {
            deployServerDex()
            // su -c 执行启动命令；Magisk 授权弹窗可能需要用户手动确认，给足等待时间
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", START_CMD))
            // 带超时等待，超时后强杀进程，防授权弹窗无人响应时永久挂起
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                throw IllegalStateException("Root 授权超时（20 秒无响应，请在授权弹窗中点允许）")
            }
            var up = false
            repeat(20) { if (!up) { delay(300); up = ping() } }
            if (!up) throw IllegalStateException("Root 启动失败：su 执行了但服务未响应（设备未 Root 或未授权，检查 Magisk 授权日志）")
            state.value = MangoState.RUNNING
        } catch (t: Throwable) {
            fail(t.message ?: "Root 启动失败")
        }
    }

    /**
     * 开启 NsdManager (mDNS) 监听配对服务
     */
    suspend fun startPairingDiscovery() = withContext(Dispatchers.IO) {
        error.value = null
        state.value = MangoState.SEARCHING_PAIR
        val found = MangoDiscovery.findPairingPort(context)
        if (found == null) {
            fail("60 秒内未发现配对服务，请确认已点开系统「使用配对码配对设备」弹窗")
        } else {
            pairHost = found.first
            pairPort = found.second
            state.value = MangoState.WAITING_FOR_CODE
            // 用户切去系统设置开配对界面时（App 在后台），发通知把人叫回来
            if (!MangoEvents.inForeground) {
                MangoNotifier.notifyPairingDetected(context)
            }
        }
    }

    /**
     * 输入配对码后：配对 -> 发现服务端口 -> 启动服务
     */
    suspend fun pairAndStart(code: String) = withContext(Dispatchers.IO) {
        if (pairPort == 0) { fail("尚未发现配对端口，请先点「无线配对」"); return@withContext }
        state.value = MangoState.PAIRING
        val pairResult = AdbPairing.pair(pairHost, pairPort, code, getKeyPair())
        if (pairResult.isFailure) {
            fail("配对失败：${pairResult.exceptionOrNull()?.message ?: "未知错误"}（配对码可能已过期，重新打开配对界面再试）")
            return@withContext
        }
        state.value = MangoState.SEARCHING_SERVICE
        val service = MangoDiscovery.findServicePort(context)
        if (service == null) { fail("未找到 ADB 服务端口，请确认「无线调试」总开关已打开"); return@withContext }
        val serviceHost = service.first
        val servicePort = service.second
        state.value = MangoState.STARTING
        try {
            deployServerDex()
            AdbClient(serviceHost, servicePort, getKeyPair()).use { adb ->
                adb.connect()
                adb.shell(START_CMD)
            }
            var up = false
            repeat(20) { if (!up) { delay(300); up = ping() } }
            if (!up) throw IllegalStateException("服务唤醒失败：命令已发送但 ping 无响应")
            error.value = null
            state.value = MangoState.RUNNING
            // 服务起来了，配对提醒通知可以撤掉
            MangoNotifier.cancel(context)
        } catch (t: Throwable) {
            fail(t.message ?: "启动失败")
        }
    }

    suspend fun stopService(): Boolean = withContext(Dispatchers.IO) {
        val r = runCatching { request(JSONObject().put("action", "stop")) }
        connection?.close(); connection = null
        state.value = MangoState.OFFLINE
        r.isSuccess
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching { request(JSONObject().put("action", "ping")).optInt("code") == 0 }.getOrDefault(false)
    }

    suspend fun exec(cmd: String, timeout: Long = 60_000): String = withContext(Dispatchers.IO) {
        val resp = request(JSONObject().put("action", "exec").put("cmd", cmd).put("timeout", timeout))
        if (resp.optInt("code") != 0) return@withContext "❌ ${resp.optString("msg")}"
        val data = resp.optString("data")
        val exit = resp.optInt("exit", 0)
        if (exit != 0) "$data\n↩ 退出码: $exit" else data
    }

    suspend fun screenshot(): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            exec("screencap -p /data/local/tmp/mango_shot.png", 15_000)
            val b64 = request(JSONObject().put("action", "read").put("path", "/data/local/tmp/mango_shot.png")).optString("data")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /**
     * 静默安装：先 cp 到 /data/local/tmp（规避作用域存储限制），
     * pm install 完成后 finally 中 rm -f 清理——安装失败也会删，避免临时 APK 残留
     */
    suspend fun installApk(uri: android.net.Uri): String = withContext(Dispatchers.IO) {
        try {
            val tmpFile = File(context.getExternalFilesDir(null), "mango_install.apk")
            context.contentResolver.openInputStream(uri)?.use { it.copyTo(tmpFile.outputStream()) }
                ?: return@withContext "❌ 无法读取所选 APK"
            exec("cp ${tmpFile.absolutePath} /data/local/tmp/mango_install.apk")
            exec("pm install -r -g /data/local/tmp/mango_install.apk", 120_000)
        } catch (e: Exception) {
            "❌ 安装失败: ${e.message}"
        } finally {
            // 无论成败都清理临时文件（/data/local/tmp 不会自动清）
            runCatching { exec("rm -f /data/local/tmp/mango_install.apk") }
        }
    }

    private fun request(json: JSONObject): JSONObject {
        val c = connection ?: MangoConnection().also { connection = it }
        return try {
            c.request(json)
        } catch (t: Throwable) {
            c.close(); connection = null
            val c2 = MangoConnection().also { connection = it }
            c2.request(json)
        }
    }
}

class MangoConnection {
    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private val lock = Any()
    fun request(json: JSONObject): JSONObject = synchronized(lock) {
        val s = ensure()
        s.outputStream.write((json.toString() + "\n").toByteArray())
        s.outputStream.flush()
        val line = reader?.readLine() ?: error("服务端断开连接")
        JSONObject(line)
    }
    private fun ensure(): LocalSocket {
        socket?.takeIf { it.isConnected }?.let { return it }
        val s = LocalSocket()
        s.connect(LocalSocketAddress("mango_adb_tool", LocalSocketAddress.Namespace.ABSTRACT))
        val r = s.inputStream.bufferedReader()
        s.outputStream.write((JSONObject().put("action", "hello").put("package", "com.mango.adbtool").toString() + "\n").toByteArray())
        s.outputStream.flush()
        val hello = r.readLine() ?: error("无法连接服务（服务未启动？）")
        val resp = JSONObject(hello)
        if (resp.optInt("code") != 0) { s.close(); error(resp.optString("msg", "身份校验失败")) }
        socket = s; reader = r
        return s
    }
    fun close() {
        runCatching { socket?.close() }
        socket = null; reader = null
    }
}
