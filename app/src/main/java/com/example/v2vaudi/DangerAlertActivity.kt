package com.example.v2vaudi

import android.Manifest
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.v2vaudi.R
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import androidx.annotation.RequiresPermission

class DangerAlertActivity : AppCompatActivity() {

    private lateinit var mediaPlayer: MediaPlayer
    private var dangerVibrator: Vibrator? = null
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var dangerText: TextView

    @RequiresPermission(Manifest.permission.VIBRATE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make this activity full screen and keep screen on
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_danger_alert)

        rootLayout = findViewById(R.id.dangerRoot)
        dangerText = findViewById(R.id.dangerText)

        // 🔴 Flash background color
        startFlashingBackground()

        // 🔊 Play looping alarm sound (place "alert_sound.mp3" in res/raw/)
        mediaPlayer = MediaPlayer.create(this, R.raw.alert_sound)
        mediaPlayer.isLooping = true
        mediaPlayer.start()

        // 📳 Vibrate in repeating waveform
        dangerVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 250, 120, 250, 120, 400) // triple buzz
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dangerVibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // repeat forever
        } else {
            @Suppress("DEPRECATION")
            dangerVibrator?.vibrate(pattern, 0)
        }

        // Auto-close after 10s if not dismissed
        rootLayout.postDelayed({ finish() }, 10_000)
    }

    private fun startFlashingBackground() {
        val colorAnim = ValueAnimator.ofObject(
            ArgbEvaluator(),
            Color.RED,
            Color.BLACK
        )
        colorAnim.duration = 600 // ms per cycle
        colorAnim.repeatMode = ValueAnimator.REVERSE
        colorAnim.repeatCount = ValueAnimator.INFINITE
        colorAnim.addUpdateListener { animator ->
            rootLayout.setBackgroundColor(animator.animatedValue as Int)
            dangerText.setTextColor(Color.WHITE)
        }
        colorAnim.start()
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaPlayer.stop()
            mediaPlayer.release()
        } catch (_: Exception) {}

        try {
            dangerVibrator?.cancel()
        } catch (_: Exception) {}
    }
}
