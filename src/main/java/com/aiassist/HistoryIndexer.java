package com.aiassist;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Indexa el HTTP history de Burp (api.proxy().history()) en SQLite local y permite
 * recuperar solo las N entradas más relevantes para el request actual, en vez de
 * volcar todo el historial al LLM (que con un modelo de 3B desbordaría el contexto
 * y degradaría mucho la calidad de la respuesta).
 *
 * Nota de diseño: la relevancia se calcula con un TF-IDF simple sobre tokens
 * (host, path, nombres de parámetros). No requiere modelo de embeddings adicional,
 * lo cual mantiene el pipeline ligero y 100% local.
 */
public class HistoryIndexer {

    private final MontoyaApi api;
    private final AppConfig config;
    private final Connection connection;

    public static class HistoryEntry {
        public final long id;
        public final String host;
        public final String method;
        public final String path;
        public final int statusCode;
        public final String paramsSummary;
        public final String rawRequestSnippet;

        public HistoryEntry(long id, String host, String method, String path,
                             int statusCode, String paramsSummary, String rawRequestSnippet) {
            this.id = id;
            this.host = host;
            this.method = method;
            this.path = path;
            this.statusCode = statusCode;
            this.paramsSummary = paramsSummary;
            this.rawRequestSnippet = rawRequestSnippet;
        }
    }

    public HistoryIndexer(MontoyaApi api, AppConfig config) {
        this.api = api;
        this.config = config;
        try {
            File dbFile = new File(config.sqliteDbPath);
            if (dbFile.getParentFile() != null) dbFile.getParentFile().mkdirs();
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + config.sqliteDbPath);
            try (Statement st = connection.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS history_entries (
                            id INTEGER PRIMARY KEY,
                            host TEXT,
                            method TEXT,
                            path TEXT,
                            status_code INTEGER,
                            params_summary TEXT,
                            raw_request_snippet TEXT,
                            indexed_at INTEGER
                        )
                        """);
            }
        } catch (SQLException e) {
            throw new RuntimeException("No se pudo inicializar la base SQLite de historial", e);
        }
    }

    /** Re-indexa el historial completo de Burp. Llamar bajo demanda (botón "Reindexar"), no en cada análisis. */
    public void reindexFromBurpHistory() {
        List<ProxyHttpRequestResponse> history = api.proxy().history();

        try (Statement del = connection.createStatement()) {
            del.execute("DELETE FROM history_entries");
        } catch (SQLException e) {
            throw new RuntimeException("Error limpiando tabla de historial", e);
        }

        String insertSql = """
                INSERT INTO history_entries (host, method, path, status_code, params_summary, raw_request_snippet, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
            for (ProxyHttpRequestResponse entry : history) {
                var req = entry.request();
                var resp = entry.response();

                String host = req.httpService().host();
                String method = req.method();
                String path = req.path();
                int status = (resp != null) ? resp.statusCode() : -1;
                String params = req.parameters().stream()
                        .map(p -> p.name())
                        .collect(Collectors.joining(","));
                String snippet = req.toString();
                if (snippet.length() > config.maxBodyCharsInPrompt) {
                    snippet = snippet.substring(0, config.maxBodyCharsInPrompt);
                }

                stmt.setString(1, host);
                stmt.setString(2, method);
                stmt.setString(3, path);
                stmt.setInt(4, status);
                stmt.setString(5, params);
                stmt.setString(6, snippet);
                stmt.setLong(7, System.currentTimeMillis());
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Error insertando entradas de historial", e);
        }

        api.logging().logToOutput("HistoryIndexer: reindexadas " + history.size() + " entradas.");
    }

    /**
     * Devuelve las entradas más relevantes para el request actual, usando solapamiento
     * de tokens (host, path segments, nombres de parámetros) ponderado tipo TF-IDF simple.
     */
    public List<HistoryEntry> findRelevant(String currentHost, String currentPath, List<String> currentParams) {
        return findRelevant(currentHost, currentPath, currentParams, config.maxHistoryItemsInPrompt);
    }

    public List<HistoryEntry> findRelevant(String currentHost, String currentPath, List<String> currentParams, int limit) {
        Set<String> queryTokens = new HashSet<>();
        queryTokens.addAll(tokenize(currentHost));
        queryTokens.addAll(tokenize(currentPath));
        for (String p : currentParams) queryTokens.addAll(tokenize(p));

        List<HistoryEntry> all = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM history_entries")) {
            while (rs.next()) {
                all.add(new HistoryEntry(
                        rs.getLong("id"),
                        rs.getString("host"),
                        rs.getString("method"),
                        rs.getString("path"),
                        rs.getInt("status_code"),
                        rs.getString("params_summary"),
                        rs.getString("raw_request_snippet")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error leyendo historial indexado", e);
        }

        // TF-IDF simplificado: idf global calculado sobre "all", score = suma de idf de tokens en común
        Map<String, Integer> docFreq = new HashMap<>();
        Map<HistoryEntry, Set<String>> docsTokens = new LinkedHashMap<>();
        for (HistoryEntry entry : all) {
            Set<String> toks = new HashSet<>();
            toks.addAll(tokenize(entry.host));
            toks.addAll(tokenize(entry.path));
            toks.addAll(tokenize(entry.paramsSummary));
            for (String t : toks) docFreq.merge(t, 1, Integer::sum);
            docsTokens.put(entry, toks);
        }
        int n = Math.max(all.size(), 1);

        List<Map.Entry<HistoryEntry, Double>> scored = new ArrayList<>();
        for (Map.Entry<HistoryEntry, Set<String>> e : docsTokens.entrySet()) {
            double score = 0.0;
            for (String tok : e.getValue()) {
                if (queryTokens.contains(tok)) {
                    int df = docFreq.getOrDefault(tok, 1);
                    score += Math.log((double) n / df);
                }
            }
            scored.add(Map.entry(e.getKey(), score));
        }

        scored.sort((a, b) -> {
            boolean aHost = a.getKey().host.equals(currentHost);
            boolean bHost = b.getKey().host.equals(currentHost);
            if (aHost != bHost) return aHost ? -1 : 1;
            return Double.compare(b.getValue(), a.getValue());
        });

        return scored.stream().limit(limit).map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private List<String> tokenize(String s) {
        if (s == null) return List.of();
        return Arrays.stream(s.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toList());
    }
}
