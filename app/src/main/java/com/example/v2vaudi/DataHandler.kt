package com.example.v2vaudi

import android.util.Log
import org.json.JSONObject

class DataHandler(private val mainActivity: MainActivity) {

    /**
     * Parse incoming JSON string from a peer
     * Example:
     * { "lat": -1.2921, "lon": 36.8219, "speed": 45.2, "braking": true }
     */
    fun parseJson(json: String) {
        try {
            val obj = JSONObject(json)
            val lat = obj.getDouble("lat")
            val lon = obj.getDouble("lon")
            val speed = obj.getDouble("speed")
            val braking = obj.getBoolean("braking")

            // ✅ Update peer marker in MainActivity
            mainActivity.runOnUiThread {
                mainActivity.updateVehicleMarker(lat, lon, speed, braking)
            }

        } catch (e: Exception) {
            Log.e("DataHandler", "Error parsing JSON: ${e.message}")
        }
    }

    /**
     * Create JSON string to send my vehicle data
     * - lat/lon in degrees
     * - speed in km/h
     * - braking: true/false
     */
    fun createJson(lat: Double, lon: Double, speedKmh: Double, braking: Boolean): String {
        return try {
            val obj = JSONObject()
            obj.put("lat", lat)
            obj.put("lon", lon)
            obj.put("speed", speedKmh)
            obj.put("braking", braking)
            obj.toString()
        } catch (e: Exception) {
            Log.e("DataHandler", "Error creating JSON: ${e.message}")
            "{}"
        }
    }
}
