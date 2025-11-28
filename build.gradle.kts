import org.gradle.api.publish.maven.MavenPublication
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.0.20"
    id("maven-publish")
}

group = "com.github.locked-fog"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("dev.langchain4j:langchain4j-core:0.35.0")
    implementation("dev.langchain4j:langchain4j-open-ai:0.35.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.slf4j:slf4j-simple:2.0.13")
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
            version = "0.1.0"
        }
    }
}