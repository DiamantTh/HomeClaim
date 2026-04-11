import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.0" apply false
}

// Disable jar tasks where present - only fatJar in build/out matters
tasks.withType<Jar>().configureEach {
    if (name == "jar") {
        enabled = false
    }
}

// Custom clean task that preserves build/out
tasks.register<Delete>("cleanIntermediate") {
    group = "build"
    description = "Clean intermediate build artifacts, keeping only build/out"
    delete(fileTree("build") { exclude("out/**") })
    delete(fileTree(".") {
        include("homeclaim-*/build/**")
    })
}

allprojects {
    group = "systems.diath.homeclaim"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }

        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                freeCompilerArgs.addAll("-Xjsr305=strict")
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        dependencies {
            add("implementation", "org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
            add("implementation", "org.slf4j:slf4j-api:2.0.13")
            add("implementation", "com.zaxxer:HikariCP:5.1.0")
            add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
            add("testImplementation", "org.jetbrains.kotlin:kotlin-test:2.2.0")
            add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit5:2.2.0")
            add("testImplementation", "org.junit.jupiter:junit-jupiter:5.10.2")
            add("testImplementation", "org.mockito:mockito-core:5.12.0")
            add("testImplementation", "org.mockito:mockito-junit-jupiter:5.12.0")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.10.2")
        }
    }
}
