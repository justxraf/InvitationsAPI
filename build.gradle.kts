plugins {
    kotlin("jvm") version "2.0.21"
    application
    jacoco
    // Generates the HTML API reference from the KDoc; run `./gradlew dokkaHtml` (output in
    // build/dokka/html). Kept at the same major as Kotlin 2.0.x for compatibility.
    id("org.jetbrains.dokka") version "1.9.20"
    // Static analysis: ktlint (style) + detekt (smells). Both run as part of `check`.
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
    // Binary-compatibility validation: dumps the public ABI to api/ and fails on undeclared changes.
    // Run `./gradlew apiDump` after an intentional public-API change; `apiCheck` runs in `check`.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
}

group = "com.justxraf"
version = "0.1-PROTOTYPE"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")

    // Bukkit/Paper is supplied by the server; the adapters only need it to compile.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")

    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.41.0")

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
}

application {
    mainClass.set("com.justxraf.invitations.demo.DemoKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform {
        // Heavy performance/stress lanes are opt-out on constrained runners:
        //   ./gradlew test -PexcludeTags=performance,stress
        val excluded = (project.findProperty("excludeTags") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (excluded.isNotEmpty()) excludeTags(*excluded.toTypedArray())
    }
    testLogging { events("passed", "failed", "skipped") }
    finalizedBy(tasks.jacocoTestReport)
}

// --- Coverage (JaCoCo) --------------------------------------------------------------------------
jacoco { toolVersion = "0.8.12" }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true) // consumed by CI
        html.required.set(true)
    }
    // Demo and example sources are illustrative and excluded from coverage accounting.
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { exclude("com/justxraf/invitations/demo/**", "com/justxraf/invitations/examples/**") }
            },
        ),
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)
    violationRules {
        // Floor for the core manager's branch coverage, aggregated across the InvitationManager source
        // file (all its nested result/builder classes) rather than per generated data-class member.
        rule {
            element = "SOURCEFILE"
            includes = listOf("InvitationManager.kt")
            limit {
                counter = "INSTRUCTION"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.70".toBigDecimal()
            }
        }
        // Overall instruction floor across the whole library (demo/examples already excluded above).
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.65".toBigDecimal()
            }
        }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

// --- Static analysis ----------------------------------------------------------------------------
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    // Pre-existing findings are recorded as a baseline so the gate fails only on *new* issues.
    // Regenerate intentionally with `./gradlew detektBaseline`.
    baseline = file("config/detekt/baseline.xml")
    // Detekt's own parallel + autoCorrect off in CI; analyze main and test.
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version.set("1.3.1")
    filter {
        // The demo/examples favor readability over strict style.
        exclude {
            it.file.path
                .replace('\\', '/')
                .contains("/examples/")
        }
    }
}

// --- Binary compatibility -----------------------------------------------------------------------
apiValidation {
    // Internal/illustrative packages are not part of the published ABI.
    ignoredPackages.add("com.justxraf.invitations.demo")
    ignoredPackages.add("com.justxraf.invitations.examples")
}

tasks.withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
    moduleName.set("InvitationsAPI")
    dokkaSourceSets.configureEach {
        // The demo and examples are illustrative, not part of the published API.
        perPackageOption {
            matchingRegex.set("com\\.justxraf\\.invitations\\.(demo|examples).*")
            suppress.set(true)
        }
        // Link the included docs/ pages into the generated module page.
        includes.from("docs/dokka-module.md")
    }
}
