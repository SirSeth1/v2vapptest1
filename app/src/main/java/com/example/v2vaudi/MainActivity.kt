package com.example.v2vaudi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.*
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint //A geographical point with latitude and longitude coordinates.
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.pow
import kotlin.math.sqrt
import java.io.File
import android.view.ViewPropertyAnimator
import androidx.core.view.WindowCompat
import android.widget.ImageButton

class MainActivity : AppCompatActivity() {

    // ===== UI =====
    private lateinit var mapView: MapView
    private lateinit var speedText: TextView
    private lateinit var latitudeText: TextView
    private lateinit var longitudeText: TextView
    private lateinit var brakingStatusText: TextView
    private lateinit var distanceAlertText: TextView

    // ===== GPS ===== main entry point for accessing the device’s location via Google Play Services.
    //uses the Fused Location Provider API, which smartly combines GPS, Wi-Fi, and cell networks for accurate and battery-efficient tracking.
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // ===== Map markers =====
    private var myMarker: Marker? = null
    private val realVehicleMarkers = mutableMapOf<String, Marker>()
    private val peerLastUpdate = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())

    // ===== Firebase =====
    private val auth = Firebase.auth
    private val database = Firebase.database
    private val uid: String
        get() = auth.currentUser?.uid ?: "unauthenticated"

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PEER_TIMEOUT = 10_000L // 10 sec no update → remove marker
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        super.onCreate(savedInstanceState)
        //setContentView(R.layout.activity_main)


//added this
        val basePath = File(getExternalFilesDir(null), "osmdroid")
        if (!basePath.exists()) basePath.mkdirs()

        val tileCache = File(basePath, "tiles")
        if (!tileCache.exists()) tileCache.mkdirs()

        Configuration.getInstance().osmdroidBasePath = basePath
        Configuration.getInstance().osmdroidTileCache = tileCache
