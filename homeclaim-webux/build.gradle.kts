plugins {
    kotlin("jvm")
}

val frontendDir = layout.projectDirectory.dir("frontend")
val staticResourceDir = layout.projectDirectory.dir("src/main/resources/static")
val compressedResourceDir = layout.buildDirectory.dir("generated/compressed-resources")
val npmCiMarker = frontendDir.file("node_modules/.homeclaim-npm-ci")

val npmCi = tasks.register<Exec>("npmCi") {
    group = "webux"
    description = "Install the WebUX frontend from its locked npm dependency graph."
    workingDir(frontendDir)
    inputs.files(frontendDir.file("package.json"), frontendDir.file("package-lock.json"))
    outputs.file(npmCiMarker)
    commandLine("npm", "ci")
    doLast {
        npmCiMarker.asFile.parentFile.mkdirs()
        npmCiMarker.asFile.writeText("npm ci completed\n")
    }
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    group = "webux"
    description = "Build the static SvelteKit WebUX resources."
    dependsOn(npmCi)
    workingDir(frontendDir)
    inputs.files(fileTree(frontendDir.dir("src")), frontendDir.file("package.json"), frontendDir.file("package-lock.json"),
        frontendDir.file("vite.config.ts"), frontendDir.file("svelte.config.js"), frontendDir.file("tsconfig.json"))
    outputs.dir(staticResourceDir.dir("spa"))
    commandLine("npm", "run", "build")
}

val compressStaticAssets = tasks.register<Exec>("compressStaticAssets") {
    group = "webux"
    description = "Create reproducible Brotli and Zstandard sidecars for text assets."
    dependsOn(buildFrontend)
    workingDir(frontendDir)
    inputs.files(frontendDir.file("package.json"), frontendDir.file("package-lock.json"), fileTree(staticResourceDir) {
        include("**/*.js", "**/*.mjs", "**/*.css", "**/*.html", "**/*.json", "**/*.svg", "**/*.xml", "**/*.txt", "**/*.map")
    }, frontendDir.file("scripts/compress-static-assets.mjs"))
    outputs.dir(compressedResourceDir)
    commandLine("npm", "run", "compress:static")
}

tasks.processResources {
    dependsOn(compressStaticAssets)
    from(compressedResourceDir)
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
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation("io.ktor:ktor-client-websockets:2.3.12")
    testImplementation("com.github.luben:zstd-jni:1.5.7-23")
    testImplementation("com.aayushatharva.brotli4j:brotli4j:1.23.0")
    testRuntimeOnly("com.aayushatharva.brotli4j:native-linux-x86_64:1.23.0")
    testRuntimeOnly("com.aayushatharva.brotli4j:native-linux-aarch64:1.23.0")
    testRuntimeOnly("com.aayushatharva.brotli4j:native-windows-x86_64:1.23.0")
    testRuntimeOnly("com.aayushatharva.brotli4j:native-osx-x86_64:1.23.0")
    testRuntimeOnly("com.aayushatharva.brotli4j:native-osx-aarch64:1.23.0")
}
