# Burp AI Assistant (payload suggester) — versión Java

Extensión de Burp Suite (Montoya API, Java) que sugiere hipótesis de vulnerabilidad y
payloads candidatos sobre **una solicitud que el usuario ya sospecha que es rara**. No
escanea nada por sí sola, no envía solicitudes, y toda modificación pasa por un diff
`+/-` que el usuario debe aprobar explícitamente antes de aplicarla al editor (el envío
sigue siendo manual).

## Principios de diseño

- **Cero automatización de red por parte del agente.** Nunca llama a envíos de solicitudes.
- **Aprobación humana obligatoria** para cualquier cambio, mostrado como diff línea a línea.
- **LLM 100% local** (Ollama / LM Studio), modelo pequeño (≤3B, ej. `qwen2.5-coder:3b` o `dolphin3-qwen2.5-3b`).
- **Búsqueda externa aislada**: solo queries genéricas (nunca contenido de Burp) hacia
  PortSwigger, HackTricks y PayloadsAllTheThings, con preferencia por una base de
  conocimiento **local cacheada** antes de salir a internet.
- **Historial de Burp resumido, no volcado completo**: se indexa en SQLite y solo se
  recuperan las N entradas más relevantes para el request actual (evita desbordar el
  contexto de un modelo de 3B).

## Estructura del proyecto

```
src/main/java/com/aiassist/
 ├── BurpAIAssistant.java     # entry point, registra menú contextual + panel
 ├── AppConfig.java           # configuración (modelo, dominios permitidos, rutas)
 ├── LLMClient.java           # cliente HTTP a Ollama + clases VulnSuggestion/PayloadCandidate/ProposedEdit
 ├── DiffEngine.java          # aplica edits y genera diff +/- línea a línea
 ├── HistoryIndexer.java      # indexa HTTP history en SQLite, retrieval TF-IDF simple
 ├── WebLookup.java           # búsqueda whitelisteada + KB local cacheada
 ├── PromptBuilder.java       # arma el prompt final (request + sospecha + historial + KB)
 └── ui/AnalysisPanel.java    # panel Swing: sospecha, análisis, diff visual, aprobar/rechazar
```

## Requisitos

1. **Java 17+** y **Gradle** (o genera el wrapper con `gradle wrapper`).
2. **Ollama** instalado y corriendo (`ollama serve`), con el modelo descargado:
   ```bash
   ollama pull qwen2.5-coder:3b
   # o
   ollama pull dolphin3:3b   # nombre exacto según el tag disponible en tu registro
   ```
3. **Montoya API jar**: descárgalo desde el propio Burp (`Extensions -> APIs`) o desde el
   repo oficial de PortSwigger, y añádelo como dependencia `compileOnly` si no está en
   Maven Central con la versión exacta que uses.
4. (Opcional) Clona localmente para la base de conocimiento offline:
   ```bash
   mkdir -p ~/.ai-assistant/kb
   git clone https://github.com/swisskyrepo/PayloadsAllTheThings ~/.ai-assistant/kb/PayloadsAllTheThings
   # Para HackTricks, exporta/clona el contenido markdown que te interese a la misma carpeta.
   ```

## Build

```bash
cd burp-ai-assistant-java
gradle shadowJar
```

Esto genera `build/libs/burp-ai-assistant-0.1.0.jar`. En Burp: **Extensions -> Installed -> Add**,
selecciona el jar, tipo `Java`.

## Uso

1. En Repeater/Proxy, sobre la solicitud que te parece rara: click derecho -> **"Analizar con AI Assistant"**.
2. En la pestaña **AI Assistant**, escribe (opcional) tu sospecha en el cuadro de texto.
3. Pulsa **"Analizar solicitud actual"**. El agente:
   - Recupera el historial relevante (mismo host/endpoint) ya indexado.
   - Busca referencias en la KB local o, si no hay match, en los sitios whitelisteados.
   - Llama al LLM local con todo el contexto y pide una hipótesis + payload en JSON.
4. Revisa el diff `+/-` mostrado.
5. **Aplicar cambios al editor** solo escribe el texto propuesto en el buffer editable;
   tú decides si lo envías (Burp sigue pidiendo tu click en "Send").

Pulsa **"Reindexar HTTP history"** de vez en cuando (o después de una sesión de recorrido
manual del target) para que el módulo de historial tenga datos frescos.

## Limitaciones conocidas / TODO

- El método `onApprove()` en `AnalysisPanel.java` deja un comentario explícito: la
  integración real con el `HttpRequestEditor` de Repeater (para que "Aplicar" escriba
  directamente en el editor visible) depende de cómo captures la referencia al editor
  activo vía `ContextMenuEvent.messageEditorRequestResponse()`. Está preparado el punto
  de enganche, falta conectar el editor concreto según tu versión de Montoya API.
- `WebLookup.searchViaSiteRestrictedQuery` es un stub: necesitas conectar una API de
  búsqueda real (Brave Search API, SerpAPI, etc.) o implementar fetch directo a páginas
  conocidas dentro del dominio permitido. La función `isUrlAllowed` ya valida cualquier
  URL antes de usarla, úsala también ahí.
- Modelos de 3B alucinan con más frecuencia que modelos grandes: por eso el prompt fuerza
  JSON estricto y el `DiffEngine` verifica que el `old_value` propuesto exista literalmente
  en la solicitud antes de aplicar nada — si no existe, se marca como advertencia en vez
  de aplicarse a ciegas.
- No se ha podido compilar/testear en este entorno (sin acceso a Maven Central desde el
  sandbox); revisa versiones de dependencias (`montoya-api`, `okhttp`, `gson`, `sqlite-jdbc`,
  `java-diff-utils`) contra lo disponible en tu máquina antes del build.
