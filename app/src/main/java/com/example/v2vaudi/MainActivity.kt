package com.example.v2vaudi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.location.Location
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.overlay.Marker
import kotlin.math.pow
import kotlin.math.sqrt
import android.content.IntentFilter

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

    // Markers
    private var myMarker: Marker? = null
    private val realVehicleMarkers = mutableMapOf<String, Marker>()
    private val peerLastUpdate = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())

    // Wi-Fi Direct
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: WiFiDirectBroadcastReceiver
    private lateinit var dataHandler: DataHandler

    // Threads
    private var serverThread: ServerThread? = null
    private var clientThread: ClientThread? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PEER_TIMEOUT = 10_000L // 10 seconds
    }

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

        // Map setup
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)

        // GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Wi-Fi Direct
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        dataHandler = DataHandler(this)
        receiver = WiFiDirectBroadcastReceiver(manager, channel, this)

        requestAllPermissions()

        // Start periodic peer cleanup
        startPeerCleanup()
    }

    // ===== PERMISSIONS =====
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
            discoverPeers()
        }
    }

    // ===== GPS =====
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    updateLocationUI(loc)

                    val speedKmh = loc.speed * 3.6
                    val json = dataHandler.createJson(
                        loc.latitude, loc.longitude, speedKmh, speedKmh < 5.0
                    )
                    serverThread?.sendData(json)
                    clientThread?.sendData(json)
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
        }
    }

    private fun updateLocationUI(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val speedKmh = location.speed * 3.6

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
            }
            mapView.overlays.add(myMarker)
            mapView.controller.setZoom(17.0)
        }
        myMarker?.position = here
        mapView.controller.setCenter(here)
        mapView.invalidate()

        // Calculate warnings
        checkPeerDistances(lat, lon, speedKmh)
    }

    // ===== DISTANCE & SPEED ALERTS =====
    private fun checkPeerDistances(myLat: Double, myLon: Double, mySpeed: Double) {
        var alert = "SAFE"
        var dangerTriggered = false

        for ((id, marker) in realVehicleMarkers) {
            val dist = haversine(myLat, myLon, marker.position.latitude, marker.position.longitude)
            val safeDist = calculateSafeStoppingDistance(mySpeed)

            when {
                dist < 25 && mySpeed > safeDist -> {
                    alert = "DANGER: BRAKE!"
                    dangerTriggered = true
                }
                dist < 50 && mySpeed > safeDist -> {
                    alert = "WARNING: TOO CLOSE"
                }
                dist < 100 && mySpeed > safeDist -> {
                    alert = "CAUTION: Approaching"
                }
            }
        }

        distanceAlertText.text = "Distance Alert: $alert"

        if (dangerTriggered) {
            startActivity(Intent(this, DangerAlertActivity::class.java))
        }
    }

    private fun calculateSafeStoppingDistance(speedKmh: Double): Double {
        val speedMs = speedKmh / 3.6
        val reactionTime = 1.5 // seconds
        val decel = 6.0 // m/s²
        return speedMs * reactionTime + (speedMs.pow(2) / (2 * decel))
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in m
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = (kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).pow(2))
        return 2 * R * kotlin.math.asin(sqrt(a))
    }

    // ===== PEER MANAGEMENT =====
    fun updateVehicleMarker(lat: Double, lon: Double, speed: Double, braking: Boolean) {
        val id = "$lat$lon"
        val pos = GeoPoint(lat, lon)

        peerLastUpdate[id] = System.currentTimeMillis()

        if (realVehicleMarkers.containsKey(id)) {
            realVehicleMarkers[id]?.position = pos
        } else {
            val marker = Marker(mapView).apply {
                position = pos
                title = "Peer Vehicle"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
            realVehicleMarkers[id] = marker
        }
        mapView.invalidate()
    }

    private fun startPeerCleanup() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val toRemove = peerLastUpdate.filter { now - it.value > PEER_TIMEOUT }.keys
                for (id in toRemove) {
                    realVehicleMarkers[id]?.let { mapView.overlays.remove(it) }
                    realVehicleMarkers.remove(id)
                    peerLastUpdate.remove(id)
                }
                mapView.invalidate()
                handler.postDelayed(this, 5000)
            }
        }, 5000)
    }

    // ===== WIFI DIRECT =====
    private fun discoverPeers() {
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Discovering peers…", Toast.LENGTH_SHORT).show()
                }
                override fun onFailure(reason: Int) {
                    Toast.makeText(this@MainActivity, "Peer discovery failed: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        } catch (_: SecurityException) {}
    }

    fun updatePeerList(peers: List<WifiP2pDevice>) {
        if (peers.isEmpty()) return
        val target = peers.first() // auto-connect to first
        val config = WifiP2pConfig().apply { deviceAddress = target.deviceAddress }
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Auto-connecting to ${target.deviceName}", Toast.LENGTH_SHORT).show()
                }
                override fun onFailure(reason: Int) {
                    Toast.makeText(this@MainActivity, "Connection failed: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        } catch (_: SecurityException) {}
    }

    fun startServer() {
        serverThread = ServerThread(dataHandler)
        serverThread?.start()
    }

    fun startClient(host: String) {
        clientThread = ClientThread(host, dataHandler)
        clientThread?.start()
    }

    // ===== LIFECYCLE =====
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        })
    }



    override fun onPause() {
        super.onPause()
        mapView.onPause()
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serverThread?.shutdown()
        clientThread?.shutdown()
    }
}

