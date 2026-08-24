package com.mango.adbtool.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * ADB 无线配对（Android 11+「使用配对码配对设备」）。
 *
 * 协议流程（与 AOSP adbd pairing_server 一致）：
 * 1. TCP 连接配对端口，完成 TLS 1.3 握手（客户端必须出示自签名证书）；
 * 2. 从 TLS 会话导出 64 字节密钥材料（EKM, label="adb-label\0"），拼在配对码后作 SPAKE2 口令；
 * 3. 交换 32 字节 SPAKE2 消息（客户端先发），派生会话密钥；
 * 4. 用 HKDF-SHA256 派生 AES-128-GCM 密钥，加解密 PeerInfo（我们的 RSA 公钥），
 *    客户端先发；服务端解密成功即把公钥写入授权列表。
 */
object AdbPairing {

    private const val HEADER_SIZE = 6
    private const val VERSION: Byte = 1
    private const val TYPE_SPAKE2_MSG: Byte = 0
    private const val TYPE_PEER_INFO: Byte = 1
    private const val PEER_INFO_SIZE = 8192
    private const val MAX_PAYLOAD = 2 * PEER_INFO_SIZE
    private const val PEER_INFO_DATA_SIZE = PEER_INFO_SIZE - 1
    private const val EKM_LABEL = "adb-label\u0000"
    private const val EKM_SIZE = 64
    private const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"
    private const val HKDF_KEY_SIZE = 16

    private val CLIENT_NAME = "adb pair client\u0000".toByteArray()
    private val SERVER_NAME = "adb pair server\u0000".toByteArray()

    fun pair(host: String, port: Int, code: String, keyPair: KeyPair): Result<Unit> = runCatching {
        require(code.length in 6..8 && code.all { it.isLetterOrDigit() }) { "配对码通常是 6 位数字哦 🥭" }

        val plain = java.net.Socket()
        try {
            plain.connect(InetSocketAddress(host, port), 10_000)
            plain.tcpNoDelay = true
            plain.soTimeout = 10_000

            val socket = AdbTls.sslContext(keyPair).socketFactory
                .createSocket(plain, host, port, true) as SSLSocket
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.use { ssl ->
                ssl.startHandshake()
                val input = DataInputStream(ssl.inputStream)
                val output = ssl.outputStream

                // EKM 拼接配对码作为 SPAKE2 口令，防止连接被劫持替换
                val keyMaterial = AdbTls.exportKeyingMaterial(ssl, EKM_LABEL, EKM_SIZE)
                val password = code.toByteArray() + keyMaterial

                val auth = PairingAuthCtx(password)

                // --- 交换 SPAKE2 消息（客户端先发）---
                writePacket(output, TYPE_SPAKE2_MSG, auth.msg)
                val (msgType, theirMsg) = readPacket(input)
                check(msgType == TYPE_SPAKE2_MSG) { "配对服务返回了意外消息类型: $msgType" }
                auth.initCipher(theirMsg)

                // --- 交换加密 PeerInfo（客户端先发，携带我们的 RSA 公钥）---
                val peerInfo = buildPeerInfo(keyPair)
                writePacket(output, TYPE_PEER_INFO, auth.encrypt(peerInfo))
                // 配对码错误时 adbd 解密失败会直接断连，把读错误翻译成友好提示
                val (infoType, encrypted) = try {
                    readPacket(input)
                } catch (e: IOException) {
                    throw IllegalStateException(
                        "配对码验证失败——密码不对或已过期，请重新打开配对界面换个新码", e
                    )
                }
                check(infoType == TYPE_PEER_INFO) { "配对服务返回了意外消息类型: $infoType" }
                val serverInfo = auth.decrypt(encrypted)
                    ?: throw IllegalStateException("配对码验证失败——密码不对或已过期，请重新打开配对界面换个新码")
                check(serverInfo.size == PEER_INFO_SIZE) { "PeerInfo 尺寸不对: ${serverInfo.size}" }
            }
        } finally {
            runCatching { plain.close() }
        }
    }

    private fun buildPeerInfo(keyPair: KeyPair): ByteArray {
        val buf = ByteBuffer.allocate(PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(0) // PEER_INFO_TYPE_ADB_RSA_PUB_KEY
        val data = ByteArray(PEER_INFO_DATA_SIZE)
        val pub = AdbCrypto.adbPublicKey(keyPair)
        System.arraycopy(pub, 0, data, 0, minOf(pub.size, PEER_INFO_DATA_SIZE))
        buf.put(data)
        return buf.array()
    }

    private fun readPacket(input: DataInputStream): Pair<Byte, ByteArray> {
        val header = ByteArray(HEADER_SIZE)
        input.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = bb.get()
        val type = bb.get()
        val size = bb.int
        check(version == VERSION) { "不支持的配对协议版本: $version" }
        check(type == TYPE_SPAKE2_MSG || type == TYPE_PEER_INFO) { "未知消息类型: $type" }
        check(size in 1..MAX_PAYLOAD) { "消息尺寸越界: $size" }
        val data = ByteArray(size)
        input.readFully(data)
        return type to data
    }

    private fun writePacket(output: OutputStream, type: Byte, data: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION).put(type).putInt(data.size).array()
        output.write(header)
        output.write(data)
        output.flush()
    }

    /** SPAKE2 + HKDF + AES-128-GCM 的配对加密上下文（对应 AOSP PairingAuthCtx）。 */
    private class PairingAuthCtx(password: ByteArray) {
        private val spake2 = Spake2.createAlice(CLIENT_NAME, SERVER_NAME)
        val msg: ByteArray = spake2.generateMessage(password)
        private var secretKey: ByteArray? = null
        private var encCounter = 0L
        private var decCounter = 0L

        fun initCipher(theirMsg: ByteArray) {
            val keyMaterial = spake2.processMessage(theirMsg)
            // HKDF-SHA256(ikm=密钥材料, salt=空, info=AOSP 约定串, len=16)
            val salt = ByteArray(32)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(keyMaterial)
            val info = HKDF_INFO.toByteArray()
            var previous = ByteArray(0)
            val out = ByteArray(HKDF_KEY_SIZE)
            var offset = 0
            var counter = 1
            while (offset < HKDF_KEY_SIZE) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val n = minOf(previous.size, HKDF_KEY_SIZE - offset)
                System.arraycopy(previous, 0, out, offset, n)
                offset += n
                counter++
            }
            secretKey = out
        }

        fun encrypt(plain: ByteArray): ByteArray {
            val key = secretKey ?: throw IOException("加密上下文尚未初始化")
            val nonce = counterNonce(encCounter++)
            return Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
                .doFinal(plain)
        }

        fun decrypt(encrypted: ByteArray): ByteArray? {
            val key = secretKey ?: throw IOException("加密上下文尚未初始化")
            val nonce = counterNonce(decCounter++)
            return try {
                Cipher.getInstance("AES/GCM/NoPadding")
                    .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
                    .doFinal(encrypted)
            } catch (e: Exception) {
                null
            }
        }

        /** GCM nonce = 小端 64 位计数器 + 4 字节零（收发计数器独立，与 AOSP 一致）。 */
        private fun counterNonce(counter: Long): ByteArray =
            ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
    }
}
