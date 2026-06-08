plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "io.tykalo"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.quartz)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)
    implementation(libs.telegram.bots.starter)
    implementation(libs.telegram.bots.client)
    implementation(libs.jspecify)

    implementation(libs.logstash.logback.encoder)
    implementation(libs.sentry.logback)

    implementation(libs.spring.boot.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
}
