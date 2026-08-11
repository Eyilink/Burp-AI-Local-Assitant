#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# build.sh — Compila la extensión Burp AI Assistant (Java) en TU máquina.
#
# Requisitos previos en tu máquina:
#   - Java 17+ instalado (`java -version`)
#   - Acceso normal a internet (Maven Central, GitHub)
#
# Uso:
#   chmod +x build.sh
#   ./build.sh
#
# Al terminar, el jar listo para cargar en Burp estará en:
#   build/libs/burp-ai-assistant-0.1.0.jar
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> Verificando Java..."
if ! command -v java &> /dev/null; then
    echo "ERROR: No se encontró 'java' en el PATH. Instala Java 17+ primero:"
    echo "  - macOS:   brew install openjdk@17"
    echo "  - Ubuntu:  sudo apt install openjdk-17-jdk"
    echo "  - Windows: https://adoptium.net/temurin/releases/"
    exit 1
fi
java -version

# ----------------------------------------------------------------------------
# 1. Generar el Gradle wrapper si no existe (así no necesitas tener Gradle
#    instalado globalmente; el wrapper se descarga solo la primera vez).
# ----------------------------------------------------------------------------
if [ ! -f "./gradlew" ]; then
    echo "==> No hay Gradle wrapper, generándolo..."
    if command -v gradle &> /dev/null; then
        gradle wrapper --gradle-version 8.7
    else
        echo "==> 'gradle' no está instalado globalmente, descargando el wrapper manualmente..."
        mkdir -p gradle/wrapper
        curl -sSL -o gradle/wrapper/gradle-wrapper.jar \
            "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar"
        cat > gradle/wrapper/gradle-wrapper.properties << 'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF
        curl -sSL -o gradlew \
            "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradlew"
        curl -sSL -o gradlew.bat \
            "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradlew.bat"
        chmod +x gradlew
    fi
fi

# ----------------------------------------------------------------------------
# 2. Descargar el jar de la Montoya API si no lo tienes ya en libs/
#    (Burp lo expone en tiempo de ejecución, pero para COMPILAR necesitamos
#    el jar localmente como dependencia `compileOnly`).
# ----------------------------------------------------------------------------
MONTOYA_VERSION="2023.12.1"
LIBS_DIR="libs"
MONTOYA_JAR="$LIBS_DIR/montoya-api-${MONTOYA_VERSION}.jar"

mkdir -p "$LIBS_DIR"

if [ ! -f "$MONTOYA_JAR" ]; then
    echo "==> Intentando descargar Montoya API ${MONTOYA_VERSION} desde Maven Central..."
    if ! curl -sSL -f -o "$MONTOYA_JAR" \
        "https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/${MONTOYA_VERSION}/montoya-api-${MONTOYA_VERSION}.jar"; then
        echo ""
        echo "AVISO: No se pudo descargar automáticamente el jar de Montoya API."
        echo "Descárgalo manualmente así:"
        echo "  1. Abre Burp Suite -> pestaña 'Extensions' -> 'APIs'"
        echo "  2. Ahí Burp te da el jar / o el link al repo oficial:"
        echo "     https://github.com/PortSwigger/burp-extensions-montoya-api"
        echo "  3. Coloca el archivo .jar en: $SCRIPT_DIR/$LIBS_DIR/"
        echo "     con el nombre: montoya-api-${MONTOYA_VERSION}.jar"
        echo "  4. Vuelve a ejecutar ./build.sh"
        exit 1
    fi
    echo "==> Montoya API descargada en $MONTOYA_JAR"
fi

# ----------------------------------------------------------------------------
# 3. Asegurar que build.gradle.kts apunte al jar local si Maven Central no
#    tiene publicado el artefacto (fallback automático).
# ----------------------------------------------------------------------------
if ! grep -q "files(\"libs" build.gradle.kts 2>/dev/null; then
    echo "==> Añadiendo fallback local para Montoya API en build.gradle.kts..."
    # Se añade una segunda declaración de dependencia apuntando al jar local;
    # si la de Maven Central falla, Gradle igual encontrará esta.
    sed -i.bak 's#compileOnly("net.portswigger.burp.extensions:montoya-api:2023.12.1")#compileOnly("net.portswigger.burp.extensions:montoya-api:2023.12.1")\n    compileOnly(files("libs/montoya-api-2023.12.1.jar"))#' build.gradle.kts
    rm -f build.gradle.kts.bak
fi

# ----------------------------------------------------------------------------
# 4. Compilar el shadowJar (jar con todas las dependencias embebidas, listo
#    para cargar directamente en Burp sin instalar nada más).
# ----------------------------------------------------------------------------
echo "==> Compilando con Gradle (esto descarga OkHttp, Gson, SQLite JDBC, java-diff-utils desde Maven Central)..."
./gradlew clean shadowJar --console=plain

JAR_PATH=$(find build/libs -name "burp-ai-assistant-*.jar" | head -n 1)

if [ -z "$JAR_PATH" ]; then
    echo "ERROR: El build terminó pero no se encontró el jar generado en build/libs/"
    exit 1
fi

echo ""
echo "============================================================"
echo "  BUILD OK"
echo "  Jar generado en: $JAR_PATH"
echo ""
echo "  Para instalarlo en Burp:"
echo "    Extensions -> Installed -> Add -> Extension type: Java"
echo "    Selecciona: $SCRIPT_DIR/$JAR_PATH"
echo "============================================================"
