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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
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
    private var connection: MangoConnection? = null
    private fun keyPair() = AdbCrypto.loadOrGenerate(File(context.filesDir, "adb"))
    fun deployServerDex(): File {
        val out = File(context.getExternalFilesDir(null), "mango-server.dex")
        if (!out.exists() || out.length() == 0L) {
            context.assets.open("mango-server.dex").use { ins -> out.outputStream().use { ins.copyTo(it) } }
        }
        return out
    }
    /**
     * 全自动流程：配对 -> 扫描端口 -> 启动服务
     */
    suspend fun autoStart(pairAddr: String, code: String) = withContext(Dispatchers.IO) {
        state.value = MangoState.PAIRING
        val idx = pairAddr.lastIndexOf(':')
        if (idx <= 0) { state.value = MangoState.FAILED; return@withContext }
        val host = pairAddr.substring(0, idx).ifBlank { "127.0.0.1" }
        val port = pairAddr.substring(idx + 1).toIntOrNull()
        if (port == null) { state.value = MangoState.FAILED; return@withContext }
        // 1. 尝试配对
        val pairResult = AdbPairing.pair(host, port, code, keyPair())
        if (pairResult.isFailure) { state.value = MangoState.FAILED; return@withContext }
        // 2. 扫描服务端口
        state.value = MangoState.SCANNING
        val servicePort = findServicePort()
        if (servicePort == null) { state.value = MangoState.FAILED; return@withContext }
        // 3. 启动服务
        state.value = MangoState.STARTING
        try {
            deployServerDex()
            AdbClient("127.0.0.1", servicePort, keyPair()).use { adb ->
                adb.connect()
                adb.shell(START_CMD)
            }
            var up = false
            repeat(20) { if (!up) { delay(300); up = ping() } }
            if (!up) throw IllegalStateException("服务唤醒失败")
            state.value = MangoState.RUNNING
        } catch (t: Throwable) {
            state.value = MangoState.FAILED
        }
    }
    /**
     * 读取 /proc/net/tcp 和 tcp6，寻找处于 LISTEN 状态的本地端口，并并发尝试 ADB 握手
     */
    private suspend fun findServicePort(): Int? = withContext(Dispatchers.IO) {
        val ports = mutableSetOf<Int>()
        listOf("/proc/net/tcp", "/proc/net/tcp6").forEach { path ->
            runCatching {
                File(path).useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size > 3 && parts[3] == "0A") { // 0A = LISTEN 状态
                            val localAddr = parts[1]
                            val hexPort = localAddr.substringAfter(":")
                            val port = hexPort.toInt(16)
                            if (port in 30000..50000) { // 无线调试端口通常在这个范围
                                ports.add(port)
                            }
                        }
                    }
                }
            }
        }
        // 并发尝试连接这些端口，看谁回应 ADB 的 CNXN
        coroutineScope {
            val jobs = ports.map { port ->
                async {
                    runCatching {
                        val client = AdbClient("127.0.0.1", port, keyPair())
                        client.connect()
                        client.close()
                        port
                    }.getOrNull()
                }
            }
            jobs.awaitAll().firstOrNull { it != null }
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
    suspend fun installApk(uri: android.net.Uri): String = withContext(Dispatchers.IO) {
        val tmp = File(context.getExternalFilesDir(null), "mango_install.apk")
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.copyTo(tmp.outputStream()) }
            exec("cp ${tmp.absolutePath} /data/local/tmp/mango_install.apk && pm install -r -g /data/local/tmp/mango_install.apk && rm -f /data/local/tmp/mango_install.apk", 180_000)
        }.getOrElse { "❌ ${it.message}" }
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
