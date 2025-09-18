package com.example.v2vaudi

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.database.database

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        try {
            Firebase.database.setPersistenceEnabled(true) // enable offline caching
        } catch (_: Exception) {
            // ignore if already enabled
        }
    }
}
