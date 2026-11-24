package cn.zhixing.travel.rag;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RagReranker {

    private static final Pattern INT_PATTERN = Pattern.compile("\\d+");

    @Autowired private ChatModel chatModel;

    List<Content> rerank(String query, List<Content> contents, int topN) {
        if (contents.size() < 2) {
            return contents;
        }

        int n = Math.min(contents.size(), Math.max(1, topN));
        String prompt = buildPrompt(query, contents.subList(0, n));
        String out = safeChat(prompt);
        if (out == null || out.isBlank()) {
            return contents;
        }

        List<Integer> order = parseIndices(out, n);
        if (order.isEmpty()) {
            return contents;
        }

        ArrayList<Content> reranked = new ArrayList<>(contents.size());
        boolean[] used = new boolean[n];
        for (Integer idx : order) {
            int i = idx - 1;
            if (i >= 0 && i < n && !used[i]) {
                used[i] = true;
                reranked.add(contents.get(i));
            }
        }
        for (int i = 0; i < n; i++) {
            if (!used[i]) {
                reranked.add(contents.get(i));
            }
        }
        if (contents.size() > n) {
            reranked.addAll(contents.subList(n, contents.size()));
        }
        return reranked;
    }

    private String buildPrompt(String query, List<Content> candidates) {
        StringBuilder sb =
                new StringBuilder(
                        """
                        你是检索结果重排序器。
                        给定用户问题和候选片段，按“与问题的相关性”从高到低排序。
                        要求：只输出 JSON 数组，内容为候选序号（从 1 开始），例如：[3,1,2]。不要输出任何解释。
                        用户问题：""");
        sb.append(query).append("\n\n候选片段：\n");

        for (int i = 0; i < candidates.size(); i++) {
            String t = candidates.get(i).textSegment().text().trim();
            if (t.length() > 380) {
                t = t.substring(0, 380);
            }
            sb.append(i + 1).append(". ").append(t).append("\n\n");
        }
        return sb.toString();
    }

    private List<Integer> parseIndices(String s, int maxIndex) {
        Matcher m = INT_PATTERN.matcher(s);
        ArrayList<Integer> out = new ArrayList<>();
        boolean[] seen = new boolean[maxIndex + 1];
        while (m.find()) {
            int v = Integer.parseInt(m.group());
            if (v >= 1 && v <= maxIndex && !seen[v]) {
                seen[v] = true;
                out.add(v);
            }
        }
        return out;
    }

    private String safeChat(String prompt) {
        try {
            String out = chatModel.chat(prompt);
            return out == null ? null : out.trim();
        } catch (Exception e) {
            return null;
        }
    }
}
