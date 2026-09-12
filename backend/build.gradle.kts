plugins {
    java
    `java-test-fixtures`
    checkstyle
    jacoco
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.github.spotbugs") version "6.0.19"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val archunitVersion = "1.3.0"
// Docker Engine 29 removed API versions below 1.44; the docker-java bundled with
// Testcontainers 1.20 negotiates an older one and gets a bare 400 from the daemon.
val testcontainersVersion = "1.21.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The registry that turns the actuator's meters into a Prometheus exposition. Without it
    // `/actuator/prometheus` does not exist at all -- the endpoint was listed in the exposure
    // config and permitted in the security rules, and answered 404 to the one role allowed to
    // ask for it, because nothing had ever fetched it to notice.
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Identity (S09): resource server for JWT verification, Spring Security for the role
    // matrix and the institution-scope filter.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Verification (S05). Ed25519 comes from the JDK itself -- native since Java 15 -- so no
    // third-party implementation of the primitive is needed. Nimbus provides the JOSE
    // serialisation and Tink is the EdDSA provider Nimbus delegates to for OKP keys.
    implementation("com.nimbusds:nimbus-jose-jwt")
    implementation("com.google.crypto.tink:tink:1.15.0")

    // FR-02 cohort import. A real CSV parser, because quoted commas and embedded newlines in
    // a holder name are exactly the rows that a split(",") loses silently.
    implementation("org.apache.commons:commons-csv:1.11.0")

    // FR-09 and FR-12 PDF output.
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // FR-11 distributed rate limiting, so the limit holds across both replicas.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")
    implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.14.0")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.springframework.security:spring-security-test")

    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
}

// ---------------------------------------------------------------- test tiers
// Fast tests run on every `check`. Integration tests need Docker (Testcontainers)
// and run as their own task -- CI stage 4, per manuscript S11.
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
        }

        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
                implementation("org.testcontainers:postgresql:$testcontainersVersion")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa")
                // RateLimitIT drives real HTTP through TestRestTemplate, so it needs the web
                // types on its compile classpath. `implementation(project())` puts them on the
                // runtime classpath only -- which is the point of `implementation`.
                implementation("org.springframework.boot:spring-boot-starter-web")
                // MigrationIT drives Flyway directly to prove NFR-05 rebuild-from-empty,
                // so it needs the API on its compile classpath, not just at runtime.
                implementation("org.flywaydb:flyway-core")
                // VerificationReportIT reads the PDF it was sent and checks the signature in
                // it with an independent JOSE implementation, rather than asking the class
                // that produced the signature whether it is happy with it -- which would
                // assert only that the code agrees with itself.
                implementation("org.apache.pdfbox:pdfbox:3.0.3")
                // MetricsIT reads the exposition this application produces and checks the
                // Grafana dashboard's queries against it, so it needs the registry type.
                implementation("io.micrometer:micrometer-registry-prometheus")
                implementation("com.nimbusds:nimbus-jose-jwt:9.37.3")
                runtimeOnly("org.flywaydb:flyway-database-postgresql")
                runtimeOnly("org.postgresql:postgresql")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(test)
                }
            }
        }
    }
}

// --------------------------------------------------------- coding standards
checkstyle {
    toolVersion = "10.17.0"
    // configDirectory is what makes ${config_loc} resolve inside checkstyle.xml,
    // which is how suppressions.xml is located.
    configDirectory = rootProject.layout.projectDirectory.dir("config/checkstyle")
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

spotbugs {
    excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
    // NFR-03: zero high-priority findings. Medium/low are reported, not gated,
    // so the gate stays credible rather than becoming noise everyone ignores.
    reportLevel = com.github.spotbugs.snom.Confidence.HIGH
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
    reports.create("xml") { required = true }
}

// ------------------------------------------------------------- NFR-02 gate
// 80% line / 70% branch, scoped to logic we actually wrote. DTOs, config and
// Spring plumbing are excluded so the number means something.
val coveredPackages = listOf("**/domain/**", "**/application/**")

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            // No `includes` here. For a BUNDLE rule the include patterns match the *bundle
            // name* -- which is "backend" -- not package names, so an earlier
            // `includes = listOf("zw.ac.qvs.*")` matched nothing and the rule silently applied
            // to no bundle at all. The gate reported success while measuring nothing, which is
            // worse than having no gate: it produced a green tick nobody had earned.
            //
            // Scoping to domain and application code is done by filtering classDirectories
            // below, which is the mechanism that actually works.
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.withType<JacocoReportBase>().configureEach {
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { include(coveredPackages) }
            }
        )
    )
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Docker Engine 29 refuses API versions below 1.44, while the docker-java client inside
// Testcontainers still negotiates an older one and gets a bare HTTP 400 that surfaces as
// "Could not find a valid Docker environment". Pinning the version fixes it, and 1.44 is
// satisfied by every engine from 25.0 onwards, including the CI runners.
tasks.named<Test>("integrationTest") {
    systemProperty(
        "api.version",
        providers.environmentVariable("DOCKER_API_VERSION").getOrElse("1.44")
    )
}

// A stable artefact name, so the Dockerfile's COPY does not have to track the project
// version. The runtime stage copies build/libs/qvs.jar by name.
tasks.bootJar {
    archiveFileName = "qvs.jar"
}

// The plain jar stays enabled even though nothing ships it: the integrationTest suite
// consumes the main project through that artifact, and disabling it makes every production
// class vanish from the integration compile classpath. It keeps the `-plain` classifier the
// Spring Boot plugin gives it, so it can never be confused with qvs.jar.