//added this end

        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = File(getExternalFilesDir(null), "osmdroid")
        Configuration.getInstance().osmdroidTileCache = File(getExternalFilesDir(null), "osmdroid/tiles")

        // ---- UI ----
        mapView = findViewById(R.id.mapView)
        speedText = findViewById(R.id.speedText)
        latitudeText = findViewById(R.id.latitudeText)
        longitudeText = findViewById(R.id.longitudeText)
        brakingStatusText = findViewById(R.id.brakingStatusText)
        distanceAlertText = findViewById(R.id.distanceAlertText)

        // === Profile Button ===
        findViewById<ImageButton>(R.id.btnProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.mapOrientation = 0.0f // start with north up


        // Initial map state
        mapView.controller.setZoom(2.0)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestAllPermissions()

        // Cleanup for stale markers
        startPeerCleanup()

        // Start listening for other vehicles
        listenForVehicles()
    }

    // ================= PERMISSIONS =================
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
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            ensureSignedIn()  // 👈 Auto-login after permissions granted
        }
    }

    // ✅ This method handles user permission responses
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            val allGranted =
                grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                ensureSignedIn()
            } else {
                Toast.makeText(
                    this,
                    "Location permissions are required for the map and tracking.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ================= ANONYMOUS LOGIN =================
    private fun ensureSignedIn() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, "Signed in anonymously", Toast.LENGTH_SHORT).show()
                        startLocationUpdates()
                    } else {
                        Toast.makeText(
                            this,
                            "Auth failed: ${task.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        } else {
            startLocationUpdates()
        }
    }

    // ================= GPS =================
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    updateLocationUI(loc)
                    val speedKmh = loc.speed * 3.6
                    maybeWrite(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        speed = speedKmh,
                        braking = speedKmh < 5.0
                    )
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
        }
    }

    private fun updateLocationUI(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val speedKmh = location.speed * 3.6

        speedText.text = getString(R.string.speed_format, speedKmh)
        latitudeText.text = getString(R.string.latitude_format, lat)
        longitudeText.text = getString(R.string.longitude_format, lon)
        brakingStatusText.text = getString(R.string.braking_status, if (speedKmh < 5.0) "YES" else "NO")

        val here = GeoPoint(lat, lon)
        if (myMarker == null) {


            myMarker = Marker(mapView).apply {
                position = here
                title = "Your Vehicle"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(myMarker)
            mapView.controller.setZoom(17.0)
            mapView.controller.animateTo(here)

        }
        myMarker?.position = here
        mapView.controller.setCenter(here)
        adjustMapTilt(speedKmh) //dynamic camera tilt


        // Rotate map based on driving direction
        // IN updateLocationUI()

        if (location.hasBearing()) {
            val currentOrientation = mapView.mapOrientation
            val targetOrientation = -location.bearing // Map rotates to driving direction

            // Smooth map rotation
            val newOrientation = currentOrientation + (targetOrientation - currentOrientation) * 0.05f
            mapView.mapOrientation = newOrientation
            myMarker?.rotation = 0f

        }

        mapView.invalidate()

        checkPeerDistances(lat, lon, speedKmh)



    }
//Added: 3D map tilt feature
private fun adjustMapTilt(speedKmh: Double) {
    // Use map controller for projection
    val controller = mapView.controller

    // Limit tilt between 0° (still) and 60° (fast)
    val maxTilt = 60f
    val tiltFactor = (speedKmh / 120).coerceIn(0.0, 1.0)  // 0 → 120 km/h scale
    val newTilt = if (speedKmh > 10) (maxTilt * tiltFactor).toFloat() else 0f

    // Apply smooth transition to tilt
    val currentZoom = mapView.zoomLevelDouble
    val newZoom = (currentZoom + (currentZoom * 0.02)).coerceAtMost(20.0) // small zoom adjust for visual depth

    // Update tilt and zoom gradually
    mapView.mapOrientation = mapView.mapOrientation // keep current bearing
    controller.setZoom(newZoom)
    mapView.setTilt(newTilt)
}

    // ================= DISTANCE & SPEED ALERTS =================
    private fun checkPeerDistances(myLat: Double, myLon: Double, mySpeed: Double) {
        var alert = "SAFE"
        var dangerTriggered = false

        for ((_, marker) in realVehicleMarkers) {
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

        distanceAlertText.text = "${getString(R.string.distance_alert)}: $alert"

        if (dangerTriggered) {
            startActivity(Intent(this, DangerAlertActivity::class.java))
        }
        logAlertToFile(alert)
    }

    private fun calculateSafeStoppingDistance(speedKmh: Double): Double {
        val speedMs = speedKmh / 3.6
        val reactionTime = 1.5 // sec
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

    // ================= FIREBASE WRITE =================
    private var lastWriteTime = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastSpeed = Double.NaN

    private fun maybeWrite(lat: Double, lon: Double, speed: Double, braking: Boolean) {
        val now = System.currentTimeMillis()
        val moved = if (!lastLat.isNaN()) haversine(lastLat, lastLon, lat, lon) else Double.MAX_VALUE
        val speedDelta = kotlin.math.abs((lastSpeed.takeIf { !it.isNaN() } ?: speed) - speed)

        if (moved > 5 || speedDelta > 1 || now - lastWriteTime > 2000) {
            writeVehicleState(lat, lon, speed, braking)
            lastLat = lat; lastLon = lon; lastSpeed = speed; lastWriteTime = now
        }
    }

    //actualy writes to firebase after the maybe write check
    private fun writeVehicleState(lat: Double, lon: Double, speed: Double, braking: Boolean) {
        val ref = database.reference.child("vehicles").child(uid)
        val payload = mapOf<String, Any>(
            "lat" to lat,
            "lon" to lon,
            "speed" to speed,
            "braking" to braking,
            "ts" to ServerValue.TIMESTAMP,
            "deviceName" to Build.MODEL
        )
        ref.setValue(payload)
        ref.onDisconnect().removeValue() //Tells Firebase to automatically remove this vehicle’s data if the device disconnects unexpectedly (e.g., app closed, network lost).
        //This is critical for keeping the map clean of stale vehicles.
    }

    // ================= FIREBASE LISTENER =================
    private fun listenForVehicles() {
        val ref = database.reference.child("vehicles")
        ref.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleVehicleSnapshot(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                handleVehicleSnapshot(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.key ?: return
                removeMarkerFor(id)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    //handling vehicle marker updates
    private fun handleVehicleSnapshot(snapshot: DataSnapshot) {
        val id = snapshot.key ?: return
        if (id == uid) return

        val lat = snapshot.child("lat").getValue(Double::class.java) ?: return
        val lon = snapshot.child("lon").getValue(Double::class.java) ?: return
        val speed = snapshot.child("speed").getValue(Double::class.java) ?: 0.0
        val braking = snapshot.child("braking").getValue(Boolean::class.java) ?: false

        updateVehicleMarker(lat, lon, speed, braking, id)
    }

    private fun updateVehicleMarker(lat: Double, lon: Double, speed: Double, braking: Boolean, id: String) {
        val pos = GeoPoint(lat, lon)
        peerLastUpdate[id] = System.currentTimeMillis() // Update last seen timestamp, This could be used later for timeouts or removing stale vehicles.

        if (realVehicleMarkers.containsKey(id)) {
            realVehicleMarkers[id]?.position = pos // Update existing marker position. keeps it moving smoothly on the map without creating duplicates.
        } else {
            val marker = Marker(mapView).apply {
                position = pos
                title = "Peer Vehicle"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) // Anchor point at bottom center of the icon
            }
            mapView.overlays.add(marker)
            realVehicleMarkers[id] = marker // Store the new marker for future updates.
        }
        mapView.invalidate()
    }
//remove marker when vehicle leaves
    private fun removeMarkerFor(id: String) {
        realVehicleMarkers[id]?.let { mapView.overlays.remove(it) }
        realVehicleMarkers.remove(id)
        peerLastUpdate.remove(id)
        mapView.invalidate()
    }

    // ================= LOGGING ALERT INFO =================
    private fun logAlertToFile(alertMessage: String) {
        try {
            val logFile = File(getExternalFilesDir(null), "alert_logs.txt")
            logFile.appendText("${System.currentTimeMillis()}: $alertMessage\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    // ================= PEER CLEANUP =================
    private fun startPeerCleanup() {
        handler.postDelayed(object : Runnable { // Runnable to periodically check for stale vehicle markers.
            override fun run() {
                val now = System.currentTimeMillis()
                val toRemove = peerLastUpdate.filter { now - it.value > PEER_TIMEOUT }.keys
                for (id in toRemove) { // Remove stale markers from the map and internal tracking structures.
                    realVehicleMarkers[id]?.let { mapView.overlays.remove(it) }
                    realVehicleMarkers.remove(id)
                    peerLastUpdate.remove(id)
                }
                mapView.invalidate()
                handler.postDelayed(this, 5000) // Schedule the next check after 5 seconds.
            }
        }, 5000) // Initial delay before the first check.
    }





    // ================= LIFECYCLE =================
    override fun onResume() { // Android lifecycle method called when the activity comes to the foreground.
        super.onResume()
        mapView.onResume()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            if (!::locationCallback.isInitialized) {
                ensureSignedIn()
            }
        } else {
            requestAllPermissions()
        }
    }


    override fun onPause() { // Android lifecycle method called when the activity goes to the background. Runs when the activity is partially hidden (e.g., user switches to another app
        super.onPause()
        mapView.onPause() // Pause the map view to conserve resources when the activity is not in the foreground.
    }

    override fun onDestroy() { // Android lifecycle method called when the activity is about to be destroyed. This is the final cleanup step before the activity is removed from memory.
        super.onDestroy()
        mapView.onDetach()// Clean up the map view to prevent memory leaks when the activity is destroyed.

        if (::locationCallback.isInitialized) { // Check if locationCallback has been initialized to avoid null reference errors.
            fusedLocationClient.removeLocationUpdates(locationCallback) // Stop receiving location updates to conserve battery and resources.
        }

        Firebase.database.reference.child("vehicles").child(uid).removeValue() // Remove this vehicle's data from Firebase when the activity is destroyed to keep the database clean.
    }

}

private fun MapView.setTilt(tiltAngle: Float) {
    // Limit tilt between 0° and 60°
    val clampedTilt = tiltAngle.coerceIn(0f, 60f)

    // Convert tilt to a scaleY factor (1.0 = flat, 0.5 = deep tilt)
    val scaleY = 1f - (clampedTilt / 100f)

    // Apply smooth 3D perspective effect
    this.animate()
        .scaleY(scaleY)
        .setDuration(500)
        .setUpdateListener { invalidate() }
        .start()
}

