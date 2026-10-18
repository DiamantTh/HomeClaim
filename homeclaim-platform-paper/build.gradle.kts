import groovy.json.JsonSlurper
import java.util.zip.ZipFile
import java.net.URI
import java.net.URL

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow") version "8.3.5"
}
val faweVersion = "2.12.3"
val faweRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":homeclaim-core"))
    implementation(project(":homeclaim-liftlink"))
    implementation(project(":homeclaim-api"))
    implementation(project(":homeclaim-webux"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
    implementation("org.flywaydb:flyway-core:10.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.10.0")
    implementation("org.flywaydb:flyway-mysql:10.10.0")
    implementation("com.github.luben:zstd-jni:1.5.7-23")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    runtimeOnly("org.postgresql:postgresql:42.7.7")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    runtimeOnly("com.mysql:mysql-connector-j:9.3.0")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    implementation("com.electronwill.night-config:toml:3.8.3")
    
    // FAWE is optional at runtime and is only required by the existing-world conversion wizard.
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweVersion")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:$faweVersion") { isTransitive = false }
    faweRuntime("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:$faweVersion") { isTransitive = false }
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
    exclude("**/*.proto")        // Protocol Buffers
    exclude("**/*.md")           // Markdown docs
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
    mergeServiceFiles()
    manifest { attributes["Multi-Release"] = "true" }

    // Keep subproject build outputs intact so test/IDE classpaths and incremental Kotlin compilation remain stable.

    // Jackson's Kotlin module and kotlin-reflect load these built-ins at runtime.
    // Excluding them produces an apparently healthy plugin whose REST responses fail to serialize.
    doLast {
        ZipFile(archiveFile.get().asFile).use { jar ->
            val requiredKotlinBuiltins = listOf(
                "kotlin/kotlin.kotlin_builtins",
                "kotlin/collections/collections.kotlin_builtins"
            )
            val missing = requiredKotlinBuiltins.filter { jar.getEntry(it) == null }
            check(missing.isEmpty()) {
                "Shaded HomeClaim JAR is missing Kotlin runtime resources: ${missing.joinToString()}"
            }
        }
    }
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

val minecraftVersion = "26.3"

fun openUrl(url: URL): java.io.InputStream {
    val conn = url.openConnection()
    conn.setRequestProperty("User-Agent", "HomeClaim-Gradle/0.1.0 (https://github.com/DiamantTh/HomeClaim)")
    return conn.getInputStream()
}

fun downloadServer(project: String, version: String, dest: java.io.File) {
    val buildsUrl = URI("https://fill.papermc.io/v3/projects/$project/versions/$version/builds").toURL()
    val builds = JsonSlurper().parse(openUrl(buildsUrl)) as List<*>
    val build = builds.asSequence()
        .map { it as Map<*, *> }
        .firstOrNull { it["channel"] == "STABLE" }
        ?: builds.asSequence()
            .map { it as Map<*, *> }
            .firstOrNull { it["channel"] == "BETA" }
        ?: throw GradleException("No stable or beta $project build is available for Minecraft $version")
    val serverDownload = ((build["downloads"] as? Map<*, *>)?.get("server:default") as? Map<*, *>)
        ?: throw GradleException("Paper build ${build["id"]} has no default server download")
    val url = URI(serverDownload["url"] as String).toURL()
    dest.parentFile.mkdirs()
    openUrl(url).use { input ->
        dest.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    println("Downloaded $project $version build ${build["id"]} to $dest")
}

fun latestFawePaperArtifact(): Pair<String, String> {
    val baseUrl = "https://ci.athion.net/view/%20%20FastAsyncWorldEdit/job/FastAsyncWorldEdit/lastSuccessfulBuild"
    val url = URI("$baseUrl/api/json?tree=artifacts[fileName,relativePath]").toURL()
    val json = JsonSlurper().parse(openUrl(url)) as Map<*, *>
    val artifacts = json["artifacts"] as List<*>
    val artifact = artifacts
        .map { it as Map<*, *> }
        .first { (it["fileName"] as String).startsWith("FastAsyncWorldEdit-Paper-") && (it["fileName"] as String).endsWith(".jar") }
    return baseUrl to artifact["relativePath"] as String
}

fun downloadFawePaper(dest: java.io.File) {
    val (baseUrl, relativePath) = latestFawePaperArtifact()
    val url = URI("$baseUrl/artifact/$relativePath").toURL()
    dest.parentFile.mkdirs()
    openUrl(url).use { input ->
        dest.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

val runPaperDir = layout.projectDirectory.dir("run/paper")

val paperJar = runPaperDir.file("paper-$minecraftVersion.jar").asFile

val copyPluginToPaper = tasks.register<Copy>("copyPluginToPaper") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.map { it.archiveFile })
    into(runPaperDir.dir("plugins"))
    rename { "HomeClaim.jar" }
}

val copyFaweToPaper = tasks.register<Copy>("copyFaweToPaper") {
    from(faweRuntime)
    into(runPaperDir.dir("plugins"))
    rename { "FastAsyncWorldEdit-Bukkit.jar" }
}

val downloadFaweToPaper = tasks.register("downloadFaweToPaper") {
    val faweJar = runPaperDir.file("plugins/FastAsyncWorldEdit.jar").asFile
    outputs.file(faweJar)
    outputs.upToDateWhen { false }
    doFirst {
        delete(runPaperDir.file("plugins/.paper-remapped/FastAsyncWorldEdit.jar"))
        delete(runPaperDir.file("plugins/.paper-remapped/FastAsyncWorldEdit-Bukkit.jar"))
        delete(runPaperDir.file("plugins/FastAsyncWorldEdit-Bukkit.jar"))
    }
    doLast {
        downloadFawePaper(faweJar)
        println("Downloaded FAWE to $faweJar")
    }
}

val downloadPaper = tasks.register("downloadPaper") {
    outputs.file(paperJar)
    doLast { downloadServer("paper", minecraftVersion, paperJar) }
}

val eulaPaper = tasks.register("eulaPaper") {
    val file = runPaperDir.file("eula.txt").asFile
    outputs.file(file)
    doLast {
        file.parentFile.mkdirs()
        file.writeText("eula=true\n")
    }
}

val setupOpsJson = tasks.register("setupOpsJson") {
    val opsPaper = runPaperDir.file("ops.json").asFile
    outputs.file(opsPaper)
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
        if (!opsPaper.exists()) {
            opsPaper.writeText(opsContent)
            println("Created $opsPaper")
        }
    }
}

val cleanRunPaperHomeClaimData = tasks.register("cleanRunPaperHomeClaimData") {
    group = "run server"
    description = "Remove HomeClaim plugin data before starting the Paper dev server"
    doLast {
        delete(runPaperDir.dir("plugins/HomeClaim"))
    }
}

tasks.register<Exec>("runPaper") {
    group = "run server"
    description = "Run a Paper server for $minecraftVersion"
    dependsOn(downloadPaper, eulaPaper, setupOpsJson, cleanRunPaperHomeClaimData, copyPluginToPaper)
    workingDir = runPaperDir.asFile
    commandLine("java", "-Dcom.mojang.eula.agree=true", "-jar", paperJar.name, "nogui")
}
