package com.example.v2vaudi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class SplashActivity : AppCompatActivity() {

    private val splashTime: Long = 2000 // total splash duration
    private lateinit var welcomeText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_splash)

        super.onCreate(savedInstanceState)

        val logo = findViewById<ImageView>(R.id.logo)
        welcomeText = findViewById(R.id.welcomeText)

        // Load user's username
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            val uid = user.uid
            // Fetch username from Firebase Realtime Database
            val ref = FirebaseDatabase.getInstance().getReference("users").child(uid)
            ref.child("username").get().addOnSuccessListener {
                snapshot -> // Successfully got data
                val username = snapshot.value?.toString() ?: "User"
                welcomeText.text = "Welcome, $username"
            }.addOnFailureListener {
                welcomeText.text = "Welcome!"
            }
        } else {
            welcomeText.text = "Welcome!"
        }

        // Animation flow
        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        logo.startAnimation(fadeIn)

        Handler(Looper.getMainLooper()).postDelayed({
            val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out)
            logo.startAnimation(fadeOut)

            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()
            }, 500)
        }, splashTime)
    }
}
