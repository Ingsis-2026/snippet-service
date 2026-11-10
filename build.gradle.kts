plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
    jacoco
}

group = "com.ingsis"
version = "0.0.1-SNAPSHOT"
description = "Snippets, sus tests y las reglas de cada usuario; orquesta al resto de los servicios"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Redis Streams: la cola de snippets a re-lintear cuando cambian las reglas (US15).
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // Valida los access tokens de Auth0: el usuario sale del `sub` del JWT.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // La API del agente de New Relic, para que el trace siga por los streams de Redis. Sin el agente no hace nada.
    implementation("com.newrelic.agent.java:newrelic-api:9.5.0")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// El agente de New Relic, igual que en el asset-service de la cátedra: Gradle lo baja pero no entra al
// classpath. copyNewRelic lo deja en build/newrelic junto a su newrelic.yml, y el Dockerfile lo copia a la imagen.
val newrelic: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    newrelic("com.newrelic.agent.java:newrelic-agent:9.5.0")
}

tasks.register<Sync>("copyNewRelic") {
    from(newrelic) { rename { "newrelic.jar" } }
    from("newrelic") { include("newrelic.yml") }
    into(layout.buildDirectory.dir("newrelic"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

ktlint {
    version.set("1.5.0")
    verbose.set(true)
    outputToConsole.set(true)
}

// Un solo jar con nombre fijo: el Dockerfile lo copia sin adivinar la versión.
tasks.bootJar {
    archiveFileName.set("app.jar")
}

tasks.jar {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// Solo se excluye el main() que genera Spring. Todo lo demás cuenta para la cobertura:
// excluir controllers, services o connectors deja al 80% midiendo casi nada.
val coverageExclusions = listOf("**/*ApplicationKt.class")

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
    classDirectories.setFrom(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
