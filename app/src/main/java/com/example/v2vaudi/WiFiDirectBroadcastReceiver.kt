package com.example.v2vaudi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat

class WiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    private val peers = mutableListOf<WifiP2pDevice>()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                Toast.makeText(
                    context,
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "Wi-Fi Direct ON" else "Wi-Fi Direct OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                if (hasWifiDirectPermissions(context)) {
                    try {
                        manager.requestPeers(channel, WifiP2pManager.PeerListListener { list ->
                            peers.clear()
                            peers.addAll(list.deviceList)
                            Log.d("WiFiDirect", "Peers found: ${peers.size}")
                            if (peers.isNotEmpty()) activity.updatePeerList(peers)
                        })
                    } catch (se: SecurityException) {
                        Log.e("WiFiDirect", "requestPeers SecurityException: ${se.message}")
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                if (hasWifiDirectPermissions(context)) {
                    try {
                        val netInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (netInfo?.isConnected == true) {
                            manager.requestConnectionInfo(channel, WifiP2pManager.ConnectionInfoListener { info ->
                                if (info.groupFormed && info.isGroupOwner) {
                                    Toast.makeText(context, "Group Owner (Server)", Toast.LENGTH_SHORT).show()
                                    activity.startServer()
                                } else if (info.groupFormed) {
                                    val host = info.groupOwnerAddress?.hostAddress ?: ""
                                    Toast.makeText(context, "Client → $host", Toast.LENGTH_SHORT).show()
                                    activity.startClient(host)
                                }
                            })
                        }
                    } catch (se: SecurityException) {
                        Log.e("WiFiDirect", "requestConnectionInfo SecurityException: ${se.message}")
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val self = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                Log.d("WiFiDirect", "This device: ${self?.deviceName} status=${self?.status}")
            }
        }
    }

    private fun hasWifiDirectPermissions(context: Context): Boolean {
        val fineOk = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val nearbyOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED

        return fineOk && nearbyOk
    }
}
