package com.example.v2vaudi

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.v2vaudi.databinding.ActivitySignupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        binding.signupButton.setOnClickListener {
            val firstName = binding.signupFirstName.text.toString().trim()
            val lastName = binding.signupLastName.text.toString().trim()
            val username = binding.signupUsername.text.toString().trim()
            val phone = binding.signupPhone.text.toString().trim()
            val email = binding.signupEmail.text.toString().trim()
            val password = binding.signupPassword.text.toString().trim()

            // Basic input validation
            if (firstName.isEmpty() || lastName.isEmpty() || username.isEmpty() ||
                phone.isEmpty() || email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Create Firebase user
            firebaseAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val userId = firebaseAuth.currentUser?.uid
                        if (userId != null) {
                            val userRef = database.getReference("users").child(userId)
                            val userMap = mapOf(
                                "firstName" to firstName,
                                "lastName" to lastName,
                                "username" to username,
                                "phone" to phone,
                                "email" to email
                            )
                            userRef.setValue(userMap)
                        }

                        Toast.makeText(this, "Signup Successful", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(
                            this,
                            "Signup Failed: ${task.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        binding.loginText.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}


//package com.example.v2vaudi
//
//import android.content.Intent
//import android.os.Bundle
//import android.widget.Toast
//import androidx.activity.enableEdgeToEdge
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.view.ViewCompat
//import androidx.core.view.WindowInsetsCompat
//import com.example.v2vaudi.databinding.ActivitySignupBinding
//import com.google.firebase.auth.FirebaseAuth
//
//class SignupActivity : AppCompatActivity() {
//
//    private lateinit var binding: ActivitySignupBinding
//    private lateinit var firebaseAuth: FirebaseAuth
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        binding = ActivitySignupBinding.inflate(layoutInflater)
//        enableEdgeToEdge()
//        setContentView(binding.root)
//
//        firebaseAuth = FirebaseAuth.getInstance()
//
//        binding.signupButton.setOnClickListener {
//            val email = binding.signupEmail.text.toString()
//            val password = binding.signupPassword.text.toString()
//
//            if (email.isNotEmpty() && password.isNotEmpty()){
//                firebaseAuth.createUserWithEmailAndPassword(email, password)
//                    .addOnCompleteListener(this) { task ->
//                       if (task.isSuccessful){
//                           Toast.makeText(this, "Signup Successful", Toast.LENGTH_SHORT).show()
//                           val intent = Intent(this, LoginActivity::class.java)
//                           startActivity(intent)
//                           finish()
//                       } else{
//                           Toast.makeText(this, "Signup Unsuccessful", Toast.LENGTH_SHORT).show()
//
//                       }
//                    }
//            } else{
//                Toast.makeText(this, "Enter Email and Password", Toast.LENGTH_SHORT).show()
//
//            }
//
//        }
//
//        binding.loginText.setOnClickListener {
//            startActivity(Intent(this, LoginActivity::class.java))
//            finish()
//        }
//
//
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }
//    }
//}