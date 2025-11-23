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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import java.io.File
import androidx.core.view.WindowCompat
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable


class MainActivity : AppCompatActivity() {

    // ===== UI =====
    private lateinit var mapView: MapView
    private lateinit var speedText: TextView
    private lateinit var latitudeText: TextView
    private lateinit var longitudeText: TextView
    private lateinit var brakingStatusText: TextView
    private lateinit var distanceAlertText: TextView

    // ===== GPS =====
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // ===== Map markers =====
    private var myMarker: Marker? = null
    private val realVehicleMarkers = mutableMapOf<String, Marker>()
    private val peerLastUpdate = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())

    // ===== Emergency Brake Detection =====
    private var lastSpeedCheckTime = 0L
    private var lastSpeedRecorded = 0.0
    private var isEmergencyBrakeActive = false

    // ===== Bearing tracking (for opposite-direction filter) =====
    private var myBearing: Float? = null
    private var lastBearing: Double = Double.NaN // will be written to DB

    private fun showEmergencyBrakeScreen() {
        val intent = Intent(this, EmergencyBrakeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
    private fun resizeMarker(drawableId: Int, width: Int, height: Int): Drawable {
        val drawable = ContextCompat.getDrawable(this, drawableId)!!
        val bitmap = (drawable as BitmapDrawable).bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return BitmapDrawable(resources, scaled)
    }


    // ===== Firebase =====
    private val auth = Firebase.auth
    private val database = Firebase.database
    private val uid: String
        get() = auth.currentUser?.uid ?: "unauthenticated"

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PEER_TIMEOUT = 10_000L // 10 sec no update → remove marker
    }

    override fun onCreate(savedInstanceState: Bundle?) //std entry point of main activity
    {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        super.onCreate(savedInstanceState)

        // osmdroid cache dirs
        val basePath = File(getExternalFilesDir(null), "osmdroid")
        if (!basePath.exists()) basePath.mkdirs()

        val tileCache = File(basePath, "tiles")
        if (!tileCache.exists()) tileCache.mkdirs()

        Configuration.getInstance().osmdroidBasePath = basePath
        Configuration.getInstance().osmdroidTileCache = tileCache

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
            ensureSignedIn()
        }
    }

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
                    detectEmergencyBrake(speedKmh)

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

        // update myBearing and lastBearing (for DB writes)
        if (location.hasBearing()) {
            myBearing = location.bearing
            lastBearing = location.bearing.toDouble()
        } else {
            myBearing = null
            lastBearing = Double.NaN
        }

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
                icon = resizeMarker(R.drawable.greenicon, 60, 60)  // adjust sizes as needed

            }
            mapView.overlays.add(myMarker)
            mapView.controller.setZoom(17.0)
            mapView.controller.animateTo(here)
        }
        myMarker?.position = here
        mapView.controller.setCenter(here)
        adjustMapTilt(speedKmh) // dynamic camera tilt

        // Rotate map based on driving direction
        if (location.hasBearing()) {
            val currentOrientation = mapView.mapOrientation
            val targetOrientation = -location.bearing
            val newOrientation = currentOrientation + (targetOrientation - currentOrientation) * 0.05f
            mapView.mapOrientation = newOrientation
            myMarker?.rotation = 0f
        }

        mapView.invalidate()

        checkPeerDistances(here.latitude, here.longitude, speedKmh)
    }

    // Added: 3D map tilt feature
    private fun adjustMapTilt(speedKmh: Double) {
        val controller = mapView.controller
        val maxTilt = 60f
        val tiltFactor = (speedKmh / 120).coerceIn(0.0, 1.0)
        val newTilt = if (speedKmh > 10) (maxTilt * tiltFactor).toFloat() else 0f

        val currentZoom = mapView.zoomLevelDouble
        val newZoom = (currentZoom + (currentZoom * 0.02)).coerceAtMost(20.0)

        mapView.mapOrientation = mapView.mapOrientation
        controller.setZoom(newZoom)
        mapView.setTilt(newTilt)
    }

    // ================= DISTANCE & SPEED ALERTS =================
    private fun checkPeerDistances(myLat: Double, myLon: Double, mySpeed: Double) {
        var alert = "SAFE"
        var dangerTriggered = false
        var triggeredDist = -1.0
        var triggeredSafeDist = -1.0

        for ((_, marker) in realVehicleMarkers) {
            val peerLat = marker.position.latitude
            val peerLon = marker.position.longitude

            val dist = haversine(myLat, myLon, peerLat, peerLon)
            val safeDist = calculateSafeStoppingDistance(mySpeed)

            // If we have my bearing and peer bearing (stored separately in peer metadata map), then filter opposite-going vehicles.
            // We need to read peer bearing from the marker's object tag (if set when creating the marker).
            val peerBearingObj = marker.relatedObject // we'll use relatedObject to store peer bearing (Double) when creating the marker
            val peerBearing = when (peerBearingObj) {
                is Double -> peerBearingObj
                is Float -> peerBearingObj.toDouble()
                else -> Double.NaN
            }

            // Opposite-direction filter:
            var isOpposite = false
            if (myBearing != null && !peerBearing.isNaN()) {
                val myB = myBearing!!.toDouble()
                // difference in 0..360
                val rawDiff = ((peerBearing - myB) % 360 + 360) % 360
                // delta = minimal angle (0..180)
                val delta = if (rawDiff > 180) 360 - rawDiff else rawDiff
                // if delta ~ 180 (say >135) then vehicle is roughly opposite direction -> ignore
                if (delta > 135) {
                    isOpposite = true
                }
            }

            if (isOpposite) {
                // ignore opposite-moving vehicles (do not use them for alerts)
                continue
            }

            // geofence: already enforced when adding/updating markers, but double-check here
            if (dist > 100.0) continue

            when {
                dist <= 25.0 -> {
                    if (dist < safeDist) {
                        alert = "DANGER: BRAKE NOW!"
                        dangerTriggered = true
                        triggeredDist = dist
                        triggeredSafeDist = safeDist
                    } else {
                        if (alert != "DANGER: BRAKE NOW!") alert = "CAUTION: Close proximity"
                    }
                }

                dist in 26.0..50.0 -> {
                    if (dist < safeDist) {
                        if (alert != "DANGER: BRAKE NOW!") {
                            alert = "WARNING: TOO CLOSE"
                        }
                    } else {
                        if (alert == "SAFE") alert = "Caution: Nearby vehicle"
                    }
                }

                dist in 51.0..100.0 -> {
                    if (dist < safeDist) {
                        if (alert == "SAFE") alert = "CAUTION: Approaching Fast"
                    } else {
                        if (alert == "SAFE") alert = "Vehicle detected ahead"
                    }
                }
            }

            if (dangerTriggered) {
                val i = Intent(this, DangerAlertActivity::class.java)
                i.putExtra("actual_distance", triggeredDist)
                i.putExtra("safe_distance", triggeredSafeDist)
                startActivity(i)
                break // prevent multiple triggers
            }
        }

        distanceAlertText.text = "${getString(R.string.distance_alert)}: $alert"
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

    // Emergency brake detection
    private fun detectEmergencyBrake(currentSpeed: Double) {
        val now = System.currentTimeMillis()

        if (lastSpeedCheckTime == 0L) {
            lastSpeedCheckTime = now
            lastSpeedRecorded = currentSpeed
            return
        }

        val speedDrop = lastSpeedRecorded - currentSpeed
        val timeDelta = now - lastSpeedCheckTime

        // Speed drops > 40 km/h in < 2s => emergency
        if (!isEmergencyBrakeActive && timeDelta <= 2000 && speedDrop > 40)
        {
            isEmergencyBrakeActive = true
            broadcastEmergencyBrake()
            showEmergencyBrakeScreen()
        }

        lastSpeedCheckTime = now
        lastSpeedRecorded = currentSpeed
    }

    // firebase broadcast
    private fun broadcastEmergencyBrake() {
        val ref = database.reference.child("vehicles").child(uid)

        // Broadcast to others
        ref.child("emergencyBrake").setValue(true)

        // Auto-reset
        Handler(Looper.getMainLooper()).postDelayed({
            ref.child("emergencyBrake").setValue(false)
            isEmergencyBrakeActive = false
        }, 1000) //
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

    // actually writes to firebase after maybeWrite check
    private fun writeVehicleState(lat: Double, lon: Double, speed: Double, braking: Boolean) {
        val ref = database.reference.child("vehicles").child(uid)
        val payload = mapOf<String, Any?>(
            "lat" to lat,
            "lon" to lon,
            "speed" to speed,
            "braking" to braking,
            "ts" to ServerValue.TIMESTAMP,
            "deviceName" to Build.MODEL,
            "bearing" to (if (!lastBearing.isNaN()) lastBearing else null)
        )
        ref.setValue(payload)
        ref.onDisconnect().removeValue()
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

    // handling vehicle snapshot — read bearing if present
    private fun handleVehicleSnapshot(snapshot: DataSnapshot) {
        val id = snapshot.key ?: return
        if (id == uid) return

        val lat = snapshot.child("lat").getValue(Double::class.java) ?: return
        val lon = snapshot.child("lon").getValue(Double::class.java) ?: return
        val speed = snapshot.child("speed").getValue(Double::class.java) ?: 0.0
        val braking = snapshot.child("braking").getValue(Boolean::class.java) ?: false
        val bearing = snapshot.child("bearing").getValue(Double::class.java) ?: Double.NaN

        updateVehicleMarker(lat, lon, speed, braking, id, bearing)
    }

    private fun updateVehicleMarker(lat: Double, lon: Double, speed: Double, braking: Boolean, id: String, bearing: Double) {
        val pos = GeoPoint(lat, lon)

        // Get my location (if marker exists)
        val myPos = myMarker?.position
        if (myPos != null) {
            val dist = haversine(myPos.latitude, myPos.longitude, pos.latitude, pos.longitude)

            // Ignore markers outside 100m geofence
            if (dist > 100) {
                realVehicleMarkers[id]?.let {
                    mapView.overlays.remove(it)
                }
                realVehicleMarkers.remove(id)
                peerLastUpdate.remove(id)
                return
            }
        }

        // Update last timestamp
        peerLastUpdate[id] = System.currentTimeMillis()

        if (realVehicleMarkers.containsKey(id)) {
            realVehicleMarkers[id]?.position = pos
            // update stored bearing as relatedObject so checkPeerDistances can access it
            realVehicleMarkers[id]?.relatedObject = if (!bearing.isNaN()) bearing else null
        } else {
            val marker = Marker(mapView).apply {
                position = pos
                title = "Peer Vehicle"
                icon = resizeMarker(R.drawable.orangeicon, 50, 50)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                relatedObject = if (!bearing.isNaN()) bearing else null
            }
            mapView.overlays.add(marker)
            realVehicleMarkers[id] = marker
        }

        mapView.invalidate()
    }

    // remove marker when vehicle leaves
    private fun removeMarkerFor(id: String) {
        realVehicleMarkers[id]?.let { mapView.overlays.remove(it) }
        realVehicleMarkers.remove(id)
        peerLastUpdate.remove(id)
        mapView.invalidate()
    }

    // ================= LOGGING ALERT INFO =================
    private fun logAlertToFile(alertMessage: String) {
        try {
            // write to filesDir so FileProvider in ProfileActivity can share it
            val logFile = File(filesDir, "alert_logs.txt")
            logFile.appendText("${System.currentTimeMillis()}: $alertMessage\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ================= PEER CLEANUP =================
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

    // ================= LIFECYCLE =================
    override fun onResume() {
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

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()

        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        Firebase.database.reference.child("vehicles").child(uid).removeValue()
    }

}

private fun MapView.setTilt(tiltAngle: Float) {
    val clampedTilt = tiltAngle.coerceIn(0f, 60f)
    val scaleY = 1f - (clampedTilt / 100f)
    this.animate()
        .scaleY(scaleY)
        .setDuration(500)
        .setUpdateListener { invalidate() }
        .start()
}