// ============================================================================
// Decision 11-A -- automated verification of requirements.
//
// docs/requirements.md is the register. Tests claim coverage with
// @Requirement("FR-06"). This task cross-references the two and fails when a
// `required` row has nothing covering it, which is what makes the traceability
// matrix generated evidence instead of a hand-maintained table.
// ============================================================================

val requirementRegister = rootProject.file("docs/requirements.md")
val traceabilitySourceRoots = listOf(
    file("src/test/java"),
    file("src/integrationTest/java")
)
val traceabilityOutputDir = layout.buildDirectory.dir("reports/traceability")

tasks.register("traceabilityReport") {
    group = "verification"
    description = "Cross-references docs/requirements.md against @Requirement-annotated tests."

    inputs.file(requirementRegister).withPropertyName("register")
    inputs.files(traceabilitySourceRoots).withPropertyName("testSources")
        .ignoreEmptyDirectories().skipWhenEmpty()
    outputs.dir(traceabilityOutputDir).withPropertyName("report")

    val register = requirementRegister
    val roots = traceabilitySourceRoots
    val outDir = traceabilityOutputDir
    val projectPath = projectDir

    doLast {
        // ---------------------------------------------------------- parse register
        // BONUS included: the optional features are held to the same standard as the rest.
        // Work that is not in the matrix is work nobody checks.
        val idPattern = Regex("""^\|\s*((?:FR|NFR|ARCH|BONUS)-\d+)\s*\|""")

        // (id, title, status). A local data class here trips Kotlin script IR lowering,
        // so this stays a Triple; the accessor vals below keep the call sites readable.
        val rows = register.readLines().mapNotNull { line ->
            val id = idPattern.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val cells = line.trim().trim('|').split('|').map { it.trim() }
            Triple(id, cells.getOrElse(1) { "" }, cells.last().lowercase())
        }
        if (rows.isEmpty()) {
            throw GradleException("no requirements parsed from ${register.path} -- has the table shape changed?")
        }
        val knownIds = rows.map { it.first }.toSet()

        // ------------------------------------------------- scan tests for claims
        val annotationPattern = Regex("""@Requirement\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
        val literalPattern = Regex(""""((?:FR|NFR|ARCH|BONUS)-\d+)"""")

        // id -> "TestClassName (tier)"
        val claims = sortedMapOf<String, MutableSet<String>>()
        val unknownClaims = mutableListOf<String>()

        roots.filter { it.isDirectory }.forEach { root ->
            val tier = if (root.path.contains("integrationTest")) "integration" else "unit/slice"
            root.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { source ->
                val text = source.readText()
                annotationPattern.findAll(text).forEach { match ->
                    val ids = literalPattern.findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
                    if (ids.isEmpty()) {
                        unknownClaims += "${source.toRelativeString(projectPath)}: @Requirement with no parsable id"
                    }
                    ids.forEach { id ->
                        if (id !in knownIds) {
                            unknownClaims += "${source.toRelativeString(projectPath)}: unknown requirement id '$id'"
                        } else {
                            claims.getOrPut(id) { sortedSetOf() } += "${source.nameWithoutExtension} ($tier)"
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------- verdicts
        val uncovered = rows.filter { it.third == "required" && claims[it.first].isNullOrEmpty() }
        val pending = rows.filter { it.third == "planned" && claims[it.first].isNullOrEmpty() }
        val gated = rows.filter { it.third == "gate" }

        // --------------------------------------------------------------- report
        val dir = outDir.get().asFile
        dir.mkdirs()
        val markdown = buildString {
            appendLine("## Requirement traceability matrix")
            appendLine()
            appendLine("Generated by `:backend:traceabilityReport`.")
            appendLine()
            appendLine("| ID | Requirement | Owning tests | Status |")
            appendLine("|----|-------------|--------------|--------|")
            rows.forEach { row ->
                val id = row.first
                val title = row.second
                val status = row.third
                val tests = claims[id].orEmpty()
                val verdict = when {
                    tests.isNotEmpty() -> "covered"
                    status == "gate" -> "gate"
                    status == "planned" -> "planned"
                    else -> "**UNCOVERED**"
                }
                val owning = if (tests.isEmpty()) "&mdash;" else tests.joinToString("<br>")
                appendLine("| $id | $title | $owning | $verdict |")
            }
            appendLine()
            appendLine(
                "Covered ${claims.size}/${rows.size} &middot; planned ${pending.size} " +
                    "&middot; gate-enforced ${gated.size} &middot; uncovered ${uncovered.size}"
            )
            if (pending.isNotEmpty()) {
                appendLine()
                appendLine("Planned, not yet implemented: " + pending.joinToString(", ") { it.first } + ".")
            }
        }
        dir.resolve("traceability.md").writeText(markdown)

        // ---------------------------------------------------------- console + gate
        logger.lifecycle("")
        logger.lifecycle("Traceability: ${claims.size}/${rows.size} covered, ${pending.size} planned, ${uncovered.size} uncovered")
        logger.lifecycle("Report: ${dir.resolve("traceability.md")}")
        pending.forEach { logger.warn("  planned, no covering test yet: ${it.first} ${it.second}") }

        if (unknownClaims.isNotEmpty()) {
            throw GradleException(
                "@Requirement annotations reference ids that are not in the register:\n  " +
                    unknownClaims.joinToString("\n  ")
            )
        }
        if (uncovered.isNotEmpty()) {
            throw GradleException(
                "requirements marked 'required' have no covering test:\n  " +
                    uncovered.joinToString("\n  ") { "${it.first} ${it.second}" }
            )
        }
    }
}

tasks.named("check") {
    dependsOn("traceabilityReport")
}
