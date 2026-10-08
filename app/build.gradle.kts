import java.util.Properties

plugins {
    id("com.android.application")
}

val signingProps = Properties().also {
    it.load(rootProject.file("signing.properties").inputStream())
}

android {
    namespace = "com.hnedk.dfroot"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hnedk.dfroot"
        minSdk = 32
        targetSdk = 37
        versionCode = 400
        versionName = "4.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(signingProps.getProperty("KEYSTORE_FILE"))
            storePassword = signingProps.getProperty("KEYSTORE_PASSWORD")
            keyAlias = signingProps.getProperty("KEY_ALIAS")
            keyPassword = signingProps.getProperty("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        checkReleaseBuilds = false
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { vn -> "DFRoot_${vn}.apk" })
        }
    }
}

dependencies {
}
