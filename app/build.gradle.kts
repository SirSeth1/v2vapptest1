plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.example.v2vaudi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.v2vaudi"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }



    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures{
        viewBinding = true
    }
}

//dependencies {
//    // --- Your non-Firebase dependencies ---
//    implementation(libs.androidx.cardview)
//    implementation(libs.playservices.location)
//    implementation(libs.osmdroid.android)
//    implementation(libs.play.services.maps)
//    implementation(libs.androidx.core.ktx)
//    implementation(libs.androidx.appcompat)
//    implementation(libs.material)
//    implementation(libs.androidx.activity)
//    implementation(libs.androidx.constraintlayout)
//
//    // --- Firebase Dependencies (Corrected) ---
//    // 1. Import the Firebase BOM. This manages the versions for all other Firebase libs.
//    implementation(platform(libs.firebase.bom))
//
//    // 2. Add the Firebase libraries you need.
//    //    Use the KTX versions for Kotlin-friendly extensions.
//    //    Do NOT specify a version here; the BOM handles it.
//    implementation("com.google.firebase:firebase-analytics-ktx")
//    implementation("com.google.firebase:firebase-auth-ktx")
//    implementation("com.google.firebase:firebase-database-ktx")
//
//
//    // --- Test dependencies ---
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.androidx.junit)
//    androidTestImplementation(libs.androidx.espresso.core)
//}

//

dependencies {
    // --- Core Android dependencies ---
    implementation(libs.androidx.cardview)
    implementation(libs.playservices.location)
    implementation(libs.osmdroid.android)
    implementation(libs.play.services.maps)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // --- Firebase dependencies (CLEAN & COMPLETE) ---
    // Use Firebase BOM to manage all Firebase versions automatically
    implementation(platform("com.google.firebase:firebase-bom:33.3.0"))

    // Kotlin Extensions (KTX) versions for Kotlin support
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // --- Testing dependencies ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
