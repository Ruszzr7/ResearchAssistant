package com.research.assistant.service.pdf.layout;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects inline mathematical fragments from the PDF's existing Unicode text layer. */
@Component
public class PaperMathContentEnricher {

    static final String VERSION = "inline-math-v1";
    private static final Pattern TOKEN = Pattern.compile("\\S+");

    public String version() {
        return VERSION;
    }

    public PaperLayoutArtifact enrich(PaperLayoutArtifact artifact) {
        if (artifact == null) throw new IllegalArgumentException("版面制品不能为空");
        List<DocumentBlock> blocks = artifact.blocks().stream().map(this::enrich).toList();
        String parserVersion = artifact.parserVersion().endsWith("+" + VERSION)
                ? artifact.parserVersion() : artifact.parserVersion() + "+" + VERSION;
        return new PaperLayoutArtifact(artifact.paperId(), artifact.documentHash(), parserVersion,
                artifact.layoutConfidence(), artifact.generatedAt(), artifact.pageCount(), blocks,
                artifact.provenance());
    }

    DocumentBlock enrich(DocumentBlock block) {
        MathContentProfile profile = detect(block.text());
        return new DocumentBlock(block.id(), block.page(), block.bbox(), block.role(),
                block.readingOrder(), block.sectionPath(), block.text(), block.latex(),
                block.tableText(), block.confidence(), block.contentMode(), profile);
    }

    MathContentProfile detect(String text) {
        if (text == null || text.isBlank()) return MathContentProfile.none(VERSION);
        List<TokenSignal> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        int signals = 0;
        while (matcher.find()) {
            Set<String> reasons = signals(matcher.group());
            if (!reasons.isEmpty()) {
                tokens.add(new TokenSignal(matcher.start(), matcher.end(), reasons));
                signals += reasons.size();
            }
        }
        if (tokens.isEmpty()) return MathContentProfile.none(VERSION);

        List<InlineMathFragment> fragments = merge(text, tokens);
        int nonWhitespace = (int) text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
        int mathCharacters = fragments.stream().mapToInt(fragment ->
                (int) fragment.sourceText().codePoints().filter(cp -> !Character.isWhitespace(cp)).count()).sum();
        double density = nonWhitespace == 0 ? 0 : Math.min(1, (double) mathCharacters / nonWhitespace);
        MathContentLevel level = signals >= 4 && (fragments.size() >= 2 || density >= 0.12)
                ? MathContentLevel.MATH_RICH : MathContentLevel.LIGHT;
        return new MathContentProfile(level, density, signals, fragments, VERSION);
    }

    private List<InlineMathFragment> merge(String text, List<TokenSignal> tokens) {
        List<InlineMathFragment> result = new ArrayList<>();
        int start = tokens.get(0).start();
        int end = tokens.get(0).end();
        Set<String> reasons = new LinkedHashSet<>(tokens.get(0).signals());
        for (int index = 1; index < tokens.size(); index++) {
            TokenSignal token = tokens.get(index);
            String gap = text.substring(end, token.start());
            if (gap.matches("[\\s,;:()\\[\\]{}]*") && token.start() - end <= 4) {
                end = token.end();
                reasons.addAll(token.signals());
                continue;
            }
            result.add(fragment(text, start, end, reasons));
            start = token.start();
            end = token.end();
            reasons = new LinkedHashSet<>(token.signals());
        }
        result.add(fragment(text, start, end, reasons));
        return List.copyOf(result);
    }

    private InlineMathFragment fragment(String text, int start, int end, Set<String> signals) {
        double confidence = Math.min(0.96, 0.58 + signals.size() * 0.09);
        return new InlineMathFragment(start, end, text.substring(start, end), confidence,
                List.copyOf(signals));
    }

    private Set<String> signals(String token) {
        Set<String> result = new LinkedHashSet<>();
        boolean hasLetterOrDigit = token.codePoints().anyMatch(Character::isLetterOrDigit);
        if (token.codePoints().anyMatch(this::isMathUnicode)) result.add("MATH_UNICODE");
        if (token.codePoints().anyMatch(this::isGreek)) result.add("GREEK_SYMBOL");
        if (token.codePoints().anyMatch(this::isScriptCharacter)) result.add("SCRIPT_CHARACTER");
        if (token.matches(".*[A-Za-z0-9][_=^][A-Za-z0-9{].*")) result.add("SCRIPT_SYNTAX");
        if (hasLetterOrDigit && token.matches(".*(?:<=|>=|!=|:=|\\+=|-=|[=<>±×÷∕]).*")) {
            result.add("RELATION_OR_OPERATOR");
        }
        if (token.contains("$$") || token.matches(".*\\\\(?:frac|sum|prod|mathbb|mathbf|mu|in)\\b.*")) {
            result.add("LATEX_SYNTAX");
        }
        return result;
    }

    private boolean isMathUnicode(int codePoint) {
        return (codePoint >= 0x2200 && codePoint <= 0x22ff)
                || (codePoint >= 0x27c0 && codePoint <= 0x27ef)
                || (codePoint >= 0x2980 && codePoint <= 0x29ff)
                || (codePoint >= 0x2a00 && codePoint <= 0x2aff)
                || codePoint == 0x00b1 || codePoint == 0x00d7 || codePoint == 0x00f7;
    }

    private boolean isGreek(int codePoint) {
        return (codePoint >= 0x0370 && codePoint <= 0x03ff)
                || (codePoint >= 0x1f00 && codePoint <= 0x1fff);
    }

    private boolean isScriptCharacter(int codePoint) {
        return (codePoint >= 0x2070 && codePoint <= 0x209f)
                || (codePoint >= 0x1d400 && codePoint <= 0x1d7ff);
    }

    private record TokenSignal(int start, int end, Set<String> signals) {
    }
}
