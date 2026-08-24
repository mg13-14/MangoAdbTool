package com.mango.adbtool.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 基于 NsdManager (mDNS) 的服务发现器，与 Shizuku 同款机制。
 * 系统点开配对码界面时广播 _adb-tls-pairing._tcp，
 * 无线调试开启时广播 _adb-tls-connect._tcp，精准监听即可，无需盲扫端口。
 */
object MangoDiscovery {

    private suspend fun findService(context: Context, type: String, timeoutMs: Long): Pair<String, Int>? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                if (nsdManager == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                // 用局部变量持有 DiscoveryListener，供 resolve 回调和取消时停止发现
                lateinit var discoveryListener: NsdManager.DiscoveryListener
                discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (!serviceInfo.serviceType.contains(type)) return
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: "127.0.0.1"
                                val port = serviceInfo.port
                                if (cont.isActive) {
                                    try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
                                    cont.resume(host to port)
                                }
                            }
                        })
                    }
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                }
                cont.invokeOnCancellation {
                    try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
                }
                try {
                    nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /**
     * 发现配对端口：等待窗口 60s，给用户足够时间在系统里点开配对码弹窗
     */
    suspend fun findPairingPort(context: Context): Pair<String, Int>? =
        findService(context, "_adb-tls-pairing._tcp", 60_000L)

    /**
     * 发现服务端口：配对成功后无线调试已开启，广播常在，短超时即可
     */
    suspend fun findServicePort(context: Context): Pair<String, Int>? =
        findService(context, "_adb-tls-connect._tcp", 20_000L)
}
