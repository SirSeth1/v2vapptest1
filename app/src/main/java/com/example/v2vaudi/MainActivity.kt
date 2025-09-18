package com.example.v2vaudi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.pow
import kotlin.math.sqrt

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

    // Firebase
    private lateinit var db: DatabaseReference
    private val peerMarkers = mutableMapOf<String, Marker>()
    private val peerLastUpdate = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())

    // My Vehicle
    private var myMarker: Marker? = null
    private val myVehicleId = Build.DEVICE + "-" + Build.SERIAL // unique-ish ID

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PEER_TIMEOUT = 10_000L // 10 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Configuration.getInstance().userAgentValue = packageName

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

        // Firebase
        db = FirebaseDatabase.getInstance().getReference("vehicles")

        requestAllPermissions()

        // Listen for other vehicles
        startPeerListener()

        // Start periodic cleanup
        startPeerCleanup()
    }

    // ===== PERMISSIONS =====
    private fun requestAllPermissions() {
        val permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            startLocationUpdates()
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

                    // Push my vehicle state to Firebase
                    val speedKmh = loc.speed * 3.6
                    val braking = speedKmh < 5.0
                    val vehicleData = mapOf(
                        "lat" to loc.latitude,
                        "lon" to loc.longitude,
                        "speed" to speedKmh,
                        "braking" to braking,
                        "timestamp" to System.currentTimeMillis()
                    )
                    db.child(myVehicleId).setValue(vehicleData)
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

        for ((id, marker) in peerMarkers) {
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
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = (kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).pow(2))
        return 2 * R * kotlin.math.asin(sqrt(a))
    }

    // ===== FIREBASE LISTENER =====
    private fun startPeerListener() {
        db.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val id = child.key ?: continue
                    if (id == myVehicleId) continue // skip myself

                    val lat = child.child("lat").getValue(Double::class.java) ?: continue
                    val lon = child.child("lon").getValue(Double::class.java) ?: continue
                    val pos = GeoPoint(lat, lon)

                    peerLastUpdate[id] = System.currentTimeMillis()

                    if (peerMarkers.containsKey(id)) {
                        peerMarkers[id]?.position = pos
                    } else {
                        val marker = Marker(mapView).apply {
                            position = pos
                            title = "Peer Vehicle"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        mapView.overlays.add(marker)
                        peerMarkers[id] = marker
                    }
                }
                mapView.invalidate()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MainActivity, "Firebase error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun startPeerCleanup() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val toRemove = peerLastUpdate.filter { now - it.value > PEER_TIMEOUT }.keys
                for (id in toRemove) {
                    peerMarkers[id]?.let { mapView.overlays.remove(it) }
                    peerMarkers.remove(id)
                    peerLastUpdate.remove(id)
                }
                mapView.invalidate()
                handler.postDelayed(this, 5000)
            }
        }, 5000)
    }

    // ===== LIFECYCLE =====
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        // Remove my entry when app closes
        db.child(myVehicleId).removeValue()
    }
}
