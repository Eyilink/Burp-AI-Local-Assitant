package com.aiassist;

import burp.api.montoya.MontoyaApi;

import java.util.Arrays;
import java.util.List;

/**
 * Configuración centralizada. En una v2 esto se podría exponer en un panel de settings
 * dentro de la pestaña de la extensión; de momento son defaults sensatos + persistencia
 * simple vía api.persistence() (project-scoped, no sale de Burp).
 */
public class AppConfig {

    // --- LLM local ---
    public final String llmBaseUrl;
    public final String llmModel;
    public final long llmTimeoutSeconds;

    // --- Web lookup whitelist (solo para queries genéricas, nunca contenido de Burp) ---
    public final List<String> allowedLookupDomains;

    // --- Ruta local de caché (repo PayloadsAllTheThings clonado, índice HackTricks, etc.) ---
    public final String localKnowledgeBasePath;

    // --- SQLite para indexar HTTP history ---
    public final String sqliteDbPath;

    // --- Límites de contexto para modelos pequeños (3B) ---
    public final int maxHistoryItemsInPrompt;
    public final int maxBodyCharsInPrompt;

    public AppConfig() {
        this.llmBaseUrl = "http://localhost:11434"; // Ollama por defecto
        this.llmModel = "qwen2.5-coder:3b";          // o "dolphin3-qwen2.5-3b"
        this.llmTimeoutSeconds = 60;

        this.allowedLookupDomains = Arrays.asList(
                "portswigger.net",
                "book.hacktricks.wiki",
                "hacktricks.boitatech.com.br",
                "github.com/swisskyrepo/PayloadsAllTheThings",
                "raw.githubusercontent.com/swisskyrepo/PayloadsAllTheThings"
        );

        this.localKnowledgeBasePath = System.getProperty("user.home") + "/.ai-assistant/kb";
        this.sqliteDbPath = System.getProperty("user.home") + "/.ai-assistant/history_index.db";

        this.maxHistoryItemsInPrompt = 5;
        this.maxBodyCharsInPrompt = 2000;
    }

    public static AppConfig loadOrDefault(MontoyaApi api) {
        // Placeholder simple: en el futuro, leer/escribir con api.persistence().extensionData()
        // para permitir que el usuario cambie modelo/URL desde un panel de settings.
        return new AppConfig();
    }
}
