package cn.zhixing.travel.memory;

import cn.zhixing.travel.exception.BusinessException;
import cn.zhixing.travel.memory.MemoryModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class MemoryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public MemoryRepository(
            JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public Profile profile(String user) {
        Profile value = read(user, "profile", Profile.class);
        if (value != null && (value.version() < 0 || value.preferences() == null))
            throw new IllegalStateException("偏好格式无效");
        return value == null ? Profile.empty() : value;
    }

    public void withProfileLock(String user, Runnable publish) {
        transactions.executeWithoutResult(
                status -> {
                    insert(user, "profile", Profile.empty());
                    jdbc.queryForObject(
                            "SELECT value::text FROM store WHERE"
                                    + " namespace=ARRAY['travel-memory','user',?]::text[] AND"
                                    + " key='profile' FOR UPDATE",
                            String.class,
                            user);
                    publish.run();
                });
    }

    /** 模型调用在事务外；版本检查、更新和操作回执在短事务内一起提交。 */
    public Update update(
            String user, long expectedVersion, String operationId, List<Operation> operations) {
        UUID.fromString(operationId);
        return transactions.execute(
                status -> {
                    insert(user, "profile", Profile.empty());
                    String body =
                            jdbc.queryForObject(
                                    "SELECT value::text FROM store WHERE"
                                        + " namespace=ARRAY['travel-memory','user',?]::text[] AND"
                                        + " key='profile' FOR UPDATE",
                                    String.class,
                                    user);
                    Profile current = decode(body, Profile.class);
                    if (read(user, "operation:" + operationId, Profile.class) != null)
                        return new Update(current, true);
                    if (current.version() != expectedVersion)
                        throw new BusinessException(409, "偏好已被其他请求更新，请重新读取后保存");
                    Map<String, String> preferences = new HashMap<>(current.preferences());
                    for (Operation op : operations) {
                        if ("DELETE".equals(op.action())) preferences.remove(op.category());
                        else preferences.put(op.category(), op.content());
                    }
                    long version =
                            current.version() + (preferences.equals(current.preferences()) ? 0 : 1);
                    Profile updated =
                            new Profile(
                                    version,
                                    Map.copyOf(preferences),
                                    OffsetDateTime.now().toString(),
                                    operationId);
                    jdbc.update(
                            "UPDATE store SET value=?::jsonb,updated_at=now() WHERE"
                                    + " namespace=ARRAY['travel-memory','user',?]::text[] AND"
                                    + " key='profile'",
                            encode(updated),
                            user);
                    insert(user, "operation:" + operationId, updated);
                    return new Update(updated, false);
                });
    }

    public void assertSession(String user, String session) {
        Boolean owned =
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM conversation WHERE id=? AND user_id=? AND"
                                + " is_delete=0)",
                        Boolean.class,
                        session,
                        user);
        if (!Boolean.TRUE.equals(owned)) throw new BusinessException(403, "无权访问此会话记忆");
    }

    public long latestSequence(String session) {
        return jdbc.queryForObject(
                "SELECT coalesce(max(memory_seq),0) FROM message WHERE conversation_id=? AND"
                        + " is_delete=0",
                Long.class,
                session);
    }

    public List<History> recent(String session, long floor, int limit) {
        List<History> list =
                jdbc.query(
                        "SELECT memory_seq,role,content FROM message WHERE conversation_id=? AND"
                                + " is_delete=0 AND memory_seq>? ORDER BY memory_seq DESC LIMIT ?",
                        (rs, i) -> new History(rs.getLong(1), rs.getShort(2), rs.getString(3)),
                        session,
                        floor,
                        limit);
        Collections.reverse(list);
        return list;
    }

    /** 只回放已完成回复之前的业务历史，本轮刚落库但尚未回答的用户消息不能重复灌入。 */
    public List<History> completedRecent(String session, long floor, int limit) {
        List<History> list =
                jdbc.query(
                        "SELECT memory_seq,role,content FROM message WHERE conversation_id=? AND"
                            + " is_delete=0 AND role IN (1,2) AND memory_seq>? AND"
                            + " memory_seq<=(SELECT coalesce(max(memory_seq),0) FROM message WHERE"
                            + " conversation_id=? AND role=2 AND is_delete=0) ORDER BY memory_seq"
                            + " DESC LIMIT ?",
                        (rs, i) -> new History(rs.getLong(1), rs.getShort(2), rs.getString(3)),
                        session,
                        floor,
                        session,
                        limit);
        Collections.reverse(list);
        return list;
    }

    public List<History> pending(String session, Summary summary, long beforeSequence, int limit) {
        return jdbc.query(
                "SELECT memory_seq,role,content FROM message WHERE conversation_id=? AND"
                    + " is_delete=0 AND memory_seq<? AND (memory_seq>? OR (memory_seq=? AND ?>0))"
                    + " ORDER BY memory_seq LIMIT ?",
                (rs, i) -> new History(rs.getLong(1), rs.getShort(2), rs.getString(3)),
                session,
                beforeSequence,
                summary.coveredSequence(),
                summary.coveredSequence(),
                summary.coveredOffset(),
                limit);
    }

    public Summary summary(String user, String session) {
        return read(user, "summary:" + session, Summary.class);
    }

    /** 摘要只在原摘要版本和用户偏好上下文仍有效时发布。 */
    public boolean saveSummary(String user, String session, Summary before, Summary after) {
        String key = "summary:" + session;
        if (before == null) {
            return jdbc.update(
                            "INSERT INTO store(namespace,key,value,created_at,updated_at) SELECT"
                                + " ARRAY['travel-memory','user',?]::text[],?,?::jsonb,now(),now()"
                                + " WHERE coalesce((SELECT (value->>'version')::bigint FROM store"
                                + " WHERE namespace=ARRAY['travel-memory','user',?]::text[] AND"
                                + " key='profile'),0)=? ON CONFLICT DO NOTHING",
                            user,
                            key,
                            encode(after),
                            user,
                            after.contextVersion())
                    == 1;
        }
        return jdbc.update(
                        "UPDATE store SET value=?::jsonb,updated_at=now() WHERE"
                                + " namespace=ARRAY['travel-memory','user',?]::text[] AND key=? AND"
                                + " (value->>'revision')::bigint=? AND coalesce((SELECT"
                                + " (value->>'version')::bigint FROM store WHERE"
                                + " namespace=ARRAY['travel-memory','user',?]::text[] AND"
                                + " key='profile'),0)=?",
                        encode(after),
                        user,
                        key,
                        before.revision(),
                        user,
                        after.contextVersion())
                == 1;
    }

    private <T> T read(String user, String key, Class<T> type) {
        List<String> values =
                jdbc.query(
                        "SELECT value::text FROM store WHERE"
                                + " namespace=ARRAY['travel-memory','user',?]::text[] AND key=?",
                        (rs, i) -> rs.getString(1),
                        user,
                        key);
        return values.isEmpty() ? null : decode(values.get(0), type);
    }

    private void insert(String user, String key, Object value) {
        jdbc.update(
                "INSERT INTO store(namespace,key,value,created_at,updated_at) VALUES"
                        + " (ARRAY['travel-memory','user',?]::text[],?,?::jsonb,now(),now()) ON"
                        + " CONFLICT DO NOTHING",
                user,
                key,
                encode(value));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("记忆序列化失败", error);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception error) {
            throw new IllegalStateException("记忆读取失败", error);
        }
    }
}
