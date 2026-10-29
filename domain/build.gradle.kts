plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Annotation-only JAR so the models can be Room entities without the domain module depending on Android.
    api(libs.androidx.room.common)
    testImplementation(libs.junit)
}
