package com.example.v2vaudi

import org.json.JSONObject

class DataHandler(private val activity: MainActivity) {

    fun createJson(lat: Double, lon: Double, speedKmh: Double, braking: Boolean): String {
        val obj = JSONObject()
        obj.put("lat", lat)
        obj.put("lon", lon)
        obj.put("speed", speedKmh)
        obj.put("braking", braking)
        return obj.toString()
    }

    fun parseJson(data: String) {
        try {
            val obj = JSONObject(data)
            val lat = obj.getDouble("lat")
            val lon = obj.getDouble("lon")
            val speed = obj.getDouble("speed")
            val braking = obj.getBoolean("braking")

            activity.runOnUiThread {
                activity.updateVehicleMarker(lat, lon, speed, braking)
            }
        } catch (_: Exception) {
            // ignore malformed lines
        }
    }
}
