import java.util.Random
import java.util.function.BinaryOperator
import java.util.function.IntFunction

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "com.obs.yl"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/services/java.net.spi.InetAddressResolverProvider",
                "META-INF/services/sun.net.spi.nameservice.NameServiceDescriptor"
            )
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("$rootDir/11.jks")
            storePassword = "111111"
            keyAlias = "key0"
            keyPassword = "111111"
        }
    }

    defaultConfig {
        applicationId = randomPackage()
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }

        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.net)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
    implementation(libs.serialize)

    implementation("dnsjava:dnsjava:3.6.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

fun randomPackage(): String {
    val random = Random()
    val result = random.ints(3, 3, 6).mapToObj(object : IntFunction<String> {
        override fun apply(length: Int): String {
            val sb = StringBuilder()
            for (i in 0..length) {
                sb.append((97 + random.nextInt(26)).toChar())
            }
            return sb.toString()
        }
    }).reduce("com", object : BinaryOperator<String> {
        override fun apply(left: String, right: String): String {
            return "$left.$right"
        }
    })

    println("result=$result")
    return result
}