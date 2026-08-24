package com.mango.adbtool.adb

import org.conscrypt.Conscrypt
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * ADB 无线配对 + ADB 客户端协议测试。
 *
 * 在 JVM 上搭建 mock adbd（与 AOSP pairing_server / adbd 行为一致），
 * 用真实生产代码（AdbPairing / AdbClient / AdbTls / Spake2 / AdbCrypto）跑完整协议：
 *
 * 1. 配对成功：TLS1.3(EKM) + SPAKE2 + HKDF + AES-GCM PeerInfo 全流程；
 * 2. 配对失败：错误配对码 → 客户端解密失败并给出友好错误；
 * 3. ADB 连接：明文 CNXN → STLS → TLS 升级（adbd 要求客户端证书）→ AUTH 签名 → CNXN → shell。
 */
class AdbPairingProtocolTest {

    companion object {
        private const val EKM_LABEL = "adb-label\u0000"
        private const val EKM_SIZE = 64
        private const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"
        private const val PEER_INFO_SIZE = 8192
        private val CLIENT_NAME = "adb pair client\u0000".toByteArray()
        private val SERVER_NAME = "adb pair server\u0000".toByteArray()

        private val serverKeyPair = rsaKeyPair()
        private val clientKeyPair = rsaKeyPair()
        private val registeredProvider = Conscrypt.newProvider()

        @JvmStatic
        @BeforeClass
        fun setUp() {
            // 与 Android 平台一致：Conscrypt 提供 TLS 1.3 + EKM 导出
            Security.insertProviderAt(registeredProvider, 1)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            Security.removeProvider(registeredProvider.name)
        }

        private fun rsaKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        /** HKDF-SHA256（extract+expand），与 AOSP HKDF 行为一致。 */
        private fun hkdfSha256(ikm: ByteArray, info: ByteArray, outLen: Int): ByteArray {
            val salt = ByteArray(32) // 空盐 = HashLen 零字节，与 AOSP HKDF(nullptr,0) 等价
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            var previous = ByteArray(0)
            val out = ByteArray(outLen)
            var offset = 0
            var counter = 1
            while (offset < outLen) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val n = minOf(previous.size, outLen - offset)
                System.arraycopy(previous, 0, out, offset, n)
                offset += n
                counter++
            }
            return out
        }
    }

    // ============================ 配对协议 ============================

    @Test
    fun pairingSucceedsWithMockAdbd() {
        val code = "515109"
        val serverResult = CompletableFuture<String>()
        val port = runMockPairingServer(code, serverResult)

        val result = AdbPairing.pair("127.0.0.1", port, code, clientKeyPair)

        assertTrue("配对应成功，实际: ${result.exceptionOrNull()}", result.isSuccess)
        // 服务端视角：成功解密客户端 PeerInfo，且内容就是客户端的 ADB 公钥
        assertEquals(
            String(AdbCrypto.adbPublicKey(clientKeyPair))
                .trimEnd { it == '\u0000' || it.code == 0 }
                .substringBefore(' '), // mock 服务端只取 base64 部分（不含 name@host 注释）
            serverResult.get(10, TimeUnit.SECONDS).trimEnd { it == '\u0000' || it.code == 0 }
        )
    }

    @Test
    fun pairingFailsWithWrongCode() {
        val serverResult = CompletableFuture<String>()
        // 服务端持有正确码 515109；客户端用错误码 111111
        val port = runMockPairingServer("515109", serverResult)

        val result = AdbPairing.pair("127.0.0.1", port, "111111", clientKeyPair)

        assertTrue("配对应失败", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("应给出配对码错误提示，实际: $message", message.contains("配对码"))
    }

    @Test
    fun pairingRejectsMalformedCode() {
        val result = AdbPairing.pair("127.0.0.1", 1, "12ab!", clientKeyPair)
        assertTrue("非法配对码应直接失败", result.isFailure)
    }

    /**
     * mock 配对服务端（AOSP PairingServer 的 Bob 角色）。
     * 完整实现 TLS→EKM→SPAKE2→HKDF→AES-GCM，与 adbd 行为一致。
     */
    private fun runMockPairingServer(code: String, result: CompletableFuture<String>): Int {
        val serverSocket = AdbTls.sslContext(serverKeyPair).serverSocketFactory
            .createServerSocket(0) as SSLServerSocket
        serverSocket.needClientAuth = true // adbd: SSL_VERIFY_FAIL_IF_NO_PEER_CERT
        serverSocket.enabledProtocols = arrayOf("TLSv1.3")

        Thread({
            try {
                serverSocket.use { listener ->
                    listener.soTimeout = 15_000
                    val ssl = listener.accept() as SSLSocket
                    ssl.use { socket ->
                        val input = DataInputStream(socket.inputStream)
                        val output = DataOutputStream(socket.outputStream)

                        // 1) 读客户端 SPAKE2 消息（首读完成 TLS 握手）
                        val (spakeType, clientSpakeMsg) = readPairingPacket(input)
                        assertEquals("首包应为 SPAKE2_MSG", 0, spakeType)

                        // 2) 服务端口令 = 配对码 + EKM（与客户端构造方式一致）
                        val keyMaterial = AdbTls.exportKeyingMaterial(socket, EKM_LABEL, EKM_SIZE)
                        val password = code.toByteArray() + keyMaterial

                        val auth = ServerPairingAuth(password)
                        writePairingPacket(output, 0, auth.msg)
                        auth.initCipher(clientSpakeMsg)

                        // 3) 收客户端加密 PeerInfo 并解密
                        val (peerType, encryptedPeerInfo) = readPairingPacket(input)
                        assertEquals("第二包应为 PEER_INFO", 1, peerType)
                        val clientPeerInfo = auth.decrypt(encryptedPeerInfo)
                            ?: error("服务端解密 PeerInfo 失败（口令不一致）")
                        assertEquals("PeerInfo 尺寸", PEER_INFO_SIZE, clientPeerInfo.size)
                        assertEquals("PeerInfo 类型", 0, clientPeerInfo[0].toInt())
                        val pubKey = String(clientPeerInfo, 1, 720)
                            .trimEnd { it == '\u0000' || it.code == 0 || it == ' ' }
                            .substringBefore(' ')
                        result.complete(pubKey)

                        // 4) 回服务端 PeerInfo（设备 GUID）
                        val guid = ByteArray(PEER_INFO_SIZE)
                        guid[0] = 1 // PEER_INFO_TYPE_ADB_DEVICE_GUID
                        byteArrayOf(0x6d, 0x61, 0x6e, 0x67, 0x6f).copyInto(guid, 1)
                        writePairingPacket(output, 1, auth.encrypt(guid))
                    }
                }
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }, "mock-pairing-server").apply { isDaemon = true }.start()

        // ServerSocket(0) 构造即监听，真实客户端连接由内核 backlog 排队，无需探测
        return serverSocket.localPort
    }

    private class ServerPairingAuth(password: ByteArray) {
        private val spake2 = Spake2.createBob(SERVER_NAME, CLIENT_NAME)
        val msg: ByteArray = spake2.generateMessage(password)
        private var key: ByteArray? = null
        private var encCounter = 0L
        private var decCounter = 0L

        fun initCipher(theirMsg: ByteArray) {
            key = hkdfSha256(spake2.processMessage(theirMsg), HKDF_INFO.toByteArray(), 16)
        }

        fun encrypt(plain: ByteArray): ByteArray = aesGcm(Cipher.ENCRYPT_MODE, plain)

        fun decrypt(encrypted: ByteArray): ByteArray? =
            try { aesGcm(Cipher.DECRYPT_MODE, encrypted) } catch (e: Exception) { null }

        private fun aesGcm(mode: Int, data: ByteArray): ByteArray {
            val counter = if (mode == Cipher.ENCRYPT_MODE) encCounter++ else decCounter++
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key!!, "AES"), GCMParameterSpec(128, nonce))
            }.doFinal(data)
        }
    }

    private fun readPairingPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = ByteArray(6)
        input.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = bb.get().toInt()
        val type = bb.get().toInt()
        val size = bb.int
        assertTrue("协议版本", version == 1)
        val payload = ByteArray(size)
        input.readFully(payload)
        return type to payload
    }

    private fun writePairingPacket(output: DataOutputStream, type: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put(1).put(type.toByte()).putInt(payload.size).array()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    // ============================ ADB 连接协议 ============================

    @Test
    fun adbClientConnectsAndRunsShellThroughMockAdbd() {
        val banner = "device::ro.product.name=mock;features=cmd,shell_v2"
        val serverDone = CompletableFuture<Void>()
        val server = ServerSocket(0)
        val port = server.localPort

        Thread({
            try {
                server.use { listener ->
                    listener.soTimeout = 15_000
                    val plain = listener.accept()
                    plain.tcpNoDelay = true
                    plain.use { s ->
                        // 注意：输入流不能加 buffered()，否则 TLS 升级时会把 ClientHello 字节吞进缓冲区
                        val input = DataInputStream(s.getInputStream())
                        val output = DataOutputStream(s.getOutputStream().buffered())

                        // 1) 明文 CNXN（校验客户端校验和算法）
                        val cnxn = readAdbMessage(input)
                        assertEquals("CNXN", cnxn.command)
                        assertTrue("CNXN 负载校验和应正确", cnxn.checksumValid)

                        // 2) STLS 升级协商
                        writeAdbMessage(output, "STLS", 0x01000000, 0, ByteArray(0))
                        val stls = readAdbMessage(input)
                        assertEquals("STLS", stls.command)
                        assertEquals(0x01000000, stls.arg0)

                        // 3) TLS 1.3 升级（服务端模式，要求客户端证书，同 adbd）
                        val ssl = AdbTls.sslContext(serverKeyPair).socketFactory
                            .createSocket(s, "127.0.0.1", port, false) as SSLSocket
                        ssl.use { tls ->
                            tls.useClientMode = false
                            tls.needClientAuth = true
                            tls.enabledProtocols = arrayOf("TLSv1.3")
                            tls.startHandshake()
                            val tlsInput = DataInputStream(tls.inputStream)
                            val tlsOutput = DataOutputStream(tls.outputStream.buffered())

                            // 4) AUTH TOKEN → 校验签名 → CNXN
                            val token = ByteArray(20) { (it * 7 + 3).toByte() }
                            writeAdbMessage(tlsOutput, "AUTH", 1, 0, token)
                            val sig = readAdbMessage(tlsInput)
                            assertEquals("AUTH", sig.command)
                            assertEquals("应回复签名", 2, sig.arg0)
                            val verified = Signature.getInstance("SHA1withRSA").apply {
                                initVerify(clientKeyPair.public); update(token)
                            }.verify(sig.payload)
                            assertTrue("AUTH 签名应验证通过", verified)

                            writeAdbMessage(
                                tlsOutput, "CNXN", 0x01000001, 4096,
                                banner.toByteArray()
                            )

                            // 5) shell 流：OPEN → OKAY → WRTE → （收 OKAY）→ CLSE
                            val open = readAdbMessage(tlsInput)
                            assertEquals("OPEN", open.command)
                            val localId = open.arg0
                            val command = String(open.payload).trimEnd('\u0000')
                            assertTrue("shell 命令", command.startsWith("shell:"))

                            writeAdbMessage(tlsOutput, "OKAY", 1, localId, ByteArray(0))
                            val outputText = "mock-adb-ok\n"
                            writeAdbMessage(tlsOutput, "WRTE", 1, localId, outputText.toByteArray())

                            val ack = readAdbMessage(tlsInput)
                            assertEquals("客户端应回 OKAY 确认 WRTE", "OKAY", ack.command)
                            writeAdbMessage(tlsOutput, "CLSE", 1, localId, ByteArray(0))
                        }
                    }
                }
                serverDone.complete(null)
            } catch (t: Throwable) {
                serverDone.completeExceptionally(t)
            }
        }, "mock-adbd").apply { isDaemon = true }.start()

        AdbClient("127.0.0.1", port, clientKeyPair).use { client ->
            client.connect()
            val shellOutput = client.shell("echo mock-adb-ok")
            assertEquals("mock-adb-ok\n", shellOutput)
        }
        serverDone.get(15, TimeUnit.SECONDS)
    }

    @Test
    fun adbClientFailsWhenServerClosesDuringHandshake() {
        val server = ServerSocket(0)
        val port = server.localPort
        Thread({
            try {
                server.accept().use { it.close() } // 握手中途断开
            } catch (_: Exception) {
            }
        }).apply { isDaemon = true }.start()

        try {
            AdbClient("127.0.0.1", port, clientKeyPair).use { it.connect() }
            fail("连接应失败")
        } catch (expected: Exception) {
            assertNotNull(expected)
        }
    }

    // ============================ ADB 消息工具 ============================

    private class AdbMsg(
        val command: String,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
        val checksumValid: Boolean
    )

    private fun readAdbMessage(input: DataInputStream): AdbMsg {
        val header = ByteArray(24)
        input.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmdInt = bb.int
        val command = String(ByteArray(4) { (cmdInt ushr (8 * it)).toByte() }, Charsets.US_ASCII)
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        val checksum = bb.int
        bb.int // magic
        val payload = ByteArray(len)
        if (len > 0) input.readFully(payload)
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xFF
        return AdbMsg(command, arg0, arg1, payload, sum == checksum)
    }

    private fun writeAdbMessage(
        output: DataOutputStream,
        command: String,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val cb = command.toByteArray(Charsets.US_ASCII)
        val cmdInt = (cb[0].toInt() and 0xff) or ((cb[1].toInt() and 0xff) shl 8) or
                ((cb[2].toInt() and 0xff) shl 16) or ((cb[3].toInt() and 0xff) shl 24)
        var checksum = 0
        for (b in payload) checksum += b.toInt() and 0xFF
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmdInt).putInt(arg0).putInt(arg1).putInt(payload.size).putInt(checksum)
        buf.putInt(cmdInt xor -1)
        buf.put(payload)
        output.write(buf.array())
        output.flush()
    }

    // ============================ 通用工具 ============================
}
