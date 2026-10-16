buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself; pin the Kotlin version that miuix 0.9.4 is built with.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
