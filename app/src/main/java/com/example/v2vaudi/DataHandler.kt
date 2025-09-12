package com.example.v2vaudi

import android.util.Log
import org.json.JSONObject

class DataHandler(private val activity: MainActivity) {

    /**
     * Create JSON string from local vehicle data
     */
    fun createJson(lat: Double, lon: Double, speed: Double, braking: Boolean): String {
        return JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("speed", speed)
            put("braking", braking)
        }.toString()
    }

    /**
     * Parse JSON received from peer and update UI
     */
    fun parseJson(json: String) {
        try {
            val obj = JSONObject(json)
            val lat = obj.getDouble("lat")
            val lon = obj.getDouble("lon")
            val speed = obj.getDouble("speed")
            val braking = obj.getBoolean("braking")

            // Call into MainActivity (on UI thread)
            activity.runOnUiThread {
                activity.updateVehicleMarker(lat, lon, speed, braking)
            }

        } catch (e: Exception) {
            Log.e("DataHandler", "JSON parse error: ${e.message}")
        }
    }
}
