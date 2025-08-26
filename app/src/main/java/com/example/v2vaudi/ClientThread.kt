package com.example.v2vaudi

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

class ClientThread(
    private val host: String,
    private val dataHandler: DataHandler
) : Thread() {

    private var socket: Socket? = null
    private var out: PrintWriter? = null
    private var reader: BufferedReader? = null

    override fun run() {
        try {
            socket = Socket()
            socket!!.connect(InetSocketAddress(host, 8888), 5000)
            Log.d("WiFiDirect", "Client: connected to $host")

            out = PrintWriter(socket!!.getOutputStream(), true) // autoFlush
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

            while (true) {
                val line = reader?.readLine() ?: break
                dataHandler.parseJson(line)
            }

        } catch (e: Exception) {
            Log.e("WiFiDirect", "Client error: ${e.message}")
        } finally {
            closeQuietly()
            Log.d("WiFiDirect", "Client: closed")
        }
    }

    fun sendData(data: String) {
        try {
            out?.println(data)
        } catch (e: Exception) {
            Log.e("WiFiDirect", "Client send error: ${e.message}")
        }
    }

    fun shutdown() {
        interrupt()
        closeQuietly()
    }

    private fun closeQuietly() {
        try { reader?.close() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}
