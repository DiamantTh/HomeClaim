import groovy.json.JsonSlurper
import java.util.zip.ZipFile
import java.net.URI
import java.net.URL
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.security.MessageDigest
import java.time.Instant

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

// Pin the Purpur runtime used by the real-world compatibility suite. The test companion remains
// an isolated artifact and the production plugin JAR is identical to the Paper test artifact.
val purpurBuild = "2647"
val purpurJar = layout.buildDirectory.file("purpur-world-e2e/purpur-$minecraftVersion-$purpurBuild.jar")
val downloadPurpur = tasks.register("downloadPurpur") {
    outputs.file(purpurJar)
    doLast {
        val destination = purpurJar.get().asFile
        destination.parentFile.mkdirs()
        val url = URI("https://api.purpurmc.org/v2/purpur/$minecraftVersion/$purpurBuild/download").toURL()
        openUrl(url).use { input -> destination.outputStream().use(input::copyTo) }
        println("Downloaded Purpur $minecraftVersion build $purpurBuild to $destination")
    }
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

val requiredWorldE2EScenarios = setOf(
    "copy", "move", "swap", "reset", "restore", "copy-recovery", "move-recovery",
    "swap-recovery", "restore-recovery", "merge", "unlink", "merge-recovery",
    "unlink-recovery", "bundle-corruption-rejected", "container-roundtrip",
    "unsupported-tilestate-fail-closed", "unsupported-tilestate-sign", "unsupported-tilestate-banner",
    "unsupported-tilestate-hanging-sign", "unsupported-tilestate-player-head", "unsupported-tilestate-beacon",
    "unsupported-tilestate-jukebox", "unsupported-tilestate-lectern", "biome-partial-recovery",
    "snapshot-interleaving", "hcbk-v1-v2-v3-regression", "biome-custom-roundtrip", "biome-missing-key", "biome-chunk-loading"
)

fun sha256(file: java.io.File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

fun validateWorldE2EResult(
    resultFile: java.io.File,
    runId: String,
    expectedSha: String,
    expectedServerPrefix: String,
    requiredScenarios: Set<String> = requiredWorldE2EScenarios
) {
    check(resultFile.isFile) { "Missing machine-readable E2E result: $resultFile" }
    @Suppress("UNCHECKED_CAST")
    val result = JsonSlurper().parse(resultFile) as? Map<String, Any?>
        ?: error("E2E result is not a JSON object: $resultFile")
    check(result["contractVersion"] == 1) { "Unsupported E2E result contract version: ${result["contractVersion"]}" }
    check(result["runId"] == runId) { "E2E result runId mismatch: ${result["runId"]} != $runId" }
    check(result["overallStatus"] == "PASSED") { "E2E overallStatus is not PASSED: ${result["overallStatus"]}; ${result["failureDiagnostics"]}" }
    check((result["serverType"] as? String)?.startsWith(expectedServerPrefix) == true) {
        "Unexpected E2E server type: ${result["serverType"]}"
    }
    check((result["serverVersion"] as? String)?.isNotBlank() == true) { "E2E result has no server build/version" }
    check(result["pluginSha256"] == expectedSha) {
        "E2E loaded plugin SHA-256 mismatch: ${result["pluginSha256"]} != $expectedSha"
    }
    val startedAt: Instant? = (result["startedAt"] as? String)?.let { timestamp -> runCatching { Instant.parse(timestamp) }.getOrNull() }
    val completedAt: Instant? = (result["completedAt"] as? String)?.let { timestamp -> runCatching { Instant.parse(timestamp) }.getOrNull() }
    check(startedAt != null && completedAt != null && !completedAt.isBefore(startedAt)) {
        "E2E result has invalid run timestamps"
    }
    val scenarios = (result["scenarios"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
        ?: error("E2E result scenarios are missing or malformed")
    val names = scenarios.map { it["name"] as? String ?: error("E2E scenario omitted its name") }
    check(names.size == names.toSet().size) { "E2E result contains duplicate scenario names" }
    check((result["failureDiagnostics"] as? List<*>)?.isEmpty() == true) {
        "Passing E2E result contains failure diagnostics: ${result["failureDiagnostics"]}"
    }
    val passedNames = scenarios.filter { it["status"] == "PASSED" }.mapNotNull { it["name"] as? String }.toSet()
    check(passedNames.containsAll(requiredScenarios)) {
        "Required E2E scenarios did not pass: ${requiredScenarios - passedNames}"
    }
}

tasks.register<Exec>("paperWorldE2E") {
    group = "verification"
    description = "Run HomeClaim mutation scenarios in a disposable real Paper 26.3 server"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar, tasks.test)
    val serverDir = layout.buildDirectory.dir("paper-world-e2e/server").get().asFile
    val reportDir = layout.buildDirectory.dir("reports/paper-world-e2e").get().asFile
    val companionJar = paperWorldE2EJar.flatMap { it.archiveFile }
    val javaExecutable = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().executablePath.asFile.absolutePath
    var runId = ""
    var expectedPluginSha = ""
    var consoleOut: java.io.OutputStream? = null
    var consoleErr: java.io.OutputStream? = null
    doFirst {
        delete(serverDir)
        delete(reportDir)
        serverDir.mkdirs()
        reportDir.mkdirs()
        runId = UUID.randomUUID().toString()
        expectedPluginSha = sha256(tasks.shadowJar.get().archiveFile.get().asFile)
        consoleOut = reportDir.resolve("server-console.out.log").outputStream()
        consoleErr = reportDir.resolve("server-console.err.log").outputStream()
        standardOutput = consoleOut
        errorOutput = consoleErr
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
        copy {
            from("src/paperWorldE2E/fixtures/biome-datapack")
            into(serverDir.resolve("world/datapacks/homeclaim-e2e"))
        }
        serverDir.resolve("eula.txt").writeText("eula=true\n")
        val serverPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        serverDir.resolve("server.properties").writeText(
            "level-name=world\nserver-ip=127.0.0.1\nserver-port=$serverPort\nonline-mode=false\nmax-players=1\n" +
                "spawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-rcon=false\n"
        )
        commandLine("timeout", "--signal=TERM", "--kill-after=30s", "30m", javaExecutable,
            "-Xms256m", "-Xmx2g", "-Dcom.mojang.eula.agree=true",
            "-Dhomeclaim.worlde2e.runId=$runId", "-Dhomeclaim.worlde2e.pluginSha256=$expectedPluginSha",
            "-jar", paperJar.name, "nogui")
    }
    workingDir(serverDir)
    isIgnoreExitValue = true
    doLast {
        consoleOut?.close()
        consoleErr?.close()
        val logsDir = serverDir.resolve("logs")
        if (logsDir.exists()) copy { from(logsDir); into(reportDir.resolve("logs")) }
        val resultFile = serverDir.resolve("plugins/HomeClaimWorldE2E/result.txt")
        val jsonResult = serverDir.resolve("plugins/HomeClaimWorldE2E/e2e-result.json")
        val exitCode = executionResult.get().exitValue
        val result = if (resultFile.isFile) resultFile.readText() else "missing E2E result file"
        check(exitCode == 0) { "Paper E2E server exited with code $exitCode; report: $result" }
        check(result.startsWith("PASS")) { "Paper World E2E failed: $result" }
        validateWorldE2EResult(jsonResult, runId, expectedPluginSha, "Paper")
        reportDir.resolve("result.txt").writeText(result)
        copy { from(jsonResult); into(reportDir) }
        copy { from(serverDir.resolve("plugins/HomeClaimWorldE2E/biome-metrics.json")); into(reportDir) }
        copy { from(serverDir.resolve("plugins/HomeClaimWorldE2E/biome-chunk-metrics.json")); into(reportDir) }
        delete(serverDir)
        println(result.trim())
    }
}

tasks.register<Exec>("purpurWorldE2E") {
    group = "verification"
    description = "Run the HomeClaim mutation scenarios in a disposable real Purpur 26.3 server"
    dependsOn(downloadPurpur, tasks.shadowJar, paperWorldE2EJar, tasks.test)
    val serverDir = layout.buildDirectory.dir("purpur-world-e2e/server").get().asFile
    val reportDir = layout.buildDirectory.dir("reports/purpur-world-e2e").get().asFile
    val companionJar = paperWorldE2EJar.flatMap { it.archiveFile }
    val javaExecutable = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().executablePath.asFile.absolutePath
    var runId = ""
    var expectedPluginSha = ""
    var consoleOut: java.io.OutputStream? = null
    var consoleErr: java.io.OutputStream? = null
    doFirst {
        delete(serverDir)
        delete(reportDir)
        serverDir.mkdirs()
        reportDir.mkdirs()
        runId = UUID.randomUUID().toString()
        expectedPluginSha = sha256(tasks.shadowJar.get().archiveFile.get().asFile)
        consoleOut = reportDir.resolve("server-console.out.log").outputStream()
        consoleErr = reportDir.resolve("server-console.err.log").outputStream()
        standardOutput = consoleOut
        errorOutput = consoleErr
        copy { from(purpurJar); into(serverDir) }
        copy {
            from(tasks.shadowJar.get().archiveFile)
            into(serverDir.resolve("plugins"))
            rename { "HomeClaim.jar" }
        }
        copy { from(companionJar.get().asFile); into(serverDir.resolve("plugins")) }
        copy {
            from("src/paperWorldE2E/fixtures/biome-datapack")
            into(serverDir.resolve("world/datapacks/homeclaim-e2e"))
        }
        serverDir.resolve("eula.txt").writeText("eula=true\n")
        val serverPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        serverDir.resolve("server.properties").writeText(
            "level-name=world\nserver-ip=127.0.0.1\nserver-port=$serverPort\nonline-mode=false\nmax-players=1\n" +
                "spawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-rcon=false\n"
        )
        commandLine("timeout", "--signal=TERM", "--kill-after=30s", "30m", javaExecutable,
            "-Xms256m", "-Xmx2g", "-Dcom.mojang.eula.agree=true",
            "-Dhomeclaim.worlde2e.runId=$runId", "-Dhomeclaim.worlde2e.pluginSha256=$expectedPluginSha",
            "-jar", purpurJar.get().asFile.name, "nogui")
    }
    workingDir(serverDir)
    isIgnoreExitValue = true
    doLast {
        consoleOut?.close()
        consoleErr?.close()
        val logsDir = serverDir.resolve("logs")
        if (logsDir.exists()) copy { from(logsDir); into(reportDir.resolve("logs")) }
        val resultFile = serverDir.resolve("plugins/HomeClaimWorldE2E/result.txt")
        val jsonResult = serverDir.resolve("plugins/HomeClaimWorldE2E/e2e-result.json")
        val exitCode = executionResult.get().exitValue
        val result = if (resultFile.isFile) resultFile.readText() else "missing E2E result file"
        check(exitCode == 0) { "Purpur E2E server exited with code $exitCode; report: $result" }
        check(result.startsWith("PASS")) { "Purpur World E2E failed: $result" }
        validateWorldE2EResult(jsonResult, runId, expectedPluginSha, "Purpur")
        reportDir.resolve("result.txt").writeText(result)
        copy { from(jsonResult); into(reportDir) }
        copy { from(serverDir.resolve("plugins/HomeClaimWorldE2E/biome-metrics.json")); into(reportDir) }
        copy { from(serverDir.resolve("plugins/HomeClaimWorldE2E/biome-chunk-metrics.json")); into(reportDir) }
        delete(serverDir)
        println(result.trim())
    }
}

tasks.register<Exec>("biomeRegistryRestartE2E") {
    group = "verification"
    description = "Verify a custom biome key disappears and returns across three real Paper processes"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("scripts/biome-registry-restart-e2e.py").absolutePath,
        paperJar.absolutePath, tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
        paperWorldE2EJar.get().archiveFile.get().asFile.absolutePath,
        file("src/paperWorldE2E/fixtures/biome-datapack").absolutePath)
    doFirst {
        environment("HOMECLAIM_E2E_JAVA", javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().executablePath.asFile.absolutePath)
    }
}

tasks.register<Exec>("networkedPlayerE2E") {
    group = "verification"
    description = "Run two real offline-mode network players through HomeClaim commands and WebUX pairing on isolated Paper 26.3"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        rootProject.file("scripts/networked-player-e2e.py").absolutePath,
        paperJar.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
        paperWorldE2EJar.get().archiveFile.get().asFile.absolutePath
    )
    doFirst {
        val java25 = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().executablePath.asFile.absolutePath
        environment("HOMECLAIM_E2E_JAVA", java25)
    }
    isIgnoreExitValue = false
}

tasks.register("worldE2EParity") {
    group = "verification"
    description = "Compare the required Paper and Purpur world E2E scenario outcomes"
    mustRunAfter("paperWorldE2E", "purpurWorldE2E")
    doLast {
        val paperResult = layout.buildDirectory.file("reports/paper-world-e2e/e2e-result.json").get().asFile
        val purpurResult = layout.buildDirectory.file("reports/purpur-world-e2e/e2e-result.json").get().asFile
        @Suppress("UNCHECKED_CAST")
        fun scenarios(file: java.io.File): Map<String, String> {
            val json = JsonSlurper().parse(file) as Map<String, Any?>
            check(json["overallStatus"] == "PASSED") { "$file does not contain a passing result: ${json["overallStatus"]}" }
            check((json["pluginSha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true) {
                "$file does not identify a production plugin artifact"
            }
            return (json["scenarios"] as List<*>).mapNotNull { it as? Map<*, *> }
                .associate { (it["name"] as String) to (it["status"] as String) }
        }
        val paperScenarios = scenarios(paperResult)
        val purpurScenarios = scenarios(purpurResult)
        check(paperScenarios.values.all { it == "PASSED" } && purpurScenarios.values.all { it == "PASSED" }) {
            "Paper/Purpur parity reports contain a non-passing scenario: Paper=$paperScenarios Purpur=$purpurScenarios"
        }
        check(paperScenarios == purpurScenarios) {
            "Paper/Purpur world E2E scenario parity differs: Paper=$paperScenarios Purpur=$purpurScenarios"
        }
        check(paperScenarios.keys.containsAll(requiredWorldE2EScenarios)) {
            "Paper/Purpur parity omitted required scenarios: ${requiredWorldE2EScenarios - paperScenarios.keys}"
        }
        val paperJson = JsonSlurper().parse(paperResult) as Map<*, *>
        val purpurJson = JsonSlurper().parse(purpurResult) as Map<*, *>
        check(paperJson["pluginSha256"] == purpurJson["pluginSha256"]) {
            "Paper and Purpur reports identify different HomeClaim artifacts"
        }
        check(paperJson["pluginSha256"] == sha256(tasks.shadowJar.get().archiveFile.get().asFile)) {
            "World E2E parity reports do not identify the current production artifact"
        }
        println("Paper/Purpur scenario parity: ${paperScenarios.size} scenarios PASSED")
    }
}

tasks.register<Exec>("purpurNetworkedPlayerE2E") {
    group = "verification"
    description = "Run a real offline-mode Player login, plot-info and reset-confirmation smoke on isolated Purpur 26.3"
    dependsOn(downloadPurpur, tasks.shadowJar, paperWorldE2EJar)
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        rootProject.file("scripts/networked-player-e2e.py").absolutePath,
        purpurJar.get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
        paperWorldE2EJar.get().archiveFile.get().asFile.absolutePath
    )
    doFirst {
        val java25 = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().executablePath.asFile.absolutePath
        environment("HOMECLAIM_E2E_JAVA", java25)
        environment("HOMECLAIM_E2E_SERVER_KIND", "purpur")
        environment("HOMECLAIM_E2E_MODE", "smoke")
    }
    isIgnoreExitValue = false
}

tasks.register<Exec>("paperDatabaseInterruptionE2E") {
    group = "verification"
    description = "Stop PostgreSQL and MariaDB while the real HomeClaim Paper plugin is running, then verify fail-closed and same-process recovery"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    workingDir(rootProject.projectDir)
    commandLine(
        "bash",
        rootProject.file("scripts/paper-database-interruption-e2e.sh").absolutePath,
        paperJar.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
        paperWorldE2EJar.get().archiveFile.get().asFile.absolutePath
    )
}

tasks.register<Exec>("destructiveDbOutageE2E") {
    group = "verification"
    description = "Run the complete deterministic destructive database outage matrix in real Paper"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    workingDir(rootProject.projectDir)
    commandLine("bash", rootProject.file("scripts/destructive-database-matrix-e2e.sh").absolutePath,
        paperJar.absolutePath, tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
        paperWorldE2EJar.get().archiveFile.get().asFile.absolutePath)
}

tasks.register<Exec>("destructiveDbRestartE2E") {
    group = "verification"
    description = "Verify explicit recovery after deterministic hard Paper crashes and database outages"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    workingDir(rootProject.projectDir)
    commandLine("bash", rootProject.file("scripts/destructive-database-restart-e2e.sh").absolutePath,
        paperJar.absolutePath, tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,
        paperWorldE2EJar.get().archiveFile.get().asFile.absolutePath)
}

tasks.register<Exec>("paperSnapshotPerformance") {
    group = "verification"
    description = "Run a standalone real-Paper medium snapshot capture/apply/verify sanity case"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    val serverDir = layout.buildDirectory.dir("paper-snapshot-performance/server").get().asFile
    val reportDir = layout.buildDirectory.dir("reports/paper-snapshot-performance").get().asFile
    val companionJar = paperWorldE2EJar.flatMap { it.archiveFile }
    val javaExecutable = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().executablePath.asFile.absolutePath
    var runId = ""
    var expectedPluginSha = ""
    var consoleOut: java.io.OutputStream? = null
    var consoleErr: java.io.OutputStream? = null
    doFirst {
        delete(serverDir)
        delete(reportDir)
        serverDir.mkdirs()
        reportDir.mkdirs()
        runId = UUID.randomUUID().toString()
        expectedPluginSha = sha256(tasks.shadowJar.get().archiveFile.get().asFile)
        consoleOut = reportDir.resolve("server-console.out.log").outputStream()
        consoleErr = reportDir.resolve("server-console.err.log").outputStream()
        standardOutput = consoleOut
        errorOutput = consoleErr
        copy { from(paperJar); into(serverDir) }
        copy {
            from(tasks.shadowJar.get().archiveFile)
            into(serverDir.resolve("plugins"))
            rename { "HomeClaim.jar" }
        }
        copy { from(companionJar.get().asFile); into(serverDir.resolve("plugins")) }
        serverDir.resolve("eula.txt").writeText("eula=true\n")
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        serverDir.resolve("server.properties").writeText(
            "level-name=world\nserver-ip=127.0.0.1\nserver-port=$port\nonline-mode=false\nmax-players=1\n" +
                "spawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-rcon=false\n"
        )
        commandLine("timeout", "--signal=TERM", "--kill-after=30s", "20m", javaExecutable,
            "-Xms256m", "-Xmx2g", "-Dcom.mojang.eula.agree=true",
            "-Dhomeclaim.worlde2e.phase=performance-only", "-Dhomeclaim.worlde2e.runId=$runId",
            "-Dhomeclaim.worlde2e.pluginSha256=$expectedPluginSha", "-jar", paperJar.name, "nogui")
    }
    workingDir(serverDir)
    isIgnoreExitValue = true
    doLast {
        consoleOut?.close()
        consoleErr?.close()
        val logsDir = serverDir.resolve("logs")
        if (logsDir.exists()) copy { from(logsDir); into(reportDir.resolve("logs")) }
        val exitCode = executionResult.get().exitValue
        val resultFile = serverDir.resolve("plugins/HomeClaimWorldE2E/result.txt")
        val jsonFile = serverDir.resolve("plugins/HomeClaimWorldE2E/e2e-result.json")
        val summary = if (resultFile.isFile) resultFile.readText() else "missing E2E result file"
        check(exitCode == 0 && summary.startsWith("PASS")) { "Paper performance task failed ($exitCode): $summary" }
        validateWorldE2EResult(jsonFile, runId, expectedPluginSha, "Paper", setOf("medium-performance-matrix"))
        copy { from(jsonFile); into(reportDir) }
        reportDir.resolve("result.txt").writeText(summary)
        delete(serverDir)
        println(summary.trim())
    }
}

tasks.register("paperWorldRestartRecoveryE2E") {
    group = "verification"
    description = "Verify transfer, merge/domain, and biome-cell recovery across real Paper process restarts"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    val serverDir = layout.buildDirectory.dir("paper-world-restart-e2e/server").get().asFile
    val reportDir = layout.buildDirectory.dir("reports/paper-world-restart-e2e").get().asFile
    val companionJar = paperWorldE2EJar.flatMap { it.archiveFile }
    val javaExecutable = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().executablePath.asFile.absolutePath
    doLast {
        val restartRunId = UUID.randomUUID().toString()
        val restartSha = sha256(tasks.shadowJar.get().archiveFile.get().asFile)
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
        val phases = listOf("merge-restart-prepare", "merge-restart-verify", "restart-prepare", "restart-verify", "biome-restart-prepare", "biome-restart-verify")
        phases.forEach { phase ->
            val process = ProcessBuilder(
                javaExecutable, "-Dcom.mojang.eula.agree=true", "-Dhomeclaim.worlde2e.phase=$phase",
                "-Dhomeclaim.worlde2e.runId=$restartRunId", "-Dhomeclaim.worlde2e.pluginSha256=$restartSha",
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
                "biome-restart-prepare" -> "PREPARED BIOME_RECOVERY_REQUIRED"
                "biome-restart-verify" -> "PASS biome partial mutation was blocked and restored"
                "restart-prepare" -> "PREPARED"
                else -> "PASS restart recovery"
            }
            check(result.startsWith(expected)) { "Paper $phase failed: $result" }
            if (phase == "biome-restart-verify") {
                val json = serverDir.resolve("plugins/HomeClaimWorldE2E/e2e-result.json")
                validateWorldE2EResult(json, restartRunId, restartSha, "Paper", setOf("biome-restart-recovery"))
                copy { from(json); into(reportDir); rename { "biome-restart-result.json" } }
            }
            reportDir.resolve("$phase-result.txt").writeText(result)
        }
        println(phases.filter { it.endsWith("verify") }.joinToString("; ") { phase ->
            reportDir.resolve("$phase-result.txt").readText().trim()
        })
        delete(serverDir)
    }
}

tasks.register("purpurRestartRecoveryE2E") {
    group = "verification"
    description = "Verify transfer recovery across a real Purpur process restart"
    dependsOn(downloadPurpur, tasks.shadowJar, paperWorldE2EJar)
    val serverDir = layout.buildDirectory.dir("purpur-restart-recovery-e2e/server").get().asFile
    val reportDir = layout.buildDirectory.dir("reports/purpur-restart-recovery-e2e").get().asFile
    val companionJar = paperWorldE2EJar.flatMap { it.archiveFile }
    val javaExecutable = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().executablePath.asFile.absolutePath
    doLast {
        val restartRunId = UUID.randomUUID().toString()
        val restartSha = sha256(tasks.shadowJar.get().archiveFile.get().asFile)
        delete(serverDir)
        delete(reportDir)
        serverDir.mkdirs()
        reportDir.mkdirs()
        copy { from(purpurJar); into(serverDir) }
        copy {
            from(tasks.shadowJar.get().archiveFile)
            into(serverDir.resolve("plugins"))
            rename { "HomeClaim.jar" }
        }
        copy { from(companionJar.get().asFile); into(serverDir.resolve("plugins")) }
        serverDir.resolve("eula.txt").writeText("eula=true\n")
        val serverPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        serverDir.resolve("server.properties").writeText(
            "level-name=world\nserver-ip=127.0.0.1\nserver-port=$serverPort\nonline-mode=false\nmax-players=1\n" +
                "spawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-rcon=false\n"
        )
        listOf("restart-prepare", "restart-verify", "biome-restart-prepare", "biome-restart-verify").forEach { phase ->
            val process = ProcessBuilder(
                javaExecutable, "-Dcom.mojang.eula.agree=true", "-Dhomeclaim.worlde2e.phase=$phase",
                "-Dhomeclaim.worlde2e.runId=$restartRunId", "-Dhomeclaim.worlde2e.pluginSha256=$restartSha",
                "-jar", purpurJar.get().asFile.name, "nogui"
            ).directory(serverDir).inheritIO().start()
            val exitCode = process.waitFor()
            val resultFile = serverDir.resolve("plugins/HomeClaimWorldE2E/result.txt")
            val result = if (resultFile.isFile) resultFile.readText() else "missing E2E result file"
            val logsDir = serverDir.resolve("logs")
            if (logsDir.exists()) copy { from(logsDir); into(reportDir.resolve("$phase-logs")) }
            check(exitCode == 0) { "Purpur $phase process exited with code $exitCode: $result" }
            val expected = if (phase.endsWith("prepare")) "PREPARED" else if (phase.startsWith("biome")) "PASS biome partial mutation was blocked and restored" else "PASS restart recovery"
            check(result.startsWith(expected)) { "Purpur $phase failed: $result" }
            if (phase == "biome-restart-verify") {
                val json = serverDir.resolve("plugins/HomeClaimWorldE2E/e2e-result.json")
                validateWorldE2EResult(json, restartRunId, restartSha, "Purpur", setOf("biome-restart-recovery"))
                copy { from(json); into(reportDir); rename { "biome-restart-result.json" } }
            }
            reportDir.resolve("$phase-result.txt").writeText(result)
        }
        println(reportDir.resolve("restart-verify-result.txt").readText().trim())
        delete(serverDir)
    }
}

tasks.register("paperWorldCrashRecoveryE2E") {
    group = "verification"
    description = "Hard-stop Paper during a controlled partial transfer write and recover from its durable intent after restart"
    dependsOn(downloadPaper, tasks.shadowJar, paperWorldE2EJar)
    val serverDir = layout.buildDirectory.dir("paper-world-crash-recovery-e2e/server").get().asFile
    val reportDir = layout.buildDirectory.dir("reports/paper-world-crash-recovery-e2e").get().asFile
    val companionJar = paperWorldE2EJar.flatMap { it.archiveFile }
    val javaExecutable = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().executablePath.asFile.absolutePath
    doLast {
        delete(serverDir)
        delete(reportDir)
        serverDir.mkdirs()
        reportDir.mkdirs()
        copy { from(paperJar); into(serverDir) }
        copy {
            from(tasks.shadowJar.get().archiveFile)
            into(serverDir.resolve("plugins"))
            rename { "HomeClaim.jar" }
        }
        copy { from(companionJar.get().asFile); into(serverDir.resolve("plugins")) }
        serverDir.resolve("eula.txt").writeText("eula=true\n")
        val serverPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        serverDir.resolve("server.properties").writeText(
            "level-name=world\nserver-ip=127.0.0.1\nserver-port=$serverPort\nonline-mode=false\nmax-players=1\n" +
                "spawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-rcon=false\n"
        )
        val warmupLog = reportDir.resolve("world-warmup.log")
        val warmup = ProcessBuilder(
            javaExecutable, "-Dcom.mojang.eula.agree=true", "-Dhomeclaim.worlde2e.phase=world-identity-warmup",
            "-jar", paperJar.name, "nogui"
        ).directory(serverDir).redirectErrorStream(true).redirectOutput(warmupLog).start()
        check(warmup.waitFor(5, TimeUnit.MINUTES)) {
            warmup.destroyForcibly()
            "Paper world identity warmup timed out: ${warmupLog.readText()}"
        }
        check(warmup.exitValue() == 0 && serverDir.resolve("plugins/HomeClaimWorldE2E/result.txt")
            .readText().startsWith("PASS isolated test world identity initialized")) {
            "Paper world identity warmup failed: ${warmupLog.readText()}"
        }
        val prepareLog = reportDir.resolve("prepare.log")
        val prepare = ProcessBuilder(
            javaExecutable, "-Dcom.mojang.eula.agree=true", "-Dhomeclaim.worlde2e.phase=db-worldwrite-crash-prepare",
            "-jar", paperJar.name, "nogui"
        ).directory(serverDir).redirectErrorStream(true).redirectOutput(prepareLog).start()
        val marker = serverDir.resolve("plugins/HomeClaimWorldE2E/db-worldwrite-phase.txt")
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        while (!marker.isFile || !marker.readText().startsWith("WORLD_PARTIAL_WRITE_CRASH")) {
            check(!marker.isFile || !marker.readText().startsWith("BARRIER_ERROR")) {
                "Paper could not persist the crash barrier job reference: ${marker.readText()}"
            }
            check(prepare.isAlive) { "Paper crash-prepare exited before the partial-write barrier: ${prepareLog.readText()}" }
            check(System.nanoTime() < deadline) { "Timed out waiting for the real Paper partial-write barrier: ${prepareLog.readText()}" }
            Thread.sleep(250)
        }
        prepare.destroyForcibly()
        check(prepare.waitFor(30, TimeUnit.SECONDS)) { "Could not stop the Paper crash-prepare process" }
        val verifyLog = reportDir.resolve("verify.log")
        val verify = ProcessBuilder(
            javaExecutable, "-Dcom.mojang.eula.agree=true", "-Dhomeclaim.worlde2e.phase=db-worldwrite-restart-verify",
            "-jar", paperJar.name, "nogui"
        ).directory(serverDir).redirectErrorStream(true).redirectOutput(verifyLog).start()
        check(verify.waitFor(5, TimeUnit.MINUTES)) {
            verify.destroyForcibly()
            "Paper recovery verification timed out: ${verifyLog.readText()}"
        }
        val resultFile = serverDir.resolve("plugins/HomeClaimWorldE2E/result.txt")
        val result = if (resultFile.isFile) resultFile.readText() else "missing E2E result file"
        check(verify.exitValue() == 0 && result.startsWith("PASS destructive DB outage partial COPY recovered after Paper process restart")) {
            "Paper restart recovery failed: $result\n${verifyLog.readText()}"
        }
        reportDir.resolve("result.txt").writeText(result)
        println(result.trim())
        delete(serverDir)
    }
}
