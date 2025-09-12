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

    @Volatile private var running = true
    private var socket: Socket? = null
    private var out: PrintWriter? = null
    private var reader: BufferedReader? = null

    override fun run() {
        while (running) {
            try {
                socket = Socket()
                socket!!.connect(InetSocketAddress(host, 8888), 5000)
                Log.d("ClientThread", "Connected to server: $host")

                out = PrintWriter(socket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

                var line: String? = null
                while (running && reader!!.readLine().also { line = it } != null) {
                    line?.let {
                        Log.d("ClientThread", "Received: $it")
                        dataHandler.parseJson(it)
                    }
                }

            } catch (e: Exception) {
                Log.e("ClientThread", "Error: ${e.message}")
                sleepRetry()
            } finally {
                closeQuietly()
            }
        }
    }

    fun sendData(data: String) {
        try {
            out?.println(data)
        } catch (e: Exception) {
            Log.e("ClientThread", "Send error: ${e.message}")
        }
    }

    fun shutdown() {
        running = false
        closeQuietly()
        interrupt()
    }

    private fun closeQuietly() {
        try { reader?.close() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun sleepRetry() {
        try { sleep(3000) } catch (_: Exception) {}
    }
}
