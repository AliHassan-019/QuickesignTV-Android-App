package com.example.rokutv.network

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

    /**
     * Send an ECP command. Returns true on HTTP success, false otherwise.
     */
    fun sendCommand(ip: String, command: String): Boolean {
        return try {
            val url = "http://$ip:8060/$command"
            val empty = RequestBody.create("text/plain".toMediaTypeOrNull(), ByteArray(0))
            val request = Request.Builder().url(url).post(empty).build()
            client.newCall(request).execute().use { resp -> resp.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * SSDP discovery for Roku devices (ST: roku:ecp). Returns a set of IPs.
     */
    fun discoverDevices(timeoutMs: Int = 1500): Set<String> {
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
                val packet = DatagramPacket(
                    mSearch.toByteArray(Charset.forName("UTF-8")),
                    mSearch.length,
                    InetSocketAddress(group, 1900)
                )
                socket.send(packet)

                val buf = ByteArray(4096)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val resp = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(resp)
                        val text = String(resp.data, 0, min(resp.length, buf.size))
                        // Look for LOCATION header like: http://192.168.1.x:8060/
                        val lines = text.lines()
                        val locLine = lines.firstOrNull { it.startsWith("LOCATION:", true) }
                        locLine?.let {
                            val url = it.split(":", limit = 2).getOrNull(1)?.trim() ?: return@let
                            // crude parse
                            val hostPart = url.removePrefix("http://").split(":").firstOrNull()
                            hostPart?.let { ip -> found.add(ip) }
                        }
                    } catch (_: Exception) {
                        // ignore timeouts until outer loop exits
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        return found
    }
}
