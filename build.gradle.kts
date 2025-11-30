import org.gradle.api.publish.maven.MavenPublication
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("maven-publish")
}

group = "com.github.locked-fog"
version = "0.1.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    val ktorVersion = "2.3.12"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${ktorVersion}")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.github.locked-fog"
            artifactId = "stream-llm"
            version = "0.1.1"
        }
    }
}