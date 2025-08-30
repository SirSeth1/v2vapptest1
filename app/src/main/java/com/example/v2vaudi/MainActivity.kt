package com.example.v2vaudi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentFilter
import android.location.Location
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import org.osmdroid.views.MapView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var mapView: MapView
    private lateinit var speedText: TextView
    private lateinit var latitudeText: TextView
    private lateinit var longitudeText: TextView
    private lateinit var brakingStatusText: TextView
    private lateinit var distanceAlertText: TextView

    // GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var myLastLocation: Location? = null

    // Markers
    private var myMarker: Marker? = null
    private val realVehicleMarkers = mutableMapOf<String, Marker>()

    // Wi-Fi Direct
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: WiFiDirectBroadcastReceiver
    private lateinit var intentFilter: IntentFilter
    private lateinit var dataHandler: DataHandler

    // Sockets
    private var serverThread: ServerThread? = null
    private var clientThread: ClientThread? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }


    // Distance calculation function

    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // Update Vehicle Marker (peer)

    fun updateVehicleMarker(lat: Double, lon: Double, speed: Double, braking: Boolean) {
        val id = "$lat$lon"
        val pos = GeoPoint(lat, lon)

        // Update or add marker
        realVehicleMarkers[id]?.let { it.position = pos } ?: run {
            val marker = Marker(mapView).apply {
                position = pos
                title = "Peer Vehicle"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
            realVehicleMarkers[id] = marker
        }

        // ✅ Calculate distance from my car
        myLastLocation?.let { myLoc ->
            val distance = calculateDistance(
                myLoc.latitude, myLoc.longitude,
                lat, lon
            )

            when {
                distance < 25 -> {
                    distanceAlertText.text = "⚠️ HIGH ALERT: ${"%.1f".format(distance)} m"
                    distanceAlertText.setTextColor(getColor(android.R.color.holo_red_dark))

                    if (braking) {
                        // Launch DangerAlertActivity
                        val intent = Intent(this, DangerAlertActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }
                }
                distance < 50 -> {
                    distanceAlertText.text = "⚠ WARNING: ${"%.1f".format(distance)} m"
                    distanceAlertText.setTextColor(getColor(android.R.color.holo_orange_dark))
                }
                distance < 100 -> {
                    distanceAlertText.text = "CAUTION: ${"%.1f".format(distance)} m"
                    distanceAlertText.setTextColor(getColor(android.R.color.holo_blue_dark))
                }
                else -> {
                    distanceAlertText.text = "SAFE: ${"%.1f".format(distance)} m"
                    distanceAlertText.setTextColor(getColor(android.R.color.holo_green_dark))
                }
            }
        }

        mapView.invalidate()
    }

    // -----------------------------
    // Update my own marker
    // -----------------------------
    private fun updateLocationUI(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val speedKmh = location.speed * 3.6
        myLastLocation = location // ✅ Save last location

        speedText.text = "Speed: %.2f km/h".format(speedKmh)
        latitudeText.text = "Latitude: %.6f".format(lat)
        longitudeText.text = "Longitude: %.6f".format(lon)
        brakingStatusText.text = "Braking: ${if (speedKmh < 5.0) "YES" else "NO"}"

        val here = GeoPoint(lat, lon)
        if (myMarker == null) {
            myMarker = Marker(mapView).apply {
                position = here
                title = "Your Vehicle"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = null
            }
            mapView.overlays.add(myMarker)
            mapView.controller.setZoom(17.0)
            mapView.controller.setCenter(here)
        } else {
            myMarker?.position = here
            mapView.controller.setCenter(here)
        }
        mapView.invalidate()
    }

    // -----------------------------
    // Lifecycle & Setup
    // -----------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI bindings
        mapView = findViewById(R.id.mapView)
        speedText = findViewById(R.id.speedText)
        latitudeText = findViewById(R.id.latitudeText)
        longitudeText = findViewById(R.id.longitudeText)
        brakingStatusText = findViewById(R.id.brakingStatusText)
        distanceAlertText = findViewById(R.id.distanceAlertText)

        // OSMDroid map setup
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)

        // GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Wi-Fi Direct
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        dataHandler = DataHandler(this)
        receiver = WiFiDirectBroadcastReceiver(manager, channel, this)
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            startLocationUpdates()
            startPeerDiscovery()
        }
    }

    private fun startPeerDiscovery() {
        val fineOk = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearbyOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        if (!fineOk || !nearbyOk) return

        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Discovering peers…", Toast.LENGTH_SHORT).show()
                }
                override fun onFailure(reason: Int) {
                    Toast.makeText(this@MainActivity, "Peer discovery failed: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        } catch (se: SecurityException) {
            Toast.makeText(this, "Wi-Fi Direct permission error: ${se.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    updateLocationUI(loc)

                    val json = dataHandler.createJson(
                        loc.latitude,
                        loc.longitude,
                        loc.speed * 3.6,
                        loc.speed * 3.6 < 5.0
                    )
                    serverThread?.sendData(json)
                    clientThread?.sendData(json)
                }
            }
        }

        val fineOk = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk) {
            try {
                fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
            } catch (se: SecurityException) {
                Toast.makeText(this, "Location permission error: ${se.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -----------------------------
    // Wi-Fi Direct Helpers
    // -----------------------------
    fun updatePeerList(peers: List<WifiP2pDevice>) {
        if (peers.isEmpty()) return
        val target = peers.first()
        val fineOk = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk) return

        val config = WifiP2pConfig().apply { deviceAddress = target.deviceAddress }
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Connecting to ${target.deviceName}", Toast.LENGTH_SHORT).show()
                }
                override fun onFailure(reason: Int) {
                    Toast.makeText(this@MainActivity, "Connection failed: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        } catch (se: SecurityException) {
            Toast.makeText(this, "Wi-Fi Direct connection error: ${se.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun startServer() {
        serverThread?.shutdown()
        clientThread?.shutdown()
        serverThread = ServerThread(dataHandler).also { it.start() }
    }

    fun startClient(host: String) {
        serverThread?.shutdown()
        clientThread?.shutdown()
        clientThread = ClientThread(host, dataHandler).also { it.start() }
    }

    // -----------------------------
    // Lifecycle
    // -----------------------------
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        serverThread?.shutdown()
        clientThread?.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationUpdates()
                startPeerDiscovery()
            } else {
                Toast.makeText(this, "Permissions are required for this app", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
