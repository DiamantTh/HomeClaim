plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":homeclaim-core"))
    api("io.ktor:ktor-server-core:2.3.12")
    api("io.ktor:ktor-server-netty:2.3.12")
    api("io.ktor:ktor-server-content-negotiation:2.3.12")
    api("io.ktor:ktor-serialization-jackson:2.3.12")
    api("io.ktor:ktor-server-swagger:2.3.12")
    api("io.ktor:ktor-server-openapi:2.3.12")
    api("io.ktor:ktor-server-cors:2.3.12")
    api("io.ktor:ktor-server-status-pages:2.3.12")
    api("io.ktor:ktor-server-compression:2.3.12")
    implementation("com.github.luben:zstd-jni:1.5.7-23")
    implementation("com.aayushatharva.brotli4j:brotli4j:1.23.0")
    runtimeOnly("com.aayushatharva.brotli4j:native-linux-x86_64:1.23.0")
    runtimeOnly("com.aayushatharva.brotli4j:native-linux-aarch64:1.23.0")
    runtimeOnly("com.aayushatharva.brotli4j:native-windows-x86_64:1.23.0")
    runtimeOnly("com.aayushatharva.brotli4j:native-osx-x86_64:1.23.0")
    runtimeOnly("com.aayushatharva.brotli4j:native-osx-aarch64:1.23.0")
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
}
