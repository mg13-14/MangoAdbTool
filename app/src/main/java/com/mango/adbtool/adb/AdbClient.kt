package com.mango.adbtool.adb
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
class AdbClient(
    private val host: String,
    private val port: Int,
    private val keyPair: KeyPair,
    private val connectTimeoutMs: Int = 8000,
    private val handshakeTimeoutMs: Int = 8000
) : AutoCloseable {
    companion object {
        // 复用 trust-all SSLContext：端口扫描会创建大量连接
        private val SSL_CONTEXT: SSLContext by lazy {
            val tm = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            SSLContext.getInstance("TLS").apply { init(null, tm, SecureRandom()) }
        }
    }
    private class Msg(val cmd: String, val arg0: Int, val arg1: Int, val payload: ByteArray)
    private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var nextId = 1
    fun connect() {
        val s = SSL_CONTEXT.socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            // 握手期设读超时，防止连到非 ADB 端口时无限阻塞（端口扫描依赖此行为）
            s.soTimeout = handshakeTimeoutMs
            s.startHandshake()
            socket = s
            input = DataInputStream(s.inputStream)
            output = DataOutputStream(s.outputStream.buffered())
            send("CNXN", 0x01000000, 524288, "host::features=cmd,shell_v2\u0000".toByteArray())
            var msg = receive()
            if (msg.cmd == "AUTH") {
                if (msg.arg0 == 1) {
                    send("AUTH", 2, 0, AdbCrypto.sign(keyPair, msg.payload))
                    msg = receive()
                    if (msg.cmd == "AUTH" && msg.arg0 == 1) {
                        send("AUTH", 3, 0, AdbCrypto.adbPublicKey(keyPair) + byteArrayOf(0))
                        msg = receive()
                    }
                } else error("不支持的 AUTH 类型: ${msg.arg0}")
            }
            check(msg.cmd == "CNXN") { "ADB 握手失败: ${msg.cmd}" }
            // 握手完成，清除读超时，长命令不再受限制
            s.soTimeout = 0
        } catch (t: Throwable) {
            // 握手失败时关闭 socket，防止 fd 泄漏
            runCatching { s.close() }
            socket = null; input = null; output = null
            throw t
        }
    }
    fun shell(command: String, onOutput: (String) -> Unit = {}): String {
        val localId = nextId++
        send("OPEN", localId, 0, "shell:$command\u0000".toByteArray())
        val sb = StringBuilder()
        var remoteId = 0
        while (true) {
            val m = receive()
            when (m.cmd) {
                "OKAY" -> remoteId = m.arg0
                "WRTE" -> {
                    String(m.payload, Charsets.UTF_8).let { sb.append(it); onOutput(it) }
                    send("OKAY", localId, remoteId, ByteArray(0))
                }
                "CLSE" -> { send("CLSE", localId, remoteId, ByteArray(0)); return sb.toString() }
                else -> {}
            }
        }
    }
    private fun send(cmd: String, arg0: Int, arg1: Int, payload: ByteArray) {
        val out = output ?: error("未连接")
        val cb = cmd.toByteArray(Charsets.US_ASCII)
        val cmdInt = (cb[0].toInt() and 0xff) or ((cb[1].toInt() and 0xff) shl 8) or
                ((cb[2].toInt() and 0xff) shl 16) or ((cb[3].toInt() and 0xff) shl 24)
        val crc = CRC32().apply { update(payload) }.getValue().toInt()
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmdInt).putInt(arg0).putInt(arg1).putInt(payload.size).putInt(crc)
        buf.putInt(cmdInt xor -1)
        buf.put(payload)
        out.write(buf.array()); out.flush()
    }
    private fun receive(): Msg {
        val ins = input ?: error("未连接")
        val header = ByteArray(24)
        ins.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmdInt = bb.int
        val cmd = String(ByteArray(4) { (cmdInt ushr (8 * it)).toByte() }, Charsets.US_ASCII)
        val arg0 = bb.int; val arg1 = bb.int; val len = bb.int
        bb.int; bb.int
        val payload = ByteArray(len)
        if (len > 0) ins.readFully(payload)
        return Msg(cmd, arg0, arg1, payload)
    }
    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
