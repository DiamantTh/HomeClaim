plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":homeclaim-core"))
    api(project(":homeclaim-api"))
    
    // Pebble Templates
    api("io.pebbletemplates:pebble:3.2.2")
    
    // Ktor for serving web assets (optional, if not using Netty from API)
    api("io.ktor:ktor-server-html-builder:2.3.12")
    
    // WebSocket client (for Alpine.js integration)
    api("io.ktor:ktor-server-websockets:2.3.12")
}
