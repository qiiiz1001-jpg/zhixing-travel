package cn.zhixing.travel.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<KnowledgeDocument> row =
            (rs, index) ->
                    new KnowledgeDocument(
                            rs.getString("id"),
                            rs.getString("owner_id"),
                            rs.getString("filename"),
                            rs.getString("content_type"),
                            rs.getString("sha256"),
                            rs.getLong("size_bytes"),
                            rs.getInt("text_chars"),
                            rs.getInt("chunk_count"),
                            rs.getString("status"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("updated_at", OffsetDateTime.class));

    public KnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgeDocument> list(String owner) {
        return jdbc.query(
                "SELECT * FROM travel_knowledge_document WHERE owner_id=? AND status<>'DELETED'"
                        + " ORDER BY created_at DESC",
                row,
                owner);
    }

    public KnowledgeDocument find(String owner, String id) {
        return jdbc
                .query(
                        "SELECT * FROM travel_knowledge_document WHERE owner_id=? AND id=?",
                        row,
                        owner,
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public String revision(String owner) {
        return list(owner).stream()
                .filter(document -> "READY".equals(document.status()))
                .map(document -> document.id() + ":" + document.updatedAt())
                .sorted()
                .collect(java.util.stream.Collectors.joining("|"));
    }

    public void insert(
            String owner,
            String id,
            String filename,
            String type,
            String hash,
            long size,
            int chars) {
        jdbc.update(
                "INSERT INTO"
                    + " travel_knowledge_document(id,owner_id,filename,content_type,sha256,size_bytes,text_chars,status)"
                    + " VALUES (?,?,?,?,?,?,?,'INDEXING')",
                id,
                owner,
                filename,
                type,
                hash,
                size,
                chars);
    }

    public int finish(String owner, String id, int chunks) {
        return jdbc.update(
                "UPDATE travel_knowledge_document SET"
                    + " status='READY',chunk_count=?,updated_at=CURRENT_TIMESTAMP WHERE owner_id=?"
                    + " AND id=? AND status='INDEXING'",
                chunks,
                owner,
                id);
    }

    public void fail(String owner, String id) {
        jdbc.update(
                "UPDATE travel_knowledge_document SET status='FAILED',updated_at=CURRENT_TIMESTAMP"
                        + " WHERE owner_id=? AND id=? AND status='INDEXING'",
                owner,
                id);
    }

    /** 先关闭检索可见性，再清理向量；清理失败时仍可重复删除。 */
    public void markDeleted(String owner, String id) {
        jdbc.update(
                "UPDATE travel_knowledge_document SET status='DELETED',updated_at=CURRENT_TIMESTAMP"
                        + " WHERE owner_id=? AND id=?",
                owner,
                id);
    }
}
