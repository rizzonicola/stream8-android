import java.io.File
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versione: il workflow di release la passa con -PappVersionName=MAJOR.MINOR.PATCH.
// versionCode = MAJOR*10000 + MINOR*100 + PATCH, quindi cresce a ogni release (MINOR e PATCH <= 99).
val appVersionName: String = (findProperty("appVersionName") as String?) ?: "1.0.0"
val appVersionCode: Int = run {
    val match = Regex("""^(\d{1,3})\.(\d{1,2})\.(\d{1,2})$""").find(appVersionName)
        ?: throw GradleException("appVersionName deve essere MAJOR.MINOR.PATCH (trovato: '$appVersionName')")
    val (major, minor, patch) = match.destructured
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

android {
    namespace = "cc.poppi.stream8"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.poppi.stream8" // identificatore stabile: non cambiarlo dopo la prima release
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Firma release: i valori arrivano SOLO da variabili d'ambiente (GitHub Secrets nel workflow).
    // Nessuna chiave o password è presente nel repository.
    val keystoreFile: String? = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (!keystoreFile.isNullOrBlank()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Senza keystore l'APK resta "unsigned": il workflow di release rifiuta di pubblicarlo.
            if (!keystoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Tre ABI + APK universale. Niente x86 a 32 bit.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // Guava è usata esclusivamente per InternetDomainName (Public Suffix List incorporata).
    implementation("com.google.guava:guava:33.3.1-android")

    testImplementation("junit:junit:4.13.2")
}

// Copia gli APK release in <root>/build/dist con i nomi Stream8-<abi>.apk e fallisce se ne manca uno.
val expectedApkNames = listOf(
    "Stream8-arm64-v8a.apk",
    "Stream8-armeabi-v7a.apk",
    "Stream8-x86_64.apk",
    "Stream8-universal.apk",
)
val distDir = rootProject.layout.buildDirectory.dir("dist")

tasks.register<Copy>("collectReleaseApks") {
    group = "distribution"
    description = "Compila la release e raccoglie i 4 APK in build/dist (Stream8-<abi>.apk)."
    dependsOn("assembleRelease")

    val nameRegex = Regex("""app-(universal|arm64-v8a|armeabi-v7a|x86_64)-release(-unsigned)?\.apk""")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        eachFile {
            val match = nameRegex.matchEntire(name)
            if (match == null) exclude() else name = "Stream8-${match.groupValues[1]}.apk"
        }
    }
    into(distDir)

    val dist = distDir
    doFirst { dist.get().asFile.deleteRecursively() }
    doLast {
        val dir = dist.get().asFile
        val missing = expectedApkNames.filter { !File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("APK mancanti in ${dir.path}: ${missing.joinToString()}")
        }
        logger.lifecycle("APK pronti in ${dir.path}: ${expectedApkNames.joinToString()}")
    }
}
