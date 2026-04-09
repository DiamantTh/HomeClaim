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
}
