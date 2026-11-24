package cn.zhixing.travel.rag;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import cn.zhixing.travel.knowledge.KnowledgeRepository;
import cn.zhixing.travel.mapper.ConversationMapper;
import cn.zhixing.travel.utils.JsonUtil;
import cn.zhixing.travel.utils.RedisKeys;
import cn.zhixing.travel.utils.RedisUtil;

import com.fasterxml.jackson.core.type.TypeReference;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class RagContentRetriever implements ContentRetriever {
    @Value("${spring.profiles.active:}")
    private String profiles = "";

    // Redis 缓存只存 List<String>（每段文本），避免缓存结构复杂化
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    @Autowired private EmbeddingModel embeddingModel;

    @Autowired private EmbeddingStore<TextSegment> embeddingStore;

    // primary：正常阈值向量检索；fallback：阈值更低，仅在 primary 无结果时兜底
    @Autowired private KnowledgeRepository documents;
    @Autowired private ConversationMapper conversations;

    @Autowired private RagQueryAugmenter queryAugmenter;

    @Autowired private RagReranker reranker;

    // ===== 可配置参数（application.yml）=====
    @Value("${rag.max-results:8}")
    private Integer ragMaxResults;

    @Value("${rag.min-score:0.6}")
    private Double ragMinScore;

    @Value("${rag.fallback-min-score:0.45}")
    private Double ragFallbackMinScore;

    @Value("${rag.parent-context.enabled:true}")
    private Boolean ragParentContextEnabled;

    @Value("${rag.parent-context.max-results:4}")
    private Integer ragParentContextMaxResults;

    @Value("${rag.cache.enabled:true}")
    private Boolean ragCacheEnabled;

    @Value("${rag.cache.version:v2}")
    private String ragCacheVersion;

    @Value("${rag.cache.ttl-seconds:3600}")
    private Long ragCacheTtlSeconds;

    @Value("${rag.hybrid.enabled:true}")
    private Boolean ragHybridEnabled;

    @Value("${rag.hybrid.alpha:0.85}")
    private Double ragHybridAlpha;

    @Value("${rag.hybrid.candidate-multiplier:4}")
    private Integer ragHybridCandidateMultiplier;

    @Value("${rag.rerank.enabled:false}")
    private Boolean ragRerankEnabled;

    @Value("${rag.rerank.candidates:12}")
    private Integer ragRerankCandidates;

    @Value("${rag.multi-query.max-merged-results:24}")
    private Integer ragMultiQueryMaxMergedResults;

    private ContentRetriever candidates(Filter filter, double minScore) {
        int limit =
                Math.max(
                        ragMaxResults * ragHybridCandidateMultiplier,
                        ragMultiQueryMaxMergedResults);
        return EmbeddingStoreContentRetriever.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .maxResults(limit)
                .minScore(minScore)
                .filter(filter)
                .build();
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.metadata() == null || query.metadata().chatMemoryId() == null)
            return List.of();
        // 模型只携带会话 ID；身份从服务端会话归属读取，不依赖异步线程的 ThreadLocal。
        String session =
                cn.zhixing.travel.memory.MemoryScope.sessionId(query.metadata().chatMemoryId());
        if (session.isBlank()) return List.of();
        var conversation = conversations.selectById(session);
        if (conversation == null || conversation.getIsDelete() != 0) return List.of();
        return retrieveForOwner(conversation.getUserId(), query.text());
    }

    /** 仅供已认证业务层调用；owner 不能来自用户传入的查询文本。 */
    public List<Content> retrieveForOwner(String owner, String text) {
        if (owner == null || owner.isBlank() || text == null || text.isBlank()) return List.of();
        var ready = documents.list(owner).stream().filter(d -> "READY".equals(d.status())).toList();
        // 没有资料时不调用向量模型、查询改写模型或向量库。
        if (ready.isEmpty()) return List.of();
        if (List.of(profiles.split(",")).contains("infra")) {
            throw new cn.zhixing.travel.exception.BusinessException(503, "当前尚未启用向量模型，无法搜索已上传资料");
        }
        Filter filter =
                metadataKey("owner_id")
                        .isEqualTo(owner)
                        .and(
                                metadataKey("document_id")
                                        .isIn(ready.stream().map(d -> d.id()).toList()));
        String revision =
                ready.stream()
                        .map(d -> d.id() + ":" + d.updatedAt())
                        .sorted()
                        .collect(java.util.stream.Collectors.joining("|"));
        Query query = Query.from(text);
        String cacheKey = ragCacheEnabled ? cacheKey(owner, revision, query) : null;
        if (ragCacheEnabled) {
            try {
                String json = RedisUtil.get(cacheKey);
                if (json != null) {
                    List<String> texts = JsonUtil.toObj(json, STRING_LIST);
                    if (texts != null && !texts.isEmpty()) {
                        ArrayList<Content> out = new ArrayList<>(texts.size());
                        for (String s : texts) {
                            out.add(Content.from(TextSegment.from(s, new Metadata())));
                        }
                        return out;
                    }
                }
            } catch (Exception ignored) {
                // 缓存层任何异常都不影响主检索链路
            }
        }

        List<Content> result = retrieveOnce(candidates(filter, ragMinScore), query);
        if (result.isEmpty() && ragFallbackMinScore < ragMinScore) {
            result = retrieveOnce(candidates(filter, ragFallbackMinScore), query);
        }

        if (ragCacheEnabled && !result.isEmpty()) {
            try {
                List<String> texts = result.stream().map(c -> c.textSegment().text()).toList();
                RedisUtil.set(cacheKey, texts, ragCacheTtlSeconds);
            } catch (Exception ignored) {
                // 同上：缓存失败不影响主链路
            }
        }
        return result;
    }

    private List<Content> retrieveOnce(ContentRetriever candidatesRetriever, Query query) {
        // 1) 生成查询变体（multi-query / hyde），扩大召回覆盖
        List<String> queryVariants = queryAugmenter.buildQueryVariants(query.text());

        // 2) 对每个变体做向量召回，合并为候选池（达到上限就停止）
        int mergedLimit = Math.max(ragMaxResults, ragMultiQueryMaxMergedResults);
        ArrayList<Content> candidates = new ArrayList<>(mergedLimit);
        for (String qText : queryVariants) {
            Query q =
                    query.metadata() == null
                            ? Query.from(qText)
                            : Query.from(qText, query.metadata());
            candidates.addAll(candidatesRetriever.retrieve(q));
            if (candidates.size() >= mergedLimit) {
                break;
            }
        }

        // 3) Hybrid：关键词打分 + 向量分数融合，得到更稳的排序（不额外依赖索引）
        String originalQuery = query.text().trim();
        List<Content> merged =
                RagScorer.hybridRank(
                        originalQuery, candidates, ragHybridEnabled, ragHybridAlpha, mergedLimit);

        // 4) Rerank：用 LLM 在 TopN 里再排一遍，只改变顺序
        if (ragRerankEnabled) {
            merged = reranker.rerank(originalQuery, merged, ragRerankCandidates);
        }

        // 为父段预留名额，而不是先放满子段再尝试追加。
        int reserve =
                ragParentContextEnabled
                        ? Math.min(ragParentContextMaxResults, Math.max(0, ragMaxResults / 2))
                        : 0;
        List<Content> children =
                new ArrayList<>(
                        merged.subList(0, Math.min(merged.size(), ragMaxResults - reserve)));
        List<Content> result =
                ragParentContextEnabled ? applyParentContext(children, ragMaxResults) : children;
        return result.stream()
                .map(
                        content -> {
                            var segment = content.textSegment();
                            String source = segment.metadata().getString("source");
                            String id = segment.metadata().getString("document_id");
                            return Content.from(
                                    TextSegment.from(
                                            "[资料：" + source + "；文档：" + id + "]\n" + segment.text(),
                                            segment.metadata()));
                        })
                .toList();
    }

    private String cacheKey(String owner, String revision, Query query) {
        // cacheKey = RedisKeys 前缀 + md5(版本|query|metadata)
        // version 用于隔离不同参数/策略下的缓存（手动 bump version 即可整体失效）
        Object meta = query.metadata();
        String base =
                ragCacheVersion.trim()
                        + "|"
                        + owner
                        + "|"
                        + revision
                        + "|"
                        + query.text().trim()
                        + "|"
                        + (meta == null ? "" : meta);
        String digest = md5Hex(base);
        return RedisKeys.getRagCacheKey(digest);
    }

    private String md5Hex(String s) {
        // 仅用于生成稳定短 key（无安全诉求）
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Content> applyParentContext(List<Content> childContents, int maxTotal) {
        // 父段补齐是“增强上下文连续性”的 best-effort：
        // - 保留子段（更精确）
        // - 额外补充少量父段（更完整）
        // - 总条数不超过 rag.max-results
        int parentBudget = ragParentContextMaxResults;
        if (parentBudget <= 0 || childContents.isEmpty()) {
            return childContents;
        }

        ArrayList<Content> merged = new ArrayList<>(childContents);
        Set<String> added = new HashSet<>();

        for (Content content : childContents) {
            if (merged.size() >= maxTotal || parentBudget <= 0) {
                break;
            }
            String parentId = content.textSegment().metadata().getString("parent_id");
            if (parentId == null || parentId.isBlank()) {
                continue;
            }
            if (!added.add(parentId)) {
                continue;
            }
            String parentText = content.textSegment().metadata().getString("parent_text");
            if (parentText == null || parentText.isBlank()) {
                continue;
            }
            Metadata metadata = content.textSegment().metadata().copy().put("chunk_type", "parent");
            merged.add(Content.from(TextSegment.from(parentText, metadata)));
            parentBudget--;
        }

        if (merged.size() > maxTotal) {
            return new ArrayList<>(merged.subList(0, maxTotal));
        }
        return merged;
    }
}
