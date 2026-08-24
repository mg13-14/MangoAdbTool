package com.mango.adbtool.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BoringSSL SPAKE2 已知答案测试（KAT）。
 *
 * 向量来源：Flyfish233/spake2-java 测试资源（从 BoringSSL 源码
 * commit 9e04aed2d1652441d2c84e85ed21acc05e053260 的确定性 harness 直接生成），
 * 覆盖：
 * - G：确定性私钥/口令 → 生成消息逐比特比对；
 * - P：处理对方消息 → 64 字节共享密钥逐比特比对（含 ADB 真实名字用例）；
 * - F：非法输入必须拒绝（长度错误等）；
 * - X：456 组随机全交换模糊向量（空名字、随机口令、高位进位等）。
 */
class Spake2KatTest {

    companion object {
        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { i ->
                ((Character.digit(s[2 * i], 16) shl 4) + Character.digit(s[2 * i + 1], 16)).toByte()
            }

        private fun vectorLines(): List<String> =
            Spake2KatTest::class.java.getResourceAsStream("/spake2/boringssl-oracle-vectors.txt")!!
                .bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }

        private fun newSpake(role: String, myName: ByteArray, theirName: ByteArray): Spake2 =
            if (role == "alice") Spake2.createAlice(myName, theirName)
            else Spake2.createBob(myName, theirName)
    }

    @Test
    fun generateMessageMatchesBoringSSL() {
        val cases = vectorLines().filter { it.startsWith("G|") }
        assertTrue("测试向量缺失", cases.isNotEmpty())
        for (line in cases) {
            val f = line.split('|')
            val spake = newSpake(f[1], hex(f[2]), hex(f[3]))
            val msg = spake.generateMessage(hex(f[4]), hex(f[5]))
            assertArrayEquals("生成消息不匹配 [$line]", hex(f[6]), msg)
            assertEquals("消息长度必须为 32", 32, msg.size)
        }
    }

    @Test
    fun processMessageMatchesBoringSSL() {
        val cases = vectorLines().filter { it.startsWith("P|") }
        assertTrue(cases.size >= 10)
        for (line in cases) {
            val f = line.split('|')
            val spake = newSpake(f[1], hex(f[2]), hex(f[3]))
            spake.generateMessage(hex(f[4]), hex(f[5]))
            val key = spake.processMessage(hex(f[6]))
            assertEquals("密钥长度必须为 64", 64, key.size)
            assertArrayEquals("派生密钥不匹配 [$line]", hex(f[7]), key)
        }
    }

    @Test
    fun processMessageRejectsInvalidInput() {
        val cases = vectorLines().filter { it.startsWith("F|") }
        assertTrue(cases.isNotEmpty())
        for (line in cases) {
            val f = line.split('|')
            val spake = newSpake(f[1], hex(f[2]), hex(f[3]))
            spake.generateMessage(hex(f[4]), hex(f[5]))
            try {
                spake.processMessage(hex(f[6]))
                fail("非法输入应被拒绝 [$line]")
            } catch (expected: Exception) {
                // 预期：长度不符/点不合法
            }
        }
    }

    @Test
    fun fullExchangeMatchesBoringSSL() {
        val cases = vectorLines().filter { it.startsWith("X|") }
        assertEquals("应包含全部 456 组模糊向量", 456, cases.size)
        for (line in cases) {
            val f = line.split('|')
            val password = hex(f[3])
            val alice = Spake2.createAlice(hex(f[1]), hex(f[2]))
            val bob = Spake2.createBob(hex(f[2]), hex(f[1]))

            val aliceMsg = alice.generateMessage(password, hex(f[4]))
            val bobMsg = bob.generateMessage(password, hex(f[5]))
            assertArrayEquals("Alice 消息不匹配 [$line]", hex(f[6]), aliceMsg)
            assertArrayEquals("Bob 消息不匹配 [$line]", hex(f[7]), bobMsg)

            val aliceKey = alice.processMessage(bobMsg)
            val bobKey = bob.processMessage(aliceMsg)
            assertArrayEquals("Alice 密钥不匹配 [$line]", hex(f[8]), aliceKey)
            assertArrayEquals("Bob 密钥不匹配 [$line]", hex(f[9]), bobKey)
        }
    }

    /** 与 BoringSSL 官方测试套件对齐的自一致性测试（20 轮随机）。 */
    @Test
    fun randomRunsAgreeOnBothSides() {
        val clientName = "adb pair client\u0000".toByteArray()
        val serverName = "adb pair server\u0000".toByteArray()
        repeat(20) { round ->
            val password = ByteArray(6 + round) { ('a' + it).code.toByte() }
            val alice = Spake2.createAlice(clientName, serverName)
            val bob = Spake2.createBob(serverName, clientName)
            val aliceMsg = alice.generateMessage(password)
            val bobMsg = bob.generateMessage(password)
            assertArrayEquals("第 $round 轮双方密钥应一致", alice.processMessage(bobMsg), bob.processMessage(aliceMsg))
        }
    }

    /** 错误口令 → 双方密钥必须不同（BoringSSL WrongPassword 测试）。 */
    @Test
    fun wrongPasswordProducesDifferentKeys() {
        val alice = Spake2.createAlice("alice".toByteArray(), "bob".toByteArray())
        val bob = Spake2.createBob("bob".toByteArray(), "alice".toByteArray())
        val aliceMsg = alice.generateMessage("password".toByteArray())
        val bobMsg = bob.generateMessage("wrong password".toByteArray())
        val aliceKey = alice.processMessage(bobMsg)
        val bobKey = bob.processMessage(aliceMsg)
        assertTrue("口令不同时密钥不应一致", !aliceKey.contentEquals(bobKey))
    }

    /** 名字不一致 → 双方密钥必须不同（BoringSSL WrongNames 测试）。 */
    @Test
    fun wrongNamesProduceDifferentKeys() {
        val alice = Spake2.createAlice("alice".toByteArray(), "charlie".toByteArray())
        val bob = Spake2.createBob("bob".toByteArray(), "alice".toByteArray())
        val aliceMsg = alice.generateMessage("password".toByteArray())
        val bobMsg = bob.generateMessage("password".toByteArray())
        val aliceKey = alice.processMessage(bobMsg)
        val bobKey = bob.processMessage(aliceMsg)
        assertTrue("名字不同时密钥不应一致", !aliceKey.contentEquals(bobKey))
    }
}
