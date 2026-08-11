package com.aiassist;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

import java.util.ArrayList;
import java.util.List;

/**
 * Aplica los ProposedEdit del LLM sobre el texto crudo de la solicitud (tal como se ve
 * en el editor de Repeater) de forma puramente textual: NUNCA se envía nada, solo se
 * calcula qué cambiaría y se lo mostramos al usuario como diff.
 */
public class DiffEngine {

    public enum DiffType { CONTEXT, ADDED, REMOVED }

    public static class DiffLine {
        public final DiffType type;
        public final String text;

        public DiffLine(DiffType type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    public static class ProposedResult {
        public final String proposedRequest;
        public final List<String> warnings;

        public ProposedResult(String proposedRequest, List<String> warnings) {
            this.proposedRequest = proposedRequest;
            this.warnings = warnings;
        }
    }

    /**
     * Genera una versión "propuesta" del request aplicando reemplazos simples de texto.
     * Si un oldValue no aparece literalmente en el request, ese edit se marca como no aplicable
     * y se muestra al usuario como advertencia en vez de aplicarlo a ciegas.
     */
    public ProposedResult buildProposedRequest(String originalRequest, List<LLMClient.ProposedEdit> edits) {
        String result = originalRequest;
        List<String> warnings = new ArrayList<>();

        for (LLMClient.ProposedEdit edit : edits) {
            if (edit.oldValue == null || edit.oldValue.isBlank()) {
                warnings.add("Edit ignorado (old_value vacío): " + edit.targetLineHint);
                continue;
            }
            if (!result.contains(edit.oldValue)) {
                warnings.add("No se encontró literalmente '" + edit.oldValue + "' en la solicitud (hint: "
                        + edit.targetLineHint + "). Revisar manualmente.");
                continue;
            }
            result = result.replaceFirst(java.util.regex.Pattern.quote(edit.oldValue), edit.newValue);
        }
        return new ProposedResult(result, warnings);
    }

    /**
     * Diff línea a línea al estilo unificado (+/-) entre el request original y el propuesto.
     */
    public List<DiffLine> diffLines(String original, String proposed) {
        List<String> originalLines = List.of(original.split("\n", -1));
        List<String> proposedLines = List.of(proposed.split("\n", -1));

        Patch<String> patch = DiffUtils.diff(originalLines, proposedLines);

        List<DiffLine> out = new ArrayList<>();
        int lastOriginalIndex = 0;

        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int position = delta.getSource().getPosition();
            for (int i = lastOriginalIndex; i < position; i++) {
                out.add(new DiffLine(DiffType.CONTEXT, originalLines.get(i)));
            }
            switch (delta.getType()) {
                case DELETE -> delta.getSource().getLines().forEach(l -> out.add(new DiffLine(DiffType.REMOVED, l)));
                case INSERT -> delta.getTarget().getLines().forEach(l -> out.add(new DiffLine(DiffType.ADDED, l)));
                case CHANGE -> {
                    delta.getSource().getLines().forEach(l -> out.add(new DiffLine(DiffType.REMOVED, l)));
                    delta.getTarget().getLines().forEach(l -> out.add(new DiffLine(DiffType.ADDED, l)));
                }
                default -> { }
            }
            lastOriginalIndex = position + delta.getSource().getLines().size();
        }
        for (int i = lastOriginalIndex; i < originalLines.size(); i++) {
            out.add(new DiffLine(DiffType.CONTEXT, originalLines.get(i)));
        }
        return out;
    }
}
