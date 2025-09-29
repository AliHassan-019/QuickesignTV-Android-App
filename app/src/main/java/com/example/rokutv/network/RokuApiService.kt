package com.example.rokutv.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.rokutv.data.Device
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.math.min

object RokuApiService {

    enum class PowerMode { POWER_ON, POWER_OFF, DISPLAY_OFF, UNKNOWN }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    // Short timeout client for sweeps
    private val sweepClient = client.newBuilder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .writeTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    /** Send a Roku ECP command */
    fun sendCommand(ip: String, command: String): Boolean {
        return try {
            val url = "http://$ip:8060/$command"
            val empty = RequestBody.create("text/plain".toMediaTypeOrNull(), ByteArray(0))
            val req = Request.Builder().url(url).post(empty).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("RokuHTTP", "sendCommand error $ip", e)
            false
        }
    }

    /** Query /device-info and extract name + power mode */
    fun getDeviceInfo(ip: String): Pair<String, PowerMode?> {
        return try {
            val req = Request.Builder().url("http://$ip:8060/query/device-info").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "" to null
                val body = resp.body?.string().orEmpty()
                val nameRegex = Regex("<friendly-device-name>(.*?)</friendly-device-name>", RegexOption.IGNORE_CASE)
                val powerRegex = Regex("<power-mode>(.*?)</power-mode>", RegexOption.IGNORE_CASE)

                val name = nameRegex.find(body)?.groupValues?.getOrNull(1)?.trim()
                    ?: "Roku Device"
                val mode = when (powerRegex.find(body)?.groupValues?.getOrNull(1)?.trim()?.lowercase()) {
                    "poweron" -> PowerMode.POWER_ON
                    "poweroff" -> PowerMode.POWER_OFF
                    "displayoff" -> PowerMode.DISPLAY_OFF
                    else -> PowerMode.UNKNOWN
                }
                name to mode
            }
        } catch (e: Exception) {
            Log.e("RokuHTTP", "device-info error $ip", e)
            "" to null
        }
    }

    fun getPowerMode(ip: String): PowerMode? {
        return try {
            val req = Request.Builder().url("http://$ip:8060/query/device-info").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val regex = Regex("<power-mode>(.*?)</power-mode>", RegexOption.IGNORE_CASE)
                when (regex.find(body)?.groupValues?.getOrNull(1)?.trim()?.lowercase()) {
                    "poweron" -> PowerMode.POWER_ON
                    "poweroff" -> PowerMode.POWER_OFF
                    "displayoff" -> PowerMode.DISPLAY_OFF
                    else -> PowerMode.UNKNOWN
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isPoweredOn(ip: String): Boolean = (getPowerMode(ip) == PowerMode.POWER_ON)

    /** Discover Roku devices (SSDP first, fallback to HTTP sweep) */
    fun discoverDevices(context: Context, timeoutMs: Int = 3000): List<Device> {
        val ssdp = try { ssdpDiscover(timeoutMs) } catch (_: Exception) { emptySet() }
        if (ssdp.isNotEmpty()) {
            Log.i("RokuScan", "SSDP found: $ssdp")
            return ssdp.map { ip -> Device(ip, getDeviceInfo(ip).first) }
        }

        Log.w("RokuScan", "No SSDP response. Running HTTP sweep…")
        val sweep = try { httpSweepSubnet(context) } catch (_: Exception) { emptySet() }
        return sweep.map { ip -> Device(ip, getDeviceInfo(ip).first) }
    }

    // ---------- Internals ----------

    private fun ssdpDiscover(timeoutMs: Int): Set<String> {
        val found = mutableSetOf<String>()
        val mSearch = """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            MAN: "ssdp:discover"
            MX: 1
            ST: roku:ecp

        """.trimIndent().replace("\n", "\r\n")

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val group = InetAddress.getByName("239.255.255.250")
            val msg = mSearch.toByteArray(Charset.forName("UTF-8"))
            socket.send(DatagramPacket(msg, msg.size, InetSocketAddress(group, 1900)))

            val buf = ByteArray(4096)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                try {
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    val text = String(resp.data, 0, min(resp.length, buf.size))
                    val locLine = text.lines().firstOrNull { it.startsWith("LOCATION:", true) }
                    val host = locLine?.substringAfter("http://")?.split(":")?.firstOrNull()
                    host?.let { found.add(it) }
                } catch (_: Exception) { }
            }
        }
        return found
    }

    private fun httpSweepSubnet(context: Context): Set<String> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptySet()
        val dhcp = wifi.dhcpInfo ?: return emptySet()
        val myIp = intToIp(dhcp.ipAddress)
        val prefix = myIp.substringBeforeLast(".")
        val found = mutableSetOf<String>()

        runBlocking(Dispatchers.IO) {
            val jobs = (1..254).map { last ->
                val ip = "$prefix.$last"
                async {
                    if (ip != myIp && httpProbe(ip)) found.add(ip)
                }
            }
            jobs.awaitAll()
        }
        return found
    }

    private fun httpProbe(ip: String): Boolean {
        return try {
            val req = Request.Builder().url("http://$ip:8060/query/device-info").get().build()
            sweepClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun intToIp(addr: Int): String =
        "${addr and 0xFF}.${addr shr 8 and 0xFF}.${addr shr 16 and 0xFF}.${addr shr 24 and 0xFF}"
}
