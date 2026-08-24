package com.mango.adbtool.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import javax.net.ssl.SSLSocket

/**
 * 极简 ADB 客户端：仅实现本应用需要的 connect + shell。
 *
 * 连接流程（与 AOSP adbd TLS 传输一致）：
 * 1. 明文 TCP 连接，发送 CNXN；
 * 2. 若服务端回 STLS（Android 11+ 无线调试端口），回复 STLS 并把连接升级为 TLS 1.3
 *    （客户端必须出示证书，否则 adbd 以 FAIL_IF_NO_PEER_CERT 拒绝握手）；
 * 3. TLS 通道内走 AUTH：签名挑战（已配对的密钥直接通过），必要时上报公钥；
 * 4. 收到 CNXN 即握手完成。
 *
 * 注：ADB 的 data_check 是负载字节和（int32），不是 CRC32。
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val keyPair: KeyPair,
    private val connectTimeoutMs: Int = 8000,
    private val handshakeTimeoutMs: Int = 8000
) : AutoCloseable {

    private class Msg(val cmd: String, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var nextId = 1

    fun connect() {
        var current = Socket()
        try {
            current.connect(InetSocketAddress(host, port), connectTimeoutMs)
            current.tcpNoDelay = true
            current.soTimeout = handshakeTimeoutMs
            var input = DataInputStream(current.inputStream)
            var output = DataOutputStream(current.outputStream.buffered())

            send(output, "CNXN", 0x01000001, 1 shl 20, "host::features=cmd,shell_v2".toByteArray())

            while (true) {
                val msg = receive(input)
                when (msg.cmd) {
                    "STLS" -> {
                        // 回 STLS(版本 0x01000000)，随后在同一条 TCP 连接上做 TLS 1.3 握手
                        send(output, "STLS", 0x01000000, 0, ByteArray(0))
                        val ssl = AdbTls.sslContext(keyPair).socketFactory
                            .createSocket(current, host, port, true) as SSLSocket
                        ssl.enabledProtocols = arrayOf("TLSv1.3")
                        ssl.startHandshake()
                        current = ssl
                        input = DataInputStream(ssl.inputStream)
                        output = DataOutputStream(ssl.outputStream.buffered())
                    }
                    "AUTH" -> {
                        if (msg.arg0 == 1) {
                            send(output, "AUTH", 2, 0, AdbCrypto.sign(keyPair, msg.payload))
                            val reply = receive(input)
                            if (reply.cmd == "AUTH" && reply.arg0 == 1) {
                                // 密钥未被授权：上报公钥（配对过的密钥不会走到这里）
                                send(output, "AUTH", 3, 0, AdbCrypto.adbPublicKey(keyPair) + byteArrayOf(0))
                            } else if (reply.cmd == "CNXN") {
                                socket = current; this.input = input; this.output = output
                                onConnected(current); return
                            }
                        } else error("不支持的 AUTH 类型: ${msg.arg0}")
                    }
                    "CNXN" -> {
                        socket = current; this.input = input; this.output = output
                        onConnected(current); return
                    }
                    else -> error("ADB 握手失败: ${msg.cmd}")
                }
            }
        } catch (t: Throwable) {
            runCatching { current.close() }
            socket = null; input = null; output = null
            throw t
        }
    }

    private fun onConnected(s: Socket) {
        // 握手完成，清除读超时，长命令不再受限
        s.soTimeout = 0
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
        send(out, cmd, arg0, arg1, payload)
    }

    private fun send(out: DataOutputStream, cmd: String, arg0: Int, arg1: Int, payload: ByteArray) {
        val cb = cmd.toByteArray(Charsets.US_ASCII)
        val cmdInt = (cb[0].toInt() and 0xff) or ((cb[1].toInt() and 0xff) shl 8) or
                ((cb[2].toInt() and 0xff) shl 16) or ((cb[3].toInt() and 0xff) shl 24)
        var checksum = 0
        for (b in payload) checksum += b.toInt() and 0xFF
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmdInt).putInt(arg0).putInt(arg1).putInt(payload.size).putInt(checksum)
        buf.putInt(cmdInt xor -1)
        buf.put(payload)
        out.write(buf.array()); out.flush()
    }

    private fun receive(): Msg {
        val ins = input ?: error("未连接")
        return receive(ins)
    }

    private fun receive(ins: DataInputStream): Msg {
        val header = ByteArray(24)
        ins.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmdInt = bb.int
        val cmd = String(ByteArray(4) { (cmdInt ushr (8 * it)).toByte() }, Charsets.US_ASCII)
        val arg0 = bb.int; val arg1 = bb.int; val len = bb.int
        bb.int; bb.int // checksum 与 magic 不校验
        val payload = ByteArray(len)
        if (len > 0) ins.readFully(payload)
        return Msg(cmd, arg0, arg1, payload)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
