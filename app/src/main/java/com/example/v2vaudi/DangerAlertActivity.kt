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
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import androidx.annotation.RequiresPermission

class DangerAlertActivity : AppCompatActivity() {

    private lateinit var mediaPlayer: MediaPlayer
    private var dangerVibrator: Vibrator? = null
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var dangerText: TextView
    private lateinit var distanceText: TextView
    private lateinit var safeText: TextView

    @RequiresPermission(Manifest.permission.VIBRATE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen + keep screen on
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_danger_alert)

        rootLayout = findViewById(R.id.dangerRoot)
        dangerText = findViewById(R.id.dangerText)
        distanceText = findViewById(R.id.distanceText)
        safeText = findViewById(R.id.safeText)

        // 🔴 Flashing background animation
        startFlashingBackground()

        // 🔊 Looping alarm sound
        mediaPlayer = MediaPlayer.create(this, R.raw.alert_sound)
        mediaPlayer.isLooping = true
        mediaPlayer.start()

        // 📳 Vibrating waveform
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

        // 🚦 Get data from MainActivity
        val actualDistance = intent.getDoubleExtra("actual_distance", -1.0)
        val safeDistance = intent.getDoubleExtra("safe_distance", -1.0)

        if (actualDistance > 0 && safeDistance > 0) {
            distanceText.text = "Actual Distance: %.1f m".format(actualDistance)
            safeText.text = "Required Safe Distance: %.1f m".format(safeDistance)

            if (actualDistance < safeDistance) {
                dangerText.text = "DANGER: TOO CLOSE!"
                dangerText.setTextColor(Color.WHITE)
            } else {
                dangerText.text = "SAFE DISTANCE"
                dangerText.setTextColor(Color.GREEN)
            }
        } else {
            distanceText.text = "Distance data not available"
            safeText.text = ""
            dangerText.text = "⚠ Unknown Status"
            dangerText.setTextColor(Color.YELLOW)
        }

        // ⏱ Auto-close after 10s
        rootLayout.postDelayed({ finish() }, 10_000)

        // 👆 Close immediately if user taps anywhere
        rootLayout.setOnClickListener { finish() }
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
