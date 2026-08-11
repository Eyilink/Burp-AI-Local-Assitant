plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.aiassist"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Montoya API (proporcionado por Burp en runtime, pero lo necesitamos para compilar)
    compileOnly("net.portswigger.burp.extensions:montoya-api:2023.12.1")

    // Cliente HTTP para hablar con el LLM local (Ollama / LM Studio) y con las búsquedas whitelisteadas
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // SQLite embebido para indexar HTTP history
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")

    // Diff línea a línea
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.test {
    useJUnitPlatform()
}

// El shadowJar es el que cargas en Burp (Extensions -> Installed -> Add)
tasks.shadowJar {
    archiveBaseName.set("burp-ai-assistant")
    archiveClassifier.set("")
}
