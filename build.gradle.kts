import org.gradle.api.publish.maven.MavenPublication
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("maven-publish")

    id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

group = "com.github.locked-fog"
version = "0.3.2"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")


    val ktorVersion = "3.3.2"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${ktorVersion}")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-client-mock:${ktorVersion}")
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
            version = "0.3.2"
        }
    }
}

detekt {
    // 指定仅分析 src 目录（跳过 test 或 build 目录）
    source.setFrom("src/main/kotlin")

    // 自动修复格式问题（类似 ktlint 的功能）
    autoCorrect = true

    // 指定配置文件（稍后创建）
    config.setFrom("config/detekt/detekt.yml")

    // 只要发现任何一个问题，就让构建失败（零容忍策略）
    // 如果是老项目，可以设置为 false，只看报告不挂流水线
    buildUponDefaultConfig = true
}