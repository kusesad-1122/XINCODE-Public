package com.xincode.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 局域网设备发现:让同一个 Wi-Fi 下的多台设备互相看得见。
 *
 * 协议很小,就是 UDP 广播一问一答:
 *   探测方 → 广播 {"type":"xincode.probe"}
 *   在线方 → 单播回 {"type":"xincode.announce", 设备名/型号/版本/端口...}
 *
 * 几个必须这么做的点:
 *  - 广播地址要按【每张网卡】各算一遍(ip | ~netmask),不能只发 255.255.255.255 ——
 *    不少 Android ROM 和路由器会把全局广播直接丢掉,只认子网定向广播。
 *  - 必须持 [WifiManager.MulticastLock]。不持锁时系统为省电会在 Wi-Fi 驱动层就把
 *    非本机目标的组播/广播包滤掉,应答方【收不到探测包】,表现为「明明都在同一个网里却搜不到」。
 *  - 应答走单播而不是广播回去,省得网络里所有设备陪着一起收。
 */
object LanDiscovery {

    private const val TAG = "XincodeLan"

    /** 固定端口。挑一个不常用的高位端口,避开常见服务。 */
    const val PORT = 47821

    private const val TYPE_PROBE = "xincode.probe"
    private const val TYPE_ANNOUNCE = "xincode.announce"

    data class Peer(
        val address: String,
        val deviceName: String,
        val model: String,
        val version: String,
        val androidVersion: String,
        /** 本机自己也会应答,标出来免得用户以为多了一台。 */
        val isSelf: Boolean = false
    )

    // ---- 应答侧(常驻监听)----

    @Volatile
    private var responderSocket: DatagramSocket? = null

    @Volatile
    private var responderThread: Thread? = null

    /**
     * 启动应答监听。重复调用安全(已在跑就直接返回)。
     * 放在独立线程而不是协程:这是个阻塞式的 receive 死循环,占着协程调度线程不合适。
     */
    fun startResponder(context: Context) {
        if (responderThread?.isAlive == true) return
        val appCtx = context.applicationContext
        val t = Thread({
            var lock: WifiManager.MulticastLock? = null
            try {
                val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                // 不持这把锁就收不到别人的广播,这是本功能最容易踩空的一步。
                lock = wifi?.createMulticastLock("xincode-lan")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(PORT))
                }
                responderSocket = socket
                Log.i(TAG, "responder listening on udp/$PORT")
                val buf = ByteArray(2048)
                while (!Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)   // 阻塞
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (obj.optString("type") != TYPE_PROBE) continue
                    // 单播回给探测方,不再广播一次
                    val reply = buildAnnouncement(appCtx).toString().toByteArray(Charsets.UTF_8)
                    runCatching {
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "responder stopped: ${e.message}")
            } finally {
                runCatching { lock?.release() }
                runCatching { responderSocket?.close() }
                responderSocket = null
            }
        }, "xincode-lan-responder").apply { isDaemon = true }
        responderThread = t
        t.start()
    }

    fun stopResponder() {
        responderThread?.interrupt()
        runCatching { responderSocket?.close() }
        responderSocket = null
        responderThread = null
    }

    // ---- 探测侧 ----

    /**
     * 广播一次探测,收集 [timeoutMs] 内的所有应答。
     *
     * 注意这里【不能】复用应答侧那个绑定在 PORT 上的 socket:两边都在 receive
     * 会互相抢包。探测用一个临时端口的新 socket,应答自然回到那个临时端口。
     */
    suspend fun discover(context: Context, timeoutMs: Long = 2500): List<Peer> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, Peer>()
            var lock: WifiManager.MulticastLock? = null
            var socket: DatagramSocket? = null
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = wifi?.createMulticastLock("xincode-lan-probe")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 400          // 短超时轮询,便于整体计时收尾
                }
                val probe = JSONObject().put("type", TYPE_PROBE)
                    .toString().toByteArray(Charsets.UTF_8)

                // 对每个子网广播地址各发一次,再补一发全局广播兜底
                for (target in broadcastTargets()) {
                    runCatching {
                        socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName(target), PORT))
                    }
                }

                val selfAddrs = localAddresses()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    val ok = runCatching { socket.receive(packet); true }.getOrElse { false }
                    if (!ok) continue        // soTimeout 到点,继续等到 deadline
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (obj.optString("type") != TYPE_ANNOUNCE) continue
                    val addr = packet.address.hostAddress ?: continue
                    found[addr] = Peer(
                        address = addr,
                        deviceName = obj.optString("device_name", "未知设备"),
                        model = obj.optString("model", ""),
                        version = obj.optString("version", ""),
                        androidVersion = obj.optString("android", ""),
                        isSelf = addr in selfAddrs
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "discover failed: ${e.message}")
            } finally {
                runCatching { socket?.close() }
                runCatching { lock?.release() }
            }
            found.values.sortedWith(compareBy({ !it.isSelf }, { it.address }))
        }

    // ---- helpers ----

    private fun buildAnnouncement(context: Context): JSONObject {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        return JSONObject().apply {
            put("type", TYPE_ANNOUNCE)
            put("device_name", android.os.Build.DEVICE ?: "android")
            put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
            put("version", version)
            put("android", android.os.Build.VERSION.RELEASE ?: "")
            put("port", PORT)
        }
    }

    /**
     * 每张网卡的子网定向广播地址 + 全局广播。
     * 只发 255.255.255.255 在很多设备上收不到,所以子网定向那份才是主力。
     */
    private fun broadcastTargets(): List<String> {
        val targets = LinkedHashSet<String>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    ia.broadcast?.hostAddress?.let { targets.add(it) }
                }
            }
        }
        targets.add("255.255.255.255")
        return targets.toList()
    }

    private fun localAddresses(): Set<String> {
        val out = mutableSetOf("127.0.0.1")
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (ia in nif.inetAddresses) {
                    ia.hostAddress?.let { out.add(it) }
                }
            }
        }
        return out
    }
}
