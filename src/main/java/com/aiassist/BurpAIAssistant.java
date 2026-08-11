package com.aiassist;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import com.aiassist.ui.AnalysisPanel;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Punto de entrada de la extensión.
 *
 * Principios de diseño (no negociables):
 *  - El agente NUNCA envía solicitudes por sí mismo.
 *  - Toda modificación propuesta se muestra como diff +/- y requiere aprobación explícita.
 *  - El LLM corre en local (Ollama/LM Studio). Nada del contenido de las solicitudes
 *    sale a internet salvo consultas genéricas hacia dominios whitelisteados.
 *  - El análisis solo se dispara cuando el usuario lo pide explícitamente
 *    (click derecho -> "Analizar con AI Assistant"), nunca de forma pasiva/automática.
 */
public class BurpAIAssistant implements BurpExtension {

    private MontoyaApi api;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("AI Assistant (payload suggester)");

        AppConfig config = AppConfig.loadOrDefault(api);

        LLMClient llmClient = new LLMClient(config);
        WebLookup webLookup = new WebLookup(config);
        HistoryIndexer historyIndexer = new HistoryIndexer(api, config);

        // Panel principal (sospecha del usuario, diff, aprobar/rechazar)
        AnalysisPanel panel = new AnalysisPanel(api, llmClient, webLookup, historyIndexer);
        api.userInterface().registerSuiteTab("AI Assistant", panel);

        // Menú contextual en Repeater/Proxy/Intruder: "Analizar con AI Assistant"
        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                Optional<burp.api.montoya.ui.editor.extension.EditorCreationContext> ignore = Optional.empty();
                if (event.messageEditorRequestResponse().isEmpty()) {
                    return Collections.emptyList();
                }

                JMenuItem item = new JMenuItem("Analizar con AI Assistant");
                item.addActionListener(e -> {
                    var reqResp = event.messageEditorRequestResponse().get();
                    // Pasamos el request/response actual al panel; no se envía nada por sí solo.
                    panel.loadRequestResponse(reqResp.requestResponse());
                });
                return Collections.singletonList(item);
            }
        });

        api.logging().logToOutput("AI Assistant cargado. Modelo LLM local: " + config.llmModel + " @ " + config.llmBaseUrl);
    }
}
