package com.mango.adbtool.adb
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
object AdbPairing {
    fun pair(host: String, port: Int, code: String, keyPair: KeyPair): Result<Unit> = runCatching {
        require(code.length in 6..8 && code.all { it.isLetterOrDigit() }) { "配对码通常是 6-8 位数字/字母哦 🥭" }
        val socket = trustAll().socketFactory.createSocket() as SSLSocket
        socket.use { s ->
            s.enabledProtocols = arrayOf("TLSv1.2")
            s.enabledCipherSuites = arrayOf("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256")
            s.connect(InetSocketAddress(host, port), 10_000)
            s.startHandshake()
            val input = DataInputStream(s.inputStream)
            val output = s.outputStream
            val (type1, saltMsg) = readMessage(input)
            check(type1 == 1) { "意外的服务端消息类型: $type1" }
            val salt = saltMsg.copyOfRange(0, 16)
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(code.toCharArray(), salt, 10_000, 256)).encoded
            val key = SecretKeySpec(keyBytes, "AES")
            val pubkey = AdbCrypto.adbPublicKey(keyPair, "mango@adbtool")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            writeMessage(output, 2, iv + cipher.doFinal(pubkey))
            val (type2, resp) = readMessage(input)
            check(type2 == 3) { "配对被拒绝(type=$type2)" }
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, resp.copyOfRange(0, 12)))
                doFinal(resp.copyOfRange(12, resp.size))
            }
        }
    }
    private fun readMessage(input: DataInputStream): Pair<Int, ByteArray> {
        val version = input.read()
        check(version == 1) { "不支持的协议版本: $version" }
        val type = input.read()
        val len = input.readUnsignedShort()
        val data = ByteArray(len)
        input.readFully(data)
        return type to data
    }
    private fun writeMessage(output: OutputStream, type: Int, data: ByteArray) {
        output.write(byteArrayOf(1, type.toByte(), (data.size ushr 8).toByte(), data.size.toByte()) + data)
        output.flush()
    }
    private fun trustAll(): SSLContext {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        return SSLContext.getInstance("TLS").apply { init(null, tm, SecureRandom()) }
    }
}
