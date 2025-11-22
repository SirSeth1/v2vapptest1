package com.example.v2vaudi

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.io.File

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        loadUserData()

        // Download logs button
        findViewById<Button>(R.id.btnDownloadLogs).setOnClickListener {
            exportLogs()
        }
 //logout button
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            auth.signOut()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish() // optional, since CLEAR_TASK already clears the stack
        }

    }

    private fun loadUserData() {
        val userId = auth.currentUser?.uid ?: return
        val userRef = database.getReference("users").child(userId)

        userRef.get().addOnSuccessListener { snapshot ->
            findViewById<TextView>(R.id.txtFirstName).text = "Firstname: " + snapshot.child("firstName").value.toString()
            findViewById<TextView>(R.id.txtLastName).text = "Lastname: " + snapshot.child("lastName").value.toString()
            findViewById<TextView>(R.id.txtUsername).text = "Username: " + snapshot.child("username").value.toString()
            findViewById<TextView>(R.id.txtPhone).text = "Phone: " + snapshot.child("phone").value.toString()
            findViewById<TextView>(R.id.txtEmail).text = "Email: " + snapshot.child("email").value.toString()
        }
    }

    private fun exportLogs() {
        val file = File(filesDir, "alert_logs.txt")

        if (!file.exists()) {
            Toast.makeText(this, "No alerts logged yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Share Alert Logs"))
    }
}
