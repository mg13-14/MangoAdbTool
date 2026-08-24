package com.mango.adbtool.core
import com.mango.adbtool.adb.AdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object MangoDiscovery {
    private const val SCAN_FROM = 30000
    private const val SCAN_TO = 49500
    private const val CHUNK = 256          // 每块并发数，避免文件描述符耗尽
    private const val CONNECT_TIMEOUT = 100
    private const val READ_TIMEOUT = 200

    // 复用 trust-all SSLContext：全盘扫描要建近 2 万个 socket，每端口新建会拖垮性能
    private val sslContext: SSLContext by lazy {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        SSLContext.getInstance("TLS").apply { init(null, tm, SecureRandom()) }
    }

    /**
     * 雷达扫描：寻找配对端口
     * 当系统弹出配对码界面时，会临时开启一个 TLS 服务端，
     * 它会主动推送 ADB 配对协议消息（首字节 version=1）
     */
    suspend fun findPairingPort(): Int? = withContext(Dispatchers.IO) {
        repeat(6) {
            var port = SCAN_FROM
            while (port < SCAN_TO) {
                val end = minOf(port + CHUNK, SCAN_TO)
                val found = scanChunkForPairing(port, end)
                if (found != null) return@withContext found
                port = end
            }
            delay(400)
        }
        null
    }

    private suspend fun scanChunkForPairing(start: Int, end: Int): Int? = coroutineScope {
        (start until end).map { port ->
            async(Dispatchers.IO) {
                var socket: SSLSocket? = null
                try {
                    socket = sslContext.socketFactory.createSocket() as SSLSocket
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT)
                    socket.enabledProtocols = arrayOf("TLSv1.2")
                    socket.soTimeout = READ_TIMEOUT
                    socket.startHandshake()
                    // ADB 配对协议：服务端主动推送 [version=1][type][len][data]
                    val version = DataInputStream(socket.inputStream).read()
                    if (version == 1) port else null
                } catch (t: Throwable) {
                    null
                } finally {
                    runCatching { socket?.close() } // 失败端口也必须关闭，防止 fd 泄漏
                }
            }
        }.awaitAll().firstOrNull { it != null }
    }

    /**
     * 雷达扫描：寻找服务端口
     * 配对成功后，adbd 会在另一个端口监听 ADB 连接
     */
    suspend fun findServicePort(keyPair: KeyPair): Int? = withContext(Dispatchers.IO) {
        repeat(6) {
            var port = SCAN_FROM
            while (port < SCAN_TO) {
                val end = minOf(port + CHUNK, SCAN_TO)
                val found = scanChunkForService(port, end, keyPair)
                if (found != null) return@withContext found
                port = end
            }
            delay(400)
        }
        null
    }

    private suspend fun scanChunkForService(start: Int, end: Int, keyPair: KeyPair): Int? = coroutineScope {
        (start until end).map { port ->
            async(Dispatchers.IO) {
                try {
                    // 探测用短超时，非 ADB 的 TLS 端口快速失败，不拖慢全盘扫描
                    AdbClient("127.0.0.1", port, keyPair, connectTimeoutMs = 150, handshakeTimeoutMs = 1500).use { client ->
                        client.connect()
                    }
                    port
                } catch (t: Throwable) {
                    null
                }
            }
        }.awaitAll().firstOrNull { it != null }
    }
}
