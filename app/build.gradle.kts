plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 namespace="com.zhikanyeye.pdfoca"
 compileSdk=35
 defaultConfig {
  applicationId="com.zhikanyeye.pdfoca"
  minSdk=26
  targetSdk=35
  versionCode=71
  versionName="0.7.1"
 }
 buildFeatures { buildConfig=true }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.compose.ui:ui:1.7.6")
 implementation("androidx.compose.material3:material3:1.3.1")
 implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
 implementation("androidx.security:security-crypto:1.1.0-alpha06")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("com.tom-roush:pdfbox-android:2.0.27.0")
 implementation("androidx.documentfile:documentfile:1.0.1")
 debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
}
