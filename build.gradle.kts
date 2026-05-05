plugins {
    kotlin("jvm") version "2.1.0"
    application
}

repositories { mavenCentral() }

application {
    mainClass.set("MainKt")
}

dependencies {
    val ktor = "3.0.3"
    val coroutines = "1.9.0"

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.ktor:ktor-client-mock:$ktor")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutines")
}

tasks.test { useJUnitPlatform() }
kotlin { jvmToolchain(21) }
