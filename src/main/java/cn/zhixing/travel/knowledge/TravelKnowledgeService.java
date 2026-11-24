package cn.zhixing.travel.knowledge;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import cn.zhixing.travel.exception.BusinessException;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class TravelKnowledgeService {
    private final TravelDocumentParser parser;
    private final TravelKnowledgeSplitter splitter;
    private final KnowledgeRepository repository;
    private final EmbeddingModel model;
    private final EmbeddingStore<TextSegment> store;

    @Value("${rag.upload.max-chunks:1500}")
    private int maxChunks;

    @Value("${spring.profiles.active:}")
    private String profiles;

    public TravelKnowledgeService(
            TravelDocumentParser parser,
            TravelKnowledgeSplitter splitter,
            KnowledgeRepository repository,
            EmbeddingModel model,
            EmbeddingStore<TextSegment> store) {
        this.parser = parser;
        this.splitter = splitter;
        this.repository = repository;
        this.model = model;
        this.store = store;
    }

    public List<KnowledgeDocument> list(String owner) {
        return repository.list(owner);
    }

    public KnowledgeDocument upload(String owner, MultipartFile file) {
        if (List.of(profiles.split(",")).contains("infra"))
            throw new BusinessException(503, "当前为中间件联调模式，尚未启用向量模型；不能完成资料入库");
        var parsed = parser.parse(file);
        String id = UUID.randomUUID().toString();
        var segments = splitter.split(owner, id, parsed);
        if (segments.size() > maxChunks) throw new BusinessException(413, "文档片段过多，请拆分后上传");
        String hash;
        try {
            hash =
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(parsed.bytes()));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        try {
            repository.insert(
                    owner,
                    id,
                    parsed.filename(),
                    parsed.contentType(),
                    hash,
                    parsed.bytes().length,
                    parsed.text().length());
        } catch (DuplicateKeyException error) {
            throw new BusinessException(409, "相同资料已上传或正在入库，请勿重复上传");
        }
        try {
            for (int start = 0; start < segments.size(); start += 32) {
                var batch = segments.subList(start, Math.min(start + 32, segments.size()));
                var embeddings = model.embedAll(batch).content();
                if (embeddings == null || embeddings.size() != batch.size())
                    throw new IllegalStateException("向量返回数量不一致");
                store.addAll(embeddings, batch);
            }
            if (repository.finish(owner, id, segments.size()) != 1) {
                throw new BusinessException(409, "资料在入库期间已被删除");
            }
            return repository.find(owner, id);
        } catch (Exception error) {
            repository.fail(owner, id);
            try {
                removeVectors(owner, id);
            } catch (Exception ignored) {
                /* FAILED 状态也不会进入检索集合。 */
            }
            if (error instanceof BusinessException business) throw business;
            throw new BusinessException(502, "资料入库失败，请检查向量模型配置后重试；失败片段不会参与检索");
        }
    }

    public void delete(String owner, String id) {
        if (repository.find(owner, id) == null) throw new BusinessException(404, "资料不存在");
        repository.markDeleted(owner, id);
        try {
            removeVectors(owner, id);
        } catch (Exception error) {
            throw new BusinessException(502, "资料已停止参与检索，向量清理失败，请重复删除重试");
        }
    }

    private void removeVectors(String owner, String id) {
        store.removeAll(
                metadataKey("owner_id")
                        .isEqualTo(owner)
                        .and(metadataKey("document_id").isEqualTo(id)));
    }
}
