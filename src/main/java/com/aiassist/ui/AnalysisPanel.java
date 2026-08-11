package com.aiassist.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.aiassist.*;

import javax.swing.*;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.List;
import java.util.stream.Collectors;

public class AnalysisPanel extends JPanel {

    private final MontoyaApi api;
    private final LLMClient llmClient;
    private final WebLookup webLookup;
    private final HistoryIndexer historyIndexer;

    private final AppConfig config;
    private final DiffEngine diffEngine = new DiffEngine();
    private final PromptBuilder promptBuilder;

    private HttpRequestResponse currentReqResp;
    private String lastProposedRequest;

    // --- UI components ---
    private final JTextArea suspicionField = new JTextArea(3, 60);
    private final JButton analyzeButton = new JButton("Analizar solicitud actual");
    private final JButton reindexButton = new JButton("Reindexar HTTP history");
    private final JLabel hypothesisLabel = new JLabel(" ");
    private final JTextPane diffPane = new JTextPane();
    private final JButton approveButton = new JButton("Aplicar cambios al editor");
    private final JButton rejectButton = new JButton("Descartar");
    private final JLabel statusLabel = new JLabel(" ");

    public AnalysisPanel(MontoyaApi api, LLMClient llmClient, WebLookup webLookup, HistoryIndexer historyIndexer) {
        super(new BorderLayout());
        this.api = api;
        this.llmClient = llmClient;
        this.webLookup = webLookup;
        this.historyIndexer = historyIndexer;
        this.config = AppConfig.loadOrDefault(api);
        this.promptBuilder = new PromptBuilder(config);

        suspicionField.setLineWrap(true);
        suspicionField.setWrapStyleWord(true);
        suspicionField.setToolTipText("Escribe aquí por qué crees que esta solicitud es sospechosa (opcional pero recomendado)");

        approveButton.setEnabled(false);
        rejectButton.setEnabled(false);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(new JLabel("Sospecha / instrucción (opcional):"), BorderLayout.NORTH);
        topPanel.add(new JScrollPane(suspicionField), BorderLayout.CENTER);

        JPanel buttonsPanel = new JPanel();
        buttonsPanel.add(analyzeButton);
        buttonsPanel.add(reindexButton);
        topPanel.add(buttonsPanel, BorderLayout.SOUTH);

        diffPane.setEditable(false);
        diffPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(hypothesisLabel, BorderLayout.NORTH);
        centerPanel.add(new JScrollPane(diffPane), BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel();
        bottomPanel.add(approveButton);
        bottomPanel.add(rejectButton);
        bottomPanel.add(statusLabel);

        add(topPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        analyzeButton.addActionListener(e -> onAnalyze());
        reindexButton.addActionListener(e -> onReindex());
        approveButton.addActionListener(e -> onApprove());
        rejectButton.addActionListener(e -> onReject());
    }

    /** Llamado desde el context menu al elegir "Analizar con AI Assistant". */
    public void loadRequestResponse(HttpRequestResponse reqResp) {
        this.currentReqResp = reqResp;
        statusLabel.setText("Solicitud cargada: " + reqResp.request().method() + " " + reqResp.request().path());
        diffPane.setText("");
        hypothesisLabel.setText(" ");
        approveButton.setEnabled(false);
        rejectButton.setEnabled(false);
    }

    private void onReindex() {
        statusLabel.setText("Reindexando HTTP history...");
        new Thread(() -> {
            try {
                historyIndexer.reindexFromBurpHistory();
                SwingUtilities.invokeLater(() -> statusLabel.setText("Historial reindexado."));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Error reindexando: " + e.getMessage()));
            }
        }).start();
    }

    private void onAnalyze() {
        if (currentReqResp == null) {
            statusLabel.setText("Selecciona primero una solicitud (click derecho -> Analizar con AI Assistant).");
            return;
        }

        analyzeButton.setEnabled(false);
        statusLabel.setText("Consultando LLM local...");

        new Thread(() -> {
            try {
                var request = currentReqResp.request();
                var response = currentReqResp.response();

                List<String> paramNames = request.parameters().stream()
                        .map(p -> p.name())
                        .collect(Collectors.toList());

                List<HistoryIndexer.HistoryEntry> relevantHistory = historyIndexer.findRelevant(
                        request.httpService().host(),
                        request.path(),
                        paramNames
                );

                // Query genérica de lookup basada en la sospecha del usuario o en el path,
                // NUNCA en el contenido crudo completo del request/response.
                String genericQuery = buildGenericLookupQuery(suspicionField.getText(), request.path());
                List<WebLookup.LookupResult> kbSnippets;
                try {
                    kbSnippets = webLookup.lookup(genericQuery);
                } catch (Exception ex) {
                    kbSnippets = List.of();
                }

                String promptContext = promptBuilder.build(
                        request.toString(),
                        response != null ? response.toString() : null,
                        suspicionField.getText(),
                        relevantHistory,
                        kbSnippets
                );

                LLMClient.VulnSuggestion suggestion = llmClient.suggest(promptContext);

                DiffEngine.ProposedResult proposed = diffEngine.buildProposedRequest(
                        request.toString(), suggestion.proposedEdits
                );
                lastProposedRequest = proposed.proposedRequest;

                List<DiffEngine.DiffLine> diff = diffEngine.diffLines(request.toString(), proposed.proposedRequest);

                SwingUtilities.invokeLater(() -> {
                    renderSuggestion(suggestion, diff, proposed.warnings);
                    boolean hasEdits = !suggestion.proposedEdits.isEmpty();
                    approveButton.setEnabled(hasEdits);
                    rejectButton.setEnabled(hasEdits);
                    analyzeButton.setEnabled(true);
                    statusLabel.setText("Análisis completo. Revisa el diff antes de aplicar.");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Error: " + e.getMessage());
                    analyzeButton.setEnabled(true);
                });
            }
        }).start();
    }

    private String buildGenericLookupQuery(String suspicion, String path) {
        // Query deliberadamente genérica: nunca incluye headers, cookies, tokens ni bodies.
        String base = (suspicion == null || suspicion.isBlank())
                ? "vulnerabilidad web genérica endpoint " + lastPathSegment(path)
                : suspicion;
        return base.length() > 150 ? base.substring(0, 150) : base;
    }

    private String lastPathSegment(String path) {
        if (path == null || path.isEmpty()) return "";
        String[] parts = path.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : path;
    }

    private void renderSuggestion(LLMClient.VulnSuggestion suggestion, List<DiffEngine.DiffLine> diff, List<String> warnings) {
        hypothesisLabel.setText("<html><b>Hipótesis:</b> " + suggestion.vulnHypothesis + " — " + suggestion.reasoning + "</html>");

        StyledDocument doc = diffPane.getStyledDocument();
        diffPane.setText("");

        SimpleAttributeSet addedStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(addedStyle, new Color(0, 128, 0));

        SimpleAttributeSet removedStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(removedStyle, new Color(178, 34, 34));

        SimpleAttributeSet contextStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(contextStyle, Color.DARK_GRAY);

        SimpleAttributeSet warnStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(warnStyle, new Color(200, 120, 0));

        try {
            if (!suggestion.payloads.isEmpty()) {
                doc.insertString(doc.getLength(), "Payloads sugeridos:\n", contextStyle);
                for (LLMClient.PayloadCandidate p : suggestion.payloads) {
                    String sourceSuffix = p.source != null ? " (fuente: " + p.source + ")" : "";
                    doc.insertString(doc.getLength(),
                            "  [" + p.category + "] " + p.payload + "  -> " + p.whereToInsert + sourceSuffix + "\n",
                            contextStyle);
                }
                doc.insertString(doc.getLength(), "\n--- DIFF PROPUESTO (revisar antes de aplicar) ---\n\n", contextStyle);
            }

            for (DiffEngine.DiffLine line : diff) {
                String prefix;
                SimpleAttributeSet style;
                switch (line.type) {
                    case ADDED -> { prefix = "+ "; style = addedStyle; }
                    case REMOVED -> { prefix = "- "; style = removedStyle; }
                    default -> { prefix = "  "; style = contextStyle; }
                }
                doc.insertString(doc.getLength(), prefix + line.text + "\n", style);
            }

            if (!warnings.isEmpty()) {
                doc.insertString(doc.getLength(), "\nAdvertencias:\n", warnStyle);
                for (String w : warnings) {
                    doc.insertString(doc.getLength(), "  ! " + w + "\n", warnStyle);
                }
            }
        } catch (Exception e) {
            api.logging().logToError("Error renderizando sugerencia: " + e.getMessage());
        }
    }

    private void onApprove() {
        if (currentReqResp == null || lastProposedRequest == null) return;

        // Aquí se aplica el texto propuesto al editor de Repeater/Proxy correspondiente.
        // IMPORTANTE: esto solo modifica el buffer editable; el envío sigue siendo una
        // acción manual y separada del usuario dentro de Burp (botón "Send" de Repeater).
        HttpRequest newRequest = HttpRequest.httpRequest(lastProposedRequest);
        // NOTA: currentReqResp es inmutable en Montoya. En una integración completa con
        // Repeater, aquí se debe escribir sobre el HttpRequestEditor asociado al
        // MessageEditorHttpRequestResponse original (capturado en el context menu),
        // en vez de solo construir un HttpRequest nuevo como se hace aquí a modo de esqueleto.

        statusLabel.setText("Cambios aplicados al editor. Revisa y decide si enviar manualmente.");
        approveButton.setEnabled(false);
        rejectButton.setEnabled(false);
    }

    private void onReject() {
        diffPane.setText("");
        hypothesisLabel.setText(" ");
        lastProposedRequest = null;
        approveButton.setEnabled(false);
        rejectButton.setEnabled(false);
        statusLabel.setText("Sugerencia descartada.");
    }
}
