plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation("javax.inject:javax.inject:1")
    implementation(libs.coroutines.core)
    implementation(libs.snakeyaml)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
