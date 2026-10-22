import groovy.json.JsonSlurper
import java.util.zip.ZipFile
import java.net.URI
import java.net.URL
import java.net.InetAddress
import java.net.ServerSocket

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

tasks.register<Test>("databaseIntegrationTest") {
    group = "verification"
    description = "Run SQLite, PostgreSQL 17.11, and MariaDB 11.4 runtime persistence checks"
    outputs.upToDateWhen { false }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("*DatabaseDialectRuntimeTest")
    }
    systemProperty("homeclaim.database.integration.required", "true")
    useJUnitPlatform()
}

fun registerDatabaseProcessRestartTask(taskName: String, phase: String) = tasks.register<Test>(taskName) {
    group = "verification"
    description = "Verify PostgreSQL and MariaDB data survives a real database process restart ($phase phase)"
    outputs.upToDateWhen { false }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*DatabaseProcessRestartRuntimeTest") }
    systemProperty("homeclaim.database.process.restart.required", "true")
    systemProperty("homeclaim.database.process.restart.phase", phase)
    useJUnitPlatform()
}

registerDatabaseProcessRestartTask("databaseProcessRestartPrepare", "prepare")
registerDatabaseProcessRestartTask("databaseProcessRestartVerify", "verify")

tasks.register<Test>("databaseInterruptionTest") {
    group = "verification"
    description = "Verify that a live Hikari pool fails closed during a database outage and reconnects after the same process returns"
    outputs.upToDateWhen { false }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*DatabaseInterruptionRuntimeTest") }
    systemProperty("homeclaim.database.interruption.required", "true")
    useJUnitPlatform()
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

// A companion Paper plugin exercises the production HomeClaim plugin against real Bukkit worlds.
// It is built as a separate artifact and is never added to shadowJar or the normal runtime classpath.
val paperWorldE2E by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().runtimeClasspath
    runtimeClasspath += output + compileClasspath
}

