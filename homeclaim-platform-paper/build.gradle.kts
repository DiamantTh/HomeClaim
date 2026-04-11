import groovy.json.JsonSlurper
import java.net.URI
import java.net.URL

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    implementation(project(":homeclaim-core"))
    implementation(project(":homeclaim-liftlink"))
    implementation(project(":homeclaim-api"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.flywaydb:flyway-core:10.10.0")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    implementation("com.electronwill.night-config:toml:3.8.3")
    
    // FAWE - Optional but recommended for fast block operations
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.12.3")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.12.3") { isTransitive = false }
}

// Optimized Shadow JAR instead of fatJar
tasks.shadowJar {
    archiveBaseName.set("HomeClaim")
    archiveClassifier.set("")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("out"))
    
    // Exclude template files, docs and signatures (saves more MB)
    exclude("handlebars/**")
    exclude("JavaJaxRS/**") 
    exclude("JavaSpring/**")
    exclude("ze-ph/**")
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
    exclude("META-INF/DEPENDENCIES", "META-INF/NOTICE*", "META-INF/LICENSE*")
    exclude("**/*.kotlin_metadata", "**/*.kotlin_builtins")
    exclude("**/*.proto")        // Protocol Buffers
    exclude("**/*.md")           // Markdown docs
    exclude("**/*.txt")          // Text files
    exclude("DebugProbesKt.bin") // Kotlin coroutines debug
    exclude("module-info.class") // Java 9+ modules
    exclude("**/package.html")   // Javadoc
    exclude("**/overview.html")  // Javadoc
    
    // Big files we found
    exclude("org/bouncycastle/pqc/crypto/picnic/**")  // ~2MB Post-Quantum Crypto
    exclude("samples/**")                             // Sample JSON files 
    exclude("flash/**")                               // Flash/Flex artifacts ~1.5MB
    exclude("assets/report/**")                       // Report assets
    exclude("htmlDocs2/**")                           // HTML doc templates
    exclude("handlebars-*.js")                        // Handlebars JS (179KB)
    exclude("swagger-static/**")                      // Swagger UI assets ~500KB
    exclude("**/jansi.dll", "**/jansi.so")           // Native libraries
    exclude("**/OpenAPIDeserializer.class")          // Large swagger class
    exclude("io/swagger/codegen/**")                  // Swagger codegen ~1MB
    exclude("kotlinx/html/**")                        // KotlinX HTML DSL ~1.5MB
    
    // Version files
    archiveVersion.set("")
    
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Keep subproject build outputs intact so test/IDE classpaths and incremental Kotlin compilation remain stable.
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

val minecraftVersion = "1.21.11"

fun openUrl(url: URL): java.io.InputStream {
    val conn = url.openConnection()
    conn.setRequestProperty("User-Agent", "HomeClaim-Gradle")
    return conn.getInputStream()
}

fun latestBuild(project: String, version: String): Int {
    val url = URI("https://api.papermc.io/v2/projects/$project/versions/$version").toURL()
    val json = JsonSlurper().parse(openUrl(url)) as Map<*, *>
    val builds = json["builds"] as List<*>
    return builds.last() as Int
}

fun downloadServer(project: String, version: String, dest: java.io.File) {
    val build = latestBuild(project, version)
    val fileName = "$project-$version-$build.jar"
    val url = URI("https://api.papermc.io/v2/projects/$project/versions/$version/builds/$build/downloads/$fileName").toURL()
    dest.parentFile.mkdirs()
    openUrl(url).use { input ->
        dest.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

val runPaperDir = layout.projectDirectory.dir("run/paper")
val runFoliaDir = layout.projectDirectory.dir("run/folia")

val paperJar = runPaperDir.file("paper-$minecraftVersion.jar").asFile
val foliaJar = runFoliaDir.file("folia-$minecraftVersion.jar").asFile

val copyPluginToPaper = tasks.register<Copy>("copyPluginToPaper") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.map { it.archiveFile })
    into(runPaperDir.dir("plugins"))
    rename { "HomeClaim.jar" }
}

val copyPluginToFolia = tasks.register<Copy>("copyPluginToFolia") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.map { it.archiveFile })
    into(runFoliaDir.dir("plugins"))
    rename { "HomeClaim.jar" }
}

val downloadPaper = tasks.register("downloadPaper") {
    outputs.file(paperJar)
    doLast { downloadServer("paper", minecraftVersion, paperJar) }
}

val downloadFolia = tasks.register("downloadFolia") {
    outputs.file(foliaJar)
    doLast { downloadServer("folia", minecraftVersion, foliaJar) }
}

val eulaPaper = tasks.register("eulaPaper") {
    val file = runPaperDir.file("eula.txt").asFile
    outputs.file(file)
    doLast {
        file.parentFile.mkdirs()
        file.writeText("eula=true\n")
    }
}

val eulaFolia = tasks.register("eulaFolia") {
    val file = runFoliaDir.file("eula.txt").asFile
    outputs.file(file)
    doLast {
        file.parentFile.mkdirs()
        file.writeText("eula=true\n")
    }
}

val setupOpsJson = tasks.register("setupOpsJson") {
    val opsPaper = runPaperDir.file("ops.json").asFile
    val opsFolia = runFoliaDir.file("ops.json").asFile
    outputs.files(opsPaper, opsFolia)
    doLast {
        val opsContent = """[
  {
    "uuid": "2054054e-b7a9-4a86-bd32-effd6982b880",
    "name": "DiamantTh",
    "level": 4,
    "bypassesPlayerLimit": false
  }
]
"""
        opsPaper.parentFile.mkdirs()
        opsFolia.parentFile.mkdirs()
        if (!opsPaper.exists()) {
            opsPaper.writeText(opsContent)
            println("Created $opsPaper")
        }
        if (!opsFolia.exists()) {
            opsFolia.writeText(opsContent)
            println("Created $opsFolia")
        }
    }
}

tasks.register<Exec>("runPaper") {
    group = "run server"
    description = "Run a Paper server for $minecraftVersion"
    dependsOn(downloadPaper, eulaPaper, setupOpsJson, copyPluginToPaper)
    workingDir = runPaperDir.asFile
    commandLine("java", "-Dcom.mojang.eula.agree=true", "-jar", paperJar.name, "nogui")
}

tasks.register<Exec>("runFolia") {
    group = "run server"
    description = "Run a Folia server for $minecraftVersion"
    dependsOn(downloadFolia, eulaFolia, setupOpsJson, copyPluginToFolia)
    workingDir = runFoliaDir.asFile
    commandLine("java", "-Dcom.mojang.eula.agree=true", "-jar", foliaJar.name, "nogui")
}
