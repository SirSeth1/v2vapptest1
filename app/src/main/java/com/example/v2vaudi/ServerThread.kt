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
        try {
            serverSocket = ServerSocket(8888)
            Log.d("WiFiDirect", "Server: listening on 8888")
            clientSocket = serverSocket!!.accept()
            Log.d("WiFiDirect", "Server: client connected")

            out = PrintWriter(clientSocket!!.getOutputStream(), true) // autoFlush
            reader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))

            while (true) {
                val line = reader?.readLine() ?: break
                dataHandler.parseJson(line)
            }

        } catch (e: Exception) {
            Log.e("WiFiDirect", "Server error: ${e.message}")
        } finally {
            closeQuietly()
            Log.d("WiFiDirect", "Server: closed")
        }
    }

    fun sendData(data: String) {
        try {
            out?.println(data)
        } catch (e: Exception) {
            Log.e("WiFiDirect", "Server send error: ${e.message}")
        }
    }

    fun shutdown() {
        running = false
        interrupt()
        closeQuietly()
    }

    private fun closeQuietly() {
        try { reader?.close() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
