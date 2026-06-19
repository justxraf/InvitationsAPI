plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.justxraf"
version = "0.1-PROTOTYPE"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")

    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")

    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.41.0")
}

application {
    mainClass.set("com.justxraf.invitations.demo.DemoKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
