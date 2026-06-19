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

    // Paper is compileOnly and used ONLY by the optional BukkitScheduler adapter
    // (src/main/kotlin/.../bukkit/). The core API, demo and tests need no server.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Embedded SQLite JDBC driver for SqlInvitationStore integration tests (test scope only —
    // SqlInvitationStore itself is dependency-free and works against any JDBC driver at runtime).
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // MockBukkit gives the bukkit/ event-firing adapter tests a real (mocked) server + event bus.
    // Test scope only — the adapters ship against the compileOnly Paper API.
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.41.0")
}

application {
    // `./gradlew run` executes the server-free demonstration.
    mainClass.set("com.justxraf.invitations.demo.DemoKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
