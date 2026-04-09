plugins {
    kotlin("jvm")
}

dependencies {
    // Core remains lean; standard Kotlin + SLF4J for future logging hooks.
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    
    // Crypto (Bouncy Castle for Argon2id, BLAKE2b)
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
}