configurations[paperWorldE2E.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[paperWorldE2E.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val paperWorldE2EJar = tasks.register<Jar>("paperWorldE2EJar") {
    group = "verification"
    description = "Build the isolated test-only Paper world E2E companion plugin"
    dependsOn(paperWorldE2E.classesTaskName)
    archiveBaseName.set("HomeClaim-PaperWorldE2E")
    archiveVersion.set("")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("paper-world-e2e/artifacts"))
    from(paperWorldE2E.output)
}

tasks.register<Exec>("paperWorldE2E") {
    group = "verification"
    description = "Run HomeClaim mutation scenarios in a disposable real Paper 26.3 server"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    val serverDir = layout.buildDirectory.dir("paper-world-e2e/server").get().asFile
    val reportDir = layout.buildDirectory.dir("reports/paper-world-e2e").get().asFile
    val companionJar = paperWorldE2EJar.flatMap { it.archiveFile }
    val javaExecutable = providers.environmentVariable("JAVA_HOME")
        .map { "$it/bin/java" }
        .orElse("java")
    doFirst {
        delete(serverDir)
        delete(reportDir)
        serverDir.mkdirs()
        reportDir.mkdirs()
        copy {
            from(paperJar)
            into(serverDir)
        }
        copy {
            from(tasks.shadowJar.get().archiveFile)
            into(serverDir.resolve("plugins"))
            rename { "HomeClaim.jar" }
        }
        copy {
            from(companionJar.get().asFile)
            into(serverDir.resolve("plugins"))
        }
        serverDir.resolve("eula.txt").writeText("eula=true\n")
        val serverPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        serverDir.resolve("server.properties").writeText(
            "level-name=world\nserver-ip=127.0.0.1\nserver-port=$serverPort\nonline-mode=false\nmax-players=1\n" +
                "spawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-rcon=false\n"
        )
    }
    workingDir(serverDir)
    commandLine(javaExecutable.get(), "-Dcom.mojang.eula.agree=true", "-jar", paperJar.name, "nogui")
    isIgnoreExitValue = true
    doLast {
        val logsDir = serverDir.resolve("logs")
        if (logsDir.exists()) copy { from(logsDir); into(reportDir.resolve("logs")) }
        val resultFile = serverDir.resolve("plugins/HomeClaimWorldE2E/result.txt")
        val exitCode = executionResult.get().exitValue
        val result = if (resultFile.isFile) resultFile.readText() else "missing E2E result file"
        delete(serverDir)
        check(exitCode == 0) { "Paper E2E server exited with code $exitCode; report: $result" }
        check(result.startsWith("PASS")) { "Paper World E2E failed: $result" }
        reportDir.resolve("result.txt").writeText(result)
        println(result.trim())
    }
}

tasks.register("paperSnapshotPerformance") {
    group = "verification"
    description = "Run the real-Paper snapshot capture/apply/verify size matrix as part of the mutation E2E"
    dependsOn("paperWorldE2E")
}

tasks.register("paperWorldRestartRecoveryE2E") {
    group = "verification"
    description = "Verify transfer and merge world/domain recovery across real Paper process restarts"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    val serverDir = layout.buildDirectory.dir("paper-world-restart-e2e/server").get().asFile
    val reportDir = layout.buildDirectory.dir("reports/paper-world-restart-e2e").get().asFile
    val companionJar = paperWorldE2EJar.flatMap { it.archiveFile }
    val javaExecutable = providers.environmentVariable("JAVA_HOME")
        .map { "$it/bin/java" }
        .orElse("java")
    doLast {
        delete(serverDir)
        delete(reportDir)
        serverDir.mkdirs()
        reportDir.mkdirs()
        copy {
            from(paperJar)
            into(serverDir)
        }
        copy {
            from(tasks.shadowJar.get().archiveFile)
            into(serverDir.resolve("plugins"))
            rename { "HomeClaim.jar" }
        }
        copy {
            from(companionJar.get().asFile)
            into(serverDir.resolve("plugins"))
        }
        serverDir.resolve("eula.txt").writeText("eula=true\n")
        val serverPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        serverDir.resolve("server.properties").writeText(
            "level-name=world\nserver-ip=127.0.0.1\nserver-port=$serverPort\nonline-mode=false\nmax-players=1\n" +
                "spawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-rcon=false\n"
        )
        val phases = listOf("merge-restart-prepare", "merge-restart-verify", "restart-prepare", "restart-verify")
        phases.forEach { phase ->
            val process = ProcessBuilder(
                javaExecutable.get(), "-Dcom.mojang.eula.agree=true", "-Dhomeclaim.worlde2e.phase=$phase",
                "-jar", paperJar.name, "nogui"
            ).directory(serverDir).inheritIO().start()
            val exitCode = process.waitFor()
            val resultFile = serverDir.resolve("plugins/HomeClaimWorldE2E/result.txt")
            val result = if (resultFile.isFile) resultFile.readText() else "missing E2E result file"
            val logsDir = serverDir.resolve("logs")
            if (logsDir.exists()) copy { from(logsDir); into(reportDir.resolve("$phase-logs")) }
            check(exitCode == 0) { "Paper $phase process exited with code $exitCode: $result" }
            val expected = when (phase) {
                "merge-restart-prepare" -> "PREPARED MERGE_RECOVERY_IN_PROGRESS"
                "merge-restart-verify" -> "PASS merge world+domain recovery"
                "restart-prepare" -> "PREPARED"
                else -> "PASS restart recovery"
            }
            check(result.startsWith(expected)) { "Paper $phase failed: $result" }
            reportDir.resolve("$phase-result.txt").writeText(result)
        }
        println(phases.filter { it.endsWith("verify") }.joinToString("; ") { phase ->
            reportDir.resolve("$phase-result.txt").readText().trim()
        })
        delete(serverDir)
    }
}
