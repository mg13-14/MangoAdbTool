package com.mango.adbtool.adb
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
object AdbCrypto {
    fun loadOrGenerate(dir: File): KeyPair {
        val priv = File(dir, "mango.key")
        val pub = File(dir, "mango.key.pub")
        if (priv.exists() && pub.exists()) {
            val kf = KeyFactory.getInstance("RSA")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        }
        dir.mkdirs()
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }
    fun sign(keyPair: KeyPair, token: ByteArray): ByteArray =
        Signature.getInstance("SHA1withRSA").apply { initSign(keyPair.private); update(token) }.sign()
    fun adbPublicKey(keyPair: KeyPair, name: String = "mango@adbtool"): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        var n = pub.modulus.toByteArray()
        if (n.size > 256) n = n.copyOfRange(n.size - 256, n.size)
        val n0 = ((n[252].toLong() and 0xff) shl 24) or ((n[253].toLong() and 0xff) shl 16) or
                ((n[254].toLong() and 0xff) shl 8) or (n[255].toLong() and 0xff)
        val n0inv = (-n0) and 0xffffffffL
        val body = ByteArray(268)
        be32(body, 0, 64); be32(body, 4, n0inv.toInt())
        System.arraycopy(n, 0, body, 8, 256); be32(body, 264, pub.publicExponent.toInt())
        return (java.util.Base64.getEncoder().encodeToString(body) + " " + name).toByteArray()
    }
    private fun be32(a: ByteArray, o: Int, v: Int) {
        a[o] = (v ushr 24).toByte(); a[o + 1] = (v ushr 16).toByte()
        a[o + 2] = (v ushr 8).toByte(); a[o + 3] = v.toByte()
    }
}
