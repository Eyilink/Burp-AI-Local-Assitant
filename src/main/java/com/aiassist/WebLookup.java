package com.aiassist;

import okhttp3.OkHttpClient;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Búsqueda de referencia SOLO sobre dominios de confianza (PortSwigger, HackTricks,
 * PayloadsAllTheThings). Recibe únicamente queries genéricas generadas por el LLM
 * (ej. "blind sqli time based postgres"), NUNCA el contenido de las solicitudes/respuestas
 * de Burp. Esto se garantiza a nivel de contrato: este módulo no recibe una referencia
 * al HttpRequestResponse en ningún momento, solo Strings ya desacoplados.
 */
public class WebLookup {

    private final AppConfig config;
    private final OkHttpClient client;

    public WebLookup(AppConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public static class LookupResult {
        public final String title;
        public final String snippet;
        public final String source;

        public LookupResult(String title, String snippet, String source) {
            this.title = title;
            this.snippet = snippet;
            this.source = source;
        }
    }

    /**
     * Estrategia:
     * 1. Primero intenta resolver contra la base de conocimiento LOCAL (repo clonado /
     *    índice cacheado de HackTricks y PayloadsAllTheThings) -> config.localKnowledgeBasePath.
     * 2. Solo si no hay match local, sale a internet, y únicamente a los dominios whitelisteados.
     */
    public List<LookupResult> lookup(String genericQuery) {
        sanitizeQueryOrThrow(genericQuery);

        List<LookupResult> local = searchLocalKnowledgeBase(genericQuery);
        if (!local.isEmpty()) return local;

        List<LookupResult> results = new ArrayList<>();
        for (String domain : config.allowedLookupDomains) {
            try {
                results.addAll(searchViaSiteRestrictedQuery(genericQuery, domain));
            } catch (Exception ignored) {
                // Fallo en un dominio no debe tumbar el resto de la búsqueda
            }
        }
        return results.stream().limit(5).collect(Collectors.toList());
    }

    private void sanitizeQueryOrThrow(String query) {
        if (query.length() >= 300) {
            throw new IllegalArgumentException("Query de lookup demasiado larga; probablemente contiene datos que no deberían salir.");
        }
        String lower = query.toLowerCase();
        List<String> suspiciousPatterns = List.of("cookie:", "authorization:", "set-cookie", "bearer ", "session=");
        for (String p : suspiciousPatterns) {
            if (lower.contains(p)) {
                throw new IllegalArgumentException("Query de lookup bloqueada: parece contener datos sensibles de una solicitud real.");
            }
        }
    }

    private List<LookupResult> searchLocalKnowledgeBase(String query) {
        File kbDir = new File(config.localKnowledgeBasePath);
        if (!kbDir.exists()) return List.of();

        List<String> tokens = Stream.of(query.toLowerCase().split("\\W+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toList());

        List<LookupResult> hits = new ArrayList<>();

        try (Stream<java.nio.file.Path> walk = Files.walk(kbDir.toPath())) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            String lowerText = text.toLowerCase();
                            long matches = tokens.stream().filter(lowerText::contains).count();
                            int threshold = Math.max(tokens.size() / 2, 1);
                            if (matches >= threshold) {
                                String relative = kbDir.toPath().relativize(p).toString();
                                String name = p.getFileName().toString().replaceFirst("\\.(md|txt)$", "");
                                hits.add(new LookupResult(name, extractRelevantSnippet(text, tokens), "local-kb://" + relative));
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            return List.of();
        }

        return hits.stream()
                .sorted(Comparator.comparingInt((LookupResult r) -> r.snippet.length()).reversed())
                .limit(5)
                .collect(Collectors.toList());
    }

    private String extractRelevantSnippet(String text, List<String> tokens) {
        String[] lines = text.split("\n");
        int idx = -1;
        for (int i = 0; i < lines.length; i++) {
            String lower = lines[i].toLowerCase();
            boolean match = tokens.stream().anyMatch(lower::contains);
            if (match) { idx = i; break; }
        }
        if (idx == -1) {
            return text.length() > 300 ? text.substring(0, 300) : text;
        }
        int start = Math.max(0, idx - 2);
        int end = Math.min(lines.length, idx + 8);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) sb.append(lines[i]).append("\n");
        return sb.toString();
    }

    /**
     * Fallback online: requiere una API de búsqueda configurada (Brave Search API, SerpAPI, etc.)
     * o, alternativamente, hacer fetch directo a una URL conocida dentro del dominio permitido.
     * Aquí se deja como stub explícito porque depende de qué API de búsqueda contrates;
     * lo importante es que el `site:` SIEMPRE está forzado al dominio whitelisteado.
     */
    private List<LookupResult> searchViaSiteRestrictedQuery(String query, String domain) {
        if (!config.allowedLookupDomains.contains(domain)) {
            throw new IllegalArgumentException("Dominio no whitelisteado: " + domain);
        }

        // Ejemplo con Brave Search API (requiere API key propia, no incluida aquí).
        // String url = "https://api.search.brave.com/res/v1/web/search?q=" + encode(query + " site:" + domain);
        // Request req = new Request.Builder().url(url).header("X-Subscription-Token", "<TU_API_KEY>").build();
        // ... parsear resultados y filtrar que la URL devuelta realmente pertenezca a `domain` con isUrlAllowed().

        return List.of(); // placeholder: implementar según la API de búsqueda que elijas
    }

    /** Valida que una URL de resultado realmente pertenece a un dominio whitelisteado, antes de mostrarla/usarla. */
    public boolean isUrlAllowed(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
            if (host == null) host = "";
        } catch (Exception e) {
            host = "";
        }
        for (String domain : config.allowedLookupDomains) {
            if (host.equals(domain) || host.endsWith("." + domain) || url.contains(domain)) {
                return true;
            }
        }
        return false;
    }
}
