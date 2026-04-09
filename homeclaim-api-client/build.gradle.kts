plugins {
    kotlin("jvm")
    `maven-publish`
    signing
}

group = "systems.diath.homeclaim"
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    api("org.jetbrains.kotlin:kotlin-reflect:2.2.0")
}

tasks {
    jar {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to "systems.diath",
                "Specification-Title" to "HomeClaim API",
                "Specification-Version" to project.version,
                "Multi-Release" to "true"
            )
        }
    }
    
    create<Jar>("sourcesJar") {
        archiveClassifier.set("sources")
        from(sourceSets.main.get().allSource)
    }
    
    create<Jar>("javadocJar") {
        archiveClassifier.set("javadoc")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "homeclaim-api-client"
            
            from(components["java"])
            artifact(tasks["sourcesJar"])
            
            pom {
                name.set("HomeClaim API Client")
                description.set("Public API for third-party HomeClaim plugin extensions")
                url.set("https://github.com/systems-diath/HomeClaim")
                
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                
                developers {
                    developer {
                        id.set("systems-diath")
                        name.set("Systems Diath")
                        email.set("contact@systems-diath.de")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/systems-diath/HomeClaim.git")
                    developerConnection.set("scm:git:ssh://git@github.com:systems-diath/HomeClaim.git")
                    url.set("https://github.com/systems-diath/HomeClaim")
                }
            }
        }
    }
    
    repositories {
        maven {
            name = "MavenCentral"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = project.findProperty("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME") ?: ""
                password = project.findProperty("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD") ?: ""
            }
        }
        
        maven {
            name = "Snapshot"
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            credentials {
                username = project.findProperty("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME") ?: ""
                password = project.findProperty("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD") ?: ""
            }
        }
    }
}

signing {
    // Use GPG key from environment or properties
    val keyId = project.findProperty("signing.keyId")?.toString() ?: System.getenv("GPG_KEY_ID") ?: ""
    val key = project.findProperty("signing.key")?.toString() ?: System.getenv("GPG_PRIVATE_KEY") ?: ""
    val password = project.findProperty("signing.password")?.toString() ?: System.getenv("GPG_PASSPHRASE") ?: ""
    
    if (keyId.isNotEmpty() && key.isNotEmpty() && password.isNotEmpty()) {
        useInMemoryPgpKeys(keyId, key, password)
        sign(publishing.publications["mavenJava"])
    }
}

