import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Impostazioni locali, mai nel repository: local.properties (escluso da Git) oppure -P da riga di comando.
//   firelauncher.nasUrl      indirizzo del servizio trailer sul NAS (vuoto = modalità YouTube diretta)
//   firelauncher.tmdbKey     chiave TMDB per la modalità YouTube (si può anche inserire dalle impostazioni)
//   firelauncher.versionCode numero di versione (lo imposta GitHub Actions)
//   firelauncher.ciRelease   true nelle build pubblicate su GitHub: attiva gli aggiornamenti dall'app
//   firelauncher.keystore / .keystorePassword / .keyAlias / .keyPassword   firma delle release
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun prop(name: String): String =
    (project.findProperty(name) as String?) ?: localProps.getProperty(name) ?: System.getenv(
        name.uppercase().replace('.', '_')
    ) ?: ""
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.firelauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.firelauncher"
        minSdk = 25
        targetSdk = 34
        versionCode = prop("firelauncher.versionCode").toIntOrNull() ?: 1
        versionName = "1.0.$versionCode"

        buildConfigField("String", "DEFAULT_NAS_URL", quoted(prop("firelauncher.nasUrl")))
        buildConfigField("String", "TMDB_KEY", quoted(prop("firelauncher.tmdbKey")))
        buildConfigField("boolean", "UPDATES_ENABLED", (prop("firelauncher.ciRelease") == "true").toString())
        buildConfigField("String", "GITHUB_REPO", quoted("DanieleOwner/FireLauncher"))
    }

    val keystore = prop("firelauncher.keystore")
    signingConfigs {
        if (keystore.isNotBlank()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = prop("firelauncher.keystorePassword")
                keyAlias = prop("firelauncher.keyAlias")
                keyPassword = prop("firelauncher.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Senza keystore (build sul proprio PC) si firma con la chiave di debug locale.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor usa java.time e altre API recenti: su Fire OS (Android 7.1) servono in versione "desugared".
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // Modalità senza NAS: ricava gli indirizzi dei video YouTube (aggiornata da Dependabot).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
}
