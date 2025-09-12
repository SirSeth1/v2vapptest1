package com.example.v2vaudi

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class ServerThread(private val dataHandler: DataHandler) : Thread() {

    @Volatile private var running = true
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var out: PrintWriter? = null
    private var reader: BufferedReader? = null

    override fun run() {
        while (running) {
            try {
                serverSocket = ServerSocket(8888)
                Log.d("ServerThread", "Listening on port 8888…")

                clientSocket = serverSocket!!.accept()
                Log.d("ServerThread", "Client connected: ${clientSocket!!.inetAddress.hostAddress}")

                out = PrintWriter(clientSocket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))

                var line: String? = null
                while (running && reader!!.readLine().also { line = it } != null) {
                    line?.let {
                        Log.d("ServerThread", "Received: $it")
                        dataHandler.parseJson(it)
                    }
                }

            } catch (e: Exception) {
                Log.e("ServerThread", "Error: ${e.message}")
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
            Log.e("ServerThread", "Send error: ${e.message}")
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
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun sleepRetry() {
        try { sleep(3000) } catch (_: Exception) {}
    }
}
