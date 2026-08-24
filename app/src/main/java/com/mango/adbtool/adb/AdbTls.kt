package com.mango.adbtool.adb

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Principal
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * ADB TLS 工具集：
 * 1. 手工构造 DER 编码的自签名 X.509 证书（无需 BouncyCastle）；
 * 2. 构建携带客户端证书的 TLS 1.3 SSLContext（adbd 要求客户端必须出示证书）；
 * 3. 通过 Conscrypt 导出 TLS 导出密钥材料（EKM），配对协议用它加固口令。
 */
internal object AdbTls {

    private val contextCache = ConcurrentHashMap<KeyPair, SSLContext>()

    /** 生成（不缓存）自签名证书，测试里的 mock 服务端也会用到。 */
    fun selfSignedCertificate(keyPair: KeyPair, cn: String = "Mango ADB"): X509Certificate =
        SelfSignedCertificate.generate(keyPair, cn)

    /** 取（或生成并缓存）给定密钥对的 TLS 上下文：自签名证书 + 信任所有对端。 */
    fun sslContext(keyPair: KeyPair): SSLContext =
        contextCache.getOrPut(keyPair) { createSslContext(keyPair) }

    private fun createSslContext(keyPair: KeyPair): SSLContext {
        val cert = SelfSignedCertificate.generate(keyPair)
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(
            arrayOf<KeyManager>(SingleCertKeyManager("mango", keyPair.private, arrayOf(cert))),
            arrayOf<TrustManager>(TrustAll),
            SecureRandom()
        )
        return ctx
    }

    /**
     * 导出 TLS 导出密钥材料（RFC 5705/RFC 8446 §7.5）。
     * Android 平台 Conscrypt 位于 com.android.org.conscrypt（API 29+）或
     * org.conscrypt（旧平台/独立库），JVM 测试使用 org.conscrypt:conscrypt-openjdk。
     */
    fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray {
        val errors = mutableListOf<Throwable>()
        for (className in arrayOf("org.conscrypt.Conscrypt", "com.android.org.conscrypt.Conscrypt")) {
            try {
                val cls = Class.forName(className)
                val method = cls.getMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType
                )
                return method.invoke(null, socket, label, null, length) as ByteArray
            } catch (e: ClassNotFoundException) {
                // 换下一个类名
            } catch (e: LinkageError) {
                errors.add(e)
            } catch (e: ReflectiveOperationException) {
                errors.add(e)
            }
        }
        throw IllegalStateException("无法导出 TLS 密钥材料（Conscrypt 不可用）", errors.firstOrNull())
    }

    /** 单证书 KeyManager：TLS 握手时出示我们的自签名证书（客户端/服务端角色皆可）。 */
    private class SingleCertKeyManager(
        private val alias: String,
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = alias
        override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = alias
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = chain.takeIf { alias == this.alias }
        override fun getPrivateKey(alias: String?): PrivateKey? = key.takeIf { alias == this.alias }
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = alias
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = alias
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * 手工 DER 编码生成自签名 X.509 v3 证书（SHA256withRSA）。
     * adbd 只要求客户端能出示一张可解析的证书，不校验签发链，
     * 因此无需引入证书库依赖。
     */
    private object SelfSignedCertificate {
        private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11" // sha256WithRSAEncryption
        private const val OID_CN = "2.5.4.3"                        // commonName

        fun generate(keyPair: KeyPair, cn: String = "Mango ADB", validDays: Long = 30 * 365L): X509Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24L * 3600 * 1000) // 起始回拨一天，容忍时钟偏差
            val notAfter = Date(now + validDays * 24L * 3600 * 1000)

            val name = name(cn)
            val spki = keyPair.public.encoded // 本身就是 DER 的 SubjectPublicKeyInfo
            val serial = BigInteger(63, SecureRandom())

            val tbs = derSeq(
                derExplicit(0, derInt(2)),                                  // version v3
                derInt(serial),                                             // serialNumber
                algId(),                                                    // signature
                name,                                                       // issuer
                derSeq(utcTime(notBefore), utcTime(notAfter)),             // validity
                name,                                                       // subject
                spki                                                        // subjectPublicKeyInfo
            )
            val signature = Signature.getInstance("SHA256withRSA")
                .apply { initSign(keyPair.private); update(tbs) }.sign()

            val certificate = derSeq(tbs, algId(), derBitString(signature))
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
        }

        private fun name(cn: String): ByteArray =
            derSeq(derSet(derSeq(derOid(OID_CN), derUtf8(cn))))

        private fun algId(): ByteArray = derSeq(derOid(OID_SHA256_RSA), derNull())

        // ---------- DER 基础编码 ----------

        private fun derLength(len: Int): ByteArray = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), len.toByte())
        }

        private fun tlv(tag: Int, content: ByteArray): ByteArray {
            val len = derLength(content.size)
            return ByteArray(1 + len.size + content.size).also {
                it[0] = tag.toByte()
                System.arraycopy(len, 0, it, 1, len.size)
                System.arraycopy(content, 0, it, 1 + len.size, content.size)
            }
        }

        private fun derSeq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { acc, b -> acc + b })
        private fun derSet(content: ByteArray) = tlv(0x31, content)
        private fun derBitString(data: ByteArray): ByteArray {
            val content = byteArrayOf(0) + data // 首字节 0 = 无未用位
            return tlv(0x03, content)
        }

        private fun derInt(value: BigInteger): ByteArray {
            var bytes = value.toByteArray()
            if (bytes.isEmpty()) bytes = byteArrayOf(0)
            return tlv(0x02, bytes)
        }

        private fun derInt(value: Int): ByteArray = derInt(BigInteger.valueOf(value.toLong()))

        private fun derOid(dotted: String): ByteArray {
            val parts = dotted.split(".").map { it.toInt() }
            val body = mutableListOf<Byte>()
            body.add(((parts[0] * 40) + parts[1]).toByte())
            for (i in 2 until parts.size) {
                var v = parts[i]
                val tmp = mutableListOf<Byte>()
                tmp.add((v and 0x7F).toByte())
                v = v ushr 7
                while (v > 0) {
                    tmp.add(((v and 0x7F) or 0x80).toByte())
                    v = v ushr 7
                }
                tmp.reverse()
                body.addAll(tmp)
            }
            return tlv(0x06, body.toByteArray())
        }

        private fun derUtf8(value: String) = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

        private fun derNull() = byteArrayOf(0x05, 0x00)

        private fun derExplicit(tagNumber: Int, content: ByteArray) = tlv(0xA0 + tagNumber, content)

        /** X.509 Time：1950–2049 用 UTCTime，之后用 GeneralizedTime。 */
        private fun utcTime(date: Date): ByteArray {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.time = date
            fun f(n: Int) = n.toString().padStart(2, '0')
            val year = cal.get(java.util.Calendar.YEAR)
            val time = "${f(cal.get(java.util.Calendar.MONTH) + 1)}" +
                "${f(cal.get(java.util.Calendar.DAY_OF_MONTH))}${f(cal.get(java.util.Calendar.HOUR_OF_DAY))}" +
                "${f(cal.get(java.util.Calendar.MINUTE))}${f(cal.get(java.util.Calendar.SECOND))}Z"
            return if (year in 1950..2049) {
                tlv(0x17, (f(year % 100) + time).toByteArray(Charsets.US_ASCII))
            } else {
                tlv(0x18, (year.toString().padStart(4, '0') + time).toByteArray(Charsets.US_ASCII))
            }
        }
    }
}
