package com.example.rokutv.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private val sweepClient = client.newBuilder()
        .connectTimeout(900, TimeUnit.MILLISECONDS)
        .readTimeout(900, TimeUnit.MILLISECONDS)
        .writeTimeout(900, TimeUnit.MILLISECONDS)
        .build()

    /** POST an ECP command; true if HTTP 2xx */
    fun sendCommand(ip: String, command: String): Boolean {
        return try {
            val url = "http://$ip:8060/$command"
            Log.d("RokuHTTP", "POST ECP to $ip")
            val empty = RequestBody.create("text/plain".toMediaTypeOrNull(), ByteArray(0))
            val request = Request.Builder().url(url).post(empty).build()
            client.newCall(request).execute().use { resp ->
                val ok = resp.isSuccessful
                if (!ok) Log.w("RokuHTTP", "HTTP ${resp.code} from $ip")
                ok
            }
        } catch (e: Exception) {
            Log.e("RokuHTTP", "Network error to $ip", e)
            return false
        }
    }

    /** SSDP then HTTP sweep fallback */
    fun discoverDevices(context: Context, timeoutMs: Int = 3000): Set<String> {
        val ssdp = try { ssdpDiscover(timeoutMs) } catch (e: Exception) {
            Log.e("RokuSSDP", "SSDP error", e); emptySet()
        }
        if (ssdp.isNotEmpty()) {
            Log.d("RokuScan", "SSDP found: $ssdp")
            return ssdp
        }
        Log.w("RokuScan", "SSDP found none; fallback to HTTP sweep")
        val sweep = try { httpSweepLocalSubnet(context) } catch (e: Exception) {
            Log.e("RokuScan", "HTTP sweep error", e); emptySet()
        }
        Log.d("RokuScan", "HTTP sweep found: $sweep")
        return sweep
    }

    fun discoverDevices(timeoutMs: Int = 2000): Set<String> = ssdpDiscover(timeoutMs)

    // ---- internals ----
    private fun ssdpDiscover(timeoutMs: Int): Set<String> {
        val found = mutableSetOf<String>()
        val mSearch = """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            MAN: "ssdp:discover"
            MX: 1
            ST: roku:ecp

        """.trimIndent().replace("\n", "\r\n")

        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.bind(null)
                val group = InetAddress.getByName("239.255.255.250")
                val msg = mSearch.toByteArray(Charset.forName("UTF-8"))
                repeat(2) {
                    val packet = DatagramPacket(msg, msg.size, InetSocketAddress(group, 1900))
                    socket.send(packet)
                    Thread.sleep(200)
                }
                val buf = ByteArray(4096)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val resp = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(resp)
                        val text = String(resp.data, 0, min(resp.length, buf.size))
                        val locLine = text.lines().firstOrNull { it.startsWith("LOCATION:", true) }
                        locLine?.let {
                            val url = it.split(":", limit = 2).getOrNull(1)?.trim() ?: return@let
                            val hostPart = url.removePrefix("http://").split(":").firstOrNull()
                            hostPart?.let { ip -> found.add(ip) }
                        }
                    } catch (_: Exception) { /* loop until timeout */ }
                }
            }
        } catch (e: Exception) {
            Log.e("RokuSSDP", "Discovery error", e)
        }
        return found
    }

    private fun httpProbe(ip: String): Boolean {
        return try {
            val req = Request.Builder()
                .url("http://$ip:8060/query/device-info")
                .get()
                .build()
            sweepClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    private fun httpSweepLocalSubnet(context: Context): Set<String> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val dhcp = wifi?.dhcpInfo ?: return emptySet()
        val myIp = intToIp(dhcp.ipAddress)
        val prefix = myIp.substringBeforeLast(".")
        val results = mutableSetOf<String>()

        runBlocking(Dispatchers.IO) {
            val jobs = (1..254).map { last ->
                val ip = "$prefix.$last"
                async { if (ip == myIp) false to ip else httpProbe(ip) to ip }
            }
            jobs.awaitAll().forEach { (ok, ip) -> if (ok) results.add(ip) }
        }
        return results
    }

    private fun intToIp(hostAddress: Int): String {
        return "${hostAddress and 0xFF}.${hostAddress shr 8 and 0xFF}.${hostAddress shr 16 and 0xFF}.${hostAddress shr 24 and 0xFF}"
    }
}
