package com.example.v2vaudi

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Firebase once per app launch
        FirebaseApp.initializeApp(this)

        // Enable offline persistence for Realtime Database
        try {
            Firebase.database.setPersistenceEnabled(true)
        } catch (_: Exception) {
            // Ignore if already enabled
        }
    }
}
