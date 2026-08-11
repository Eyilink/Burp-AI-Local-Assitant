package com.aiassist;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LLMClient {

    private final AppConfig config;
    private final OkHttpClient client;
    private final Gson gson = new Gson();

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    private static final String SYSTEM_PROMPT = """
            Eres un asistente de pentesting que ayuda a un humano a analizar UNA solicitud HTTP
            que el humano ya sospecha que es sospechosa. No escaneas nada por tu cuenta.
            NUNCA propongas enviar la solicitud; solo propones una modificación candidata.
            Responde EXCLUSIVAMENTE con un JSON válido, sin texto antes ni después, con este esquema:
            {
              "vuln_hypothesis": "string corto",
              "reasoning": "string breve explicando por qué",
              "payloads": [
                {"category": "string", "payload": "string", "where_to_insert": "string", "source": "string|null"}
              ],
              "proposed_edits": [
                {"target_line_hint": "string", "old_value": "string", "new_value": "string"}
              ]
            }
            Si no tienes suficiente evidencia para una hipótesis sólida, dilo en "reasoning" y deja
            "payloads" y "proposed_edits" como listas vacías. No inventes CVEs ni detalles no observados.
            """;

    public LLMClient(AppConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(config.llmTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    /**
     * @param userContextBlock ya viene armado por PromptBuilder (request actual + sospecha + historial relevante + kb)
     */
    public VulnSuggestion suggest(String userContextBlock) throws IOException {
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.2);
        options.addProperty("num_predict", 700);

        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContextBlock);

        JsonObject body = new JsonObject();
        body.addProperty("model", config.llmModel);
        body.addProperty("stream", false);
        body.add("messages", gson.toJsonTree(List.of(systemMsg, userMsg)));
        body.add("options", options);

        RequestBody requestBody = RequestBody.create(gson.toJson(body), JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
                .url(config.llmBaseUrl + "/api/chat")
                .post(requestBody)
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("LLM local no respondió correctamente: HTTP " + resp.code());
            }
            if (resp.body() == null) {
                throw new IllegalStateException("Respuesta vacía del LLM local");
            }
            String raw = resp.body().string();
            String content = extractOllamaContent(raw);
            return parseSuggestion(content);
        }
    }

    private String extractOllamaContent(String raw) {
        // Formato Ollama /api/chat: {"message": {"role": "assistant", "content": "..."}, ...}
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
        return json.getAsJsonObject("message").get("content").getAsString();
    }

    private VulnSuggestion parseSuggestion(String content) {
        // Limpieza defensiva: modelos pequeños a veces envuelven en ```json ... ```
        String cleaned = content.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        cleaned = cleaned.trim();

        JsonObject obj = JsonParser.parseString(cleaned).getAsJsonObject();

        List<PayloadCandidate> payloads = new ArrayList<>();
        JsonArray payloadsArr = obj.getAsJsonArray("payloads");
        if (payloadsArr != null) {
            for (JsonElement el : payloadsArr) {
                JsonObject p = el.getAsJsonObject();
                String source = (p.has("source") && !p.get("source").isJsonNull())
                        ? p.get("source").getAsString() : null;
                payloads.add(new PayloadCandidate(
                        getOrDefault(p, "category", "desconocido"),
                        getOrDefault(p, "payload", ""),
                        getOrDefault(p, "where_to_insert", ""),
                        source
                ));
            }
        }

        List<ProposedEdit> edits = new ArrayList<>();
        JsonArray editsArr = obj.getAsJsonArray("proposed_edits");
        if (editsArr != null) {
            for (JsonElement el : editsArr) {
                JsonObject e = el.getAsJsonObject();
                edits.add(new ProposedEdit(
                        getOrDefault(e, "target_line_hint", ""),
                        getOrDefault(e, "old_value", ""),
                        getOrDefault(e, "new_value", "")
                ));
            }
        }

        String hypothesis = getOrDefault(obj, "vuln_hypothesis", "sin hipótesis clara");
        String reasoning = getOrDefault(obj, "reasoning", "");

        return new VulnSuggestion(hypothesis, reasoning, payloads, edits);
    }

    private String getOrDefault(JsonObject obj, String key, String def) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : def;
    }

    // ---- Clases de datos (equivalentes a los data class de Kotlin) ----

    public static class VulnSuggestion {
        public final String vulnHypothesis;
        public final String reasoning;
        public final List<PayloadCandidate> payloads;
        public final List<ProposedEdit> proposedEdits;

        public VulnSuggestion(String vulnHypothesis, String reasoning,
                               List<PayloadCandidate> payloads, List<ProposedEdit> proposedEdits) {
            this.vulnHypothesis = vulnHypothesis;
            this.reasoning = reasoning;
            this.payloads = payloads;
            this.proposedEdits = proposedEdits;
        }
    }

    public static class PayloadCandidate {
        public final String category;
        public final String payload;
        public final String whereToInsert;
        public final String source; // puede ser null

        public PayloadCandidate(String category, String payload, String whereToInsert, String source) {
            this.category = category;
            this.payload = payload;
            this.whereToInsert = whereToInsert;
            this.source = source;
        }
    }

    public static class ProposedEdit {
        public final String targetLineHint;
        public final String oldValue;
        public final String newValue;

        public ProposedEdit(String targetLineHint, String oldValue, String newValue) {
            this.targetLineHint = targetLineHint;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }
}
