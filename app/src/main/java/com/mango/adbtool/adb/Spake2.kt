package com.mango.adbtool.adb

import cafe.cryptography.curve25519.CompressedEdwardsY
import cafe.cryptography.curve25519.Constants
import cafe.cryptography.curve25519.EdwardsPoint
import cafe.cryptography.curve25519.InvalidEncodingException
import cafe.cryptography.curve25519.Scalar
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 纯 Kotlin 实现的 SPAKE2 密钥协商（RFC 9383），与 BoringSSL / AOSP adbd 的
 * 配对协议（pairing_auth）逐比特兼容。
 *
 * 兼容性关键点（与 BoringSSL spake2.c 对齐）：
 * - 私钥标量取 64 字节随机数经宽约减后左移 3 位（乘余因子 8），不做二次约减；
 * - 口令标量经 SHA-512 + 宽约减后，按低 3 位加 l/2l/4l “硬化”，仅清低位不改子群等价性；
 * - 标量乘法按原始 256 位小端标量逐半字节计算，保留第 255 位；
 * - 传输哈希为 SHA-512，各字段前缀 8 字节小端长度，Alice/Bob 字段顺序不同。
 *
 * 参考：AOSP packages/modules/adb pairing_auth（Apache-2.0 协议规范）。
 */
class Spake2 private constructor(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val random: SecureRandom
) {
    enum class Role { ALICE, BOB }

    class Spake2Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

    private enum class State { INIT, MSG_GENERATED, DONE }

    private var state = State.INIT
    private val privateKey = ByteArray(32)      // 硬化后的临时私钥标量（×8）
    private val myMsg = ByteArray(32)
    private val passwordScalar = ByteArray(32)  // 硬化后的口令标量
    private val passwordHash = ByteArray(64)

    companion object {
        /** RFC 9383 定义的 Edwards25519 上的两个固定掩码点（M/N）。 */
        private val M_POINT = hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        private val N_POINT = hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")
        /** Edwards25519 素数阶子群的群阶 l（小端）。 */
        private val GROUP_ORDER = hexToBytes("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010")

        private val M by lazy { CompressedEdwardsY(M_POINT).decompress() }
        private val N by lazy { CompressedEdwardsY(N_POINT).decompress() }

        fun createAlice(myName: ByteArray, theirName: ByteArray, random: SecureRandom = SecureRandom()) =
            Spake2(Role.ALICE, myName, theirName, random)

        fun createBob(myName: ByteArray, theirName: ByteArray, random: SecureRandom = SecureRandom()) =
            Spake2(Role.BOB, myName, theirName, random)

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte() }
    }

    /** 生成自己的 SPAKE2 消息（32 字节压缩点）。privateInput 仅供测试注入确定性私钥。 */
    fun generateMessage(password: ByteArray, privateInput: ByteArray? = null): ByteArray {
        check(state == State.INIT) { "SPAKE2 状态错误: $state" }
        require(password.isNotEmpty()) { "口令不能为空" }
        val seed = privateInput ?: ByteArray(64).also { random.nextBytes(it) }
        require(seed.size == 64) { "私钥种子必须为 64 字节" }

        // 宽约减到 [0, l)，再左移 3 位乘余因子（不做约减，保留进位）
        val reduced = Scalar.fromBytesModOrderWide(seed).toByteArray()
        leftShift3(reduced)
        System.arraycopy(reduced, 0, privateKey, 0, 32)

        // 口令 → SHA-512 → 宽约减 → 低 3 位硬化
        val pwdHash = sha512(password)
        System.arraycopy(pwdHash, 0, passwordHash, 0, 64)
        val pwdScalar = hardenPasswordScalar(Scalar.fromBytesModOrderWide(pwdHash).toByteArray())
        System.arraycopy(pwdScalar, 0, passwordScalar, 0, 32)

        // P = basepoint * privateKey；mask = (M|N) * passwordScalar；msg = P + mask
        // 与 BoringSSL 一致：两个角色生成消息时都做加法（Alice 掩码点用 M，Bob 用 N）
        val p = multiplyByRawScalar(Constants.ED25519_BASEPOINT, privateKey)
        val maskBase = if (role == Role.ALICE) M else N
        val mask = multiplyByRawScalar(maskBase, passwordScalar)
        val pStar = p.add(mask)

        val encoded = pStar.compress().toByteArray()
        // 编码合法性校验（拒绝不可编码点）
        try {
            CompressedEdwardsY(encoded).decompress()
        } catch (e: InvalidEncodingException) {
            throw Spake2Exception("生成的 SPAKE2 点无法编码", e)
        }
        System.arraycopy(encoded, 0, myMsg, 0, 32)
        state = State.MSG_GENERATED
        return myMsg.copyOf()
    }

    /** 处理对方消息并派生 64 字节共享密钥材料。 */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        check(state == State.MSG_GENERATED) { "SPAKE2 状态错误: $state" }
        require(theirMsg.size == 32) { "对方消息必须为 32 字节, 实际 ${theirMsg.size}" }

        val peerMsg = theirMsg.copyOf()
        val qStar = try {
            CompressedEdwardsY(peerMsg).decompress()
        } catch (e: InvalidEncodingException) {
            throw Spake2Exception("对方 SPAKE2 点不合法", e)
        }

        // 与 BoringSSL 一致：两个角色处理对方消息时都减去对方的掩码点
        // （Alice 剥 N·pw，Bob 剥 M·pw，还原出纯粹的公钥点再乘私钥）
        val peersMaskBase = if (role == Role.ALICE) N else M
        val peersMask = multiplyByRawScalar(peersMaskBase, passwordScalar)
        val qExt = qStar.subtract(peersMask)
        val dhShared = multiplyByRawScalar(qExt, privateKey).compress().toByteArray()

        // 传输哈希：Alice(my,their,myMsg,peerMsg) / Bob(their,my,peerMsg,myMsg)，再接 dhShared 与 passwordHash
        val sha = MessageDigest.getInstance("SHA-512")
        if (role == Role.ALICE) {
            updateWithLengthPrefix(sha, myName)
            updateWithLengthPrefix(sha, theirName)
            updateWithLengthPrefix(sha, myMsg)
            updateWithLengthPrefix(sha, peerMsg)
        } else {
            updateWithLengthPrefix(sha, theirName)
            updateWithLengthPrefix(sha, myName)
            updateWithLengthPrefix(sha, peerMsg)
            updateWithLengthPrefix(sha, myMsg)
        }
        updateWithLengthPrefix(sha, dhShared)
        updateWithLengthPrefix(sha, passwordHash)

        state = State.DONE
        return sha.digest()
    }

    /** 小端 32 字节标量左移 3 位（乘余因子 8），溢出位丢弃——与 BoringSSL 一致。 */
    private fun leftShift3(n: ByteArray) {
        var carry = 0
        for (i in 0 until 32) {
            val nextCarry = (n[i].toInt() and 0xFF) ushr 5
            n[i] = (((n[i].toInt() and 0xFF) shl 3) or carry).toByte()
            carry = nextCarry
        }
    }

    /**
     * BoringSSL 兼容“硬化”：按当前低 3 位依次条件加 l、2l、4l（小端字节加法，不约减），
     * 结果低 3 位清零且在素数阶子群中与原标量等价。
     */
    private fun hardenPasswordScalar(reduced: ByteArray): ByteArray {
        var s = reduced.copyOf()
        var order = GROUP_ORDER.copyOf()
        for (bit in 0 until 3) {
            if (s[0].toInt() and (1 shl bit) != 0) s = addScalars(s, order)
            order = doubleScalar(order)
        }
        return s
    }

    private fun addScalars(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(32)
        var carry = 0
        for (i in 0 until 32) {
            val sum = (a[i].toInt() and 0xFF) + (b[i].toInt() and 0xFF) + carry
            out[i] = sum.toByte()
            carry = sum ushr 8
        }
        return out
    }

    private fun doubleScalar(a: ByteArray): ByteArray {
        val out = ByteArray(32)
        var carry = 0
        for (i in 0 until 32) {
            val v = (a[i].toInt() and 0xFF) shl 1
            out[i] = (v or carry).toByte()
            carry = v ushr 8
        }
        return out
    }

    /**
     * 原始 256 位小端标量乘法：预计算 0..15 倍点表，从最高字节到最低字节按半字节
     * 双勾链计算，不做约减（第 255 位参与运算）。
     */
    private fun multiplyByRawScalar(point: EdwardsPoint, scalar: ByteArray): EdwardsPoint {
        val table = arrayOfNulls<EdwardsPoint>(16)
        table[0] = EdwardsPoint.IDENTITY
        for (i in 1 until 16) table[i] = table[i - 1]!!.add(point)

        var result = EdwardsPoint.IDENTITY
        for (byteIndex in 31 downTo 0) {
            val value = scalar[byteIndex].toInt() and 0xFF
            result = multiplyBy16(result).add(table[value ushr 4]!!)
            result = multiplyBy16(result).add(table[value and 0x0F]!!)
        }
        return result
    }

    private fun multiplyBy16(point: EdwardsPoint): EdwardsPoint {
        var result = point
        repeat(4) { result = result.dbl() }
        return result
    }

    private fun updateWithLengthPrefix(sha: MessageDigest, data: ByteArray) {
        var v = data.size.toLong() and 0xFFFFFFFFL
        val lenLe = ByteArray(8)
        for (i in 0 until 8) {
            lenLe[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        sha.update(lenLe)
        sha.update(data)
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)
}
