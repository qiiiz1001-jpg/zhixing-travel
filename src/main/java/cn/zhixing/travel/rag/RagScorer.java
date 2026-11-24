package cn.zhixing.travel.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RagScorer {

    private RagScorer() {}

    static List<Content> hybridRank(
            String query,
            List<Content> candidates,
            boolean hybridEnabled,
            double alpha,
            int limit) {
        if (candidates == null) {
            return List.of();
        }

        int cap = Math.max(1, limit);
        double a = clamp01(alpha);

        ArrayList<Content> scored = new ArrayList<>(candidates.size());
        for (Content c : candidates) {
            double sem = semanticScore(c);
            double lex = hybridEnabled ? lexicalScore(query, c.textSegment().text()) : 0.0;
            double s = hybridEnabled ? a * sem + (1.0 - a) * lex : sem;
            scored.add(withScore(c, s));
        }

        scored.sort((x, y) -> Double.compare(semanticScore(y), semanticScore(x)));
        return scored.size() <= cap ? scored : scored.subList(0, cap);
    }

    static double semanticScore(Content c) {
        Object s = c.metadata().get(ContentMetadata.SCORE);
        return s instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Content withScore(Content c, double score) {
        Map<ContentMetadata, Object> meta = new HashMap<>(c.metadata());
        meta.put(ContentMetadata.SCORE, score);
        return Content.from(c.textSegment(), meta);
    }

    private static double lexicalScore(String query, String text) {
        if (query == null || query.isBlank()) {
            return 0.0;
        }

        String q = normalizeForMatch(query);
        String t = normalizeForMatch(text);

        if (t.contains(q)) {
            return 1.0;
        }

        List<String> qTokens = tokens(q);
        Set<String> tTokens = new HashSet<>(tokens(t));
        int hit = 0;
        for (String token : qTokens) {
            if (tTokens.contains(token)) {
                hit++;
            }
        }
        return Math.min(1.0, (double) hit / (double) qTokens.size());
    }

    private static String normalizeForMatch(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static List<String> tokens(String s) {
        ArrayList<String> tokens = new ArrayList<>();
        String cleaned = s.trim();
        if (cleaned.isEmpty()) {
            return tokens;
        }

        for (String t : cleaned.split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 2) tokens.add(t);
        }
        if (!tokens.isEmpty()) {
            return tokens;
        }

        for (int i = 0; i < cleaned.length() - 1; i++) {
            char a = cleaned.charAt(i);
            char b = cleaned.charAt(i + 1);
            if (Character.isWhitespace(a) || Character.isWhitespace(b)) {
                continue;
            }
            tokens.add("" + a + b);
        }
        return tokens;
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }
}
