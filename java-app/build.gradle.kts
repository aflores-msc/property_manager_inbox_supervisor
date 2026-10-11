plugins {
    application
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.beryx.runtime") version "2.0.1"
    id("org.openjfx.javafxplugin") version "0.1.0"
}

javafx {
    version = "25.0.1"
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.base")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "com.estatebeacon.EstateBeaconApp"
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "2.0.1"

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai")

    implementation("org.bsc.langgraph4j:langgraph4j-core:1.8.27")

    implementation("io.github.mkpaz:atlantafx-base:2.1.0")

    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

    implementation("com.google.apis:google-api-services-gmail:v1-rev20260525-2.0.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.39.0")
    implementation("com.google.api-client:google-api-client:2.7.2")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    mainClass = "com.estatebeacon.EstateBeaconApp"
}

runtime {
    // jlink's own jdeps-based auto-detection misses modules that are only reachable via
    // JavaFX's internal/reflective use (jdk.unsupported) or TLS (jdk.crypto.ec) — spelling
    // out java.se plus those closes that gap instead of chasing missing-module errors one at a time.
    modules.set(listOf("java.se", "jdk.unsupported", "jdk.crypto.ec", "jdk.httpserver", "jdk.jfr"))

    jpackage {
        imageName = "Estate Beacon"
        // jpackage requires the first version component to be >= 1; the Gradle
        // project version (0.1.0) doesn't qualify, so this is pinned separately.
        appVersion = "1.0.0"
        skipInstaller = true
        imageOptions = listOf("--icon", file("packaging/macos/app-icon.icns").absolutePath)
        // jpackage puts every dependency jar (JavaFX included) on the plain classpath;
        // JavaFX only initializes from the module path, so it has to be added back here
        // the same way the `run` task's javafx plugin-generated args do it. Scoped to just
        // the JavaFX jars (not the whole $APPDIR) — pointing the module path at a directory
        // containing every dependency pulls sqlite-jdbc in as an automatic module too, which
        // then can't see SLF4J classes sitting in the classpath's unnamed module.
        val javafxModulePath = configurations.runtimeClasspath.get().files
            .filter { it.name.startsWith("javafx-") }
            .sortedBy { it.name }
            .joinToString(":") { "\$APPDIR/${it.name}" }
        jvmArgs = listOf(
            "--module-path", javafxModulePath,
            "--add-modules", "javafx.controls,javafx.graphics,javafx.base"
        )
    }
}
