package com.example.v2vaudi

import android.media.MediaPlayer
import android.os.Bundle
import android.view.animation.AlphaAnimation
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class EmergencyBrakeActivity : AppCompatActivity() {

    private lateinit var mediaPlayer: MediaPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emergency_brake)

        val warningText = findViewById<TextView>(R.id.emergencyText)

        // Flashing effect
        val flash = AlphaAnimation(0.2f, 1.0f)
        flash.duration = 400
        flash.repeatCount = AlphaAnimation.INFINITE
        flash.repeatMode = AlphaAnimation.REVERSE
        warningText.startAnimation(flash)

        // Play alert tone
        mediaPlayer = MediaPlayer.create(this, R.raw.alert_sound)
        mediaPlayer.isLooping = true
        mediaPlayer.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer.stop()
        mediaPlayer.release()
    }
}
