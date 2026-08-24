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
enum class MangoState(val label: String, val emoji: String) {
    OFFLINE("小芒果在睡觉", "💤"),
    PAIRED("已配对，随时待命", "🤝"),
    STARTING("正在唤醒服务…", "⏳"),
    RUNNING("提权服务运行中", "🥭")
}
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
        const val USB_CMD =
            "adb shell sh -c 'cp $DEX_SD $DEX_TMP && nohup env CLASSPATH=$DEX_TMP " +
            "app_process /system/bin --nice-name=mango_server $SERVER_MAIN >/dev/null 2>&1 &'"
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
    suspend fun pair(host: String, port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        AdbPairing.pair(host, port, code, keyPair()).also {
            if (it.isSuccess) state.value = MangoState.PAIRED
        }
    }
    suspend fun startViaWireless(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        state.value = MangoState.STARTING
        try {
            deployServerDex()
            AdbClient("127.0.0.1", port, keyPair()).use { adb ->
                adb.connect()
                adb.shell(START_CMD)
            }
            var up = false
            repeat(20) { if (!up) { delay(300); up = ping() } }
            if (!up) throw IllegalStateException("命令已发出但服务没响应，确认无线调试还开着")
            state.value = MangoState.RUNNING
            Result.success(Unit)
        } catch (t: Throwable) {
            state.value = MangoState.OFFLINE
            Result.failure(t)
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
            val r = c2.request(json)
            state.value = MangoState.RUNNING
            r
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
