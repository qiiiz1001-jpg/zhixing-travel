package cn.zhixing.travel.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.exception.BusinessException;
import cn.zhixing.travel.knowledge.*;
import cn.zhixing.travel.mapper.ConversationMapper;
import cn.zhixing.travel.model.Conversation;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 真实 PostgreSQL / pgvector，只有 Embedding 使用明确测试替身；所有测试数据在结束后清理。 */
@EnabledIfEnvironmentVariable(named = "RUN_INFRA_TESTS", matches = "true")
class PrivateKnowledgeIntegrationTest {
    private final String alice = "rag-test-a-" + UUID.randomUUID();
    private final String bob = "rag-test-b-" + UUID.randomUUID();
    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final RagQueryAugmenter augmenter = mock(RagQueryAugmenter.class);
    private KnowledgeRepository repository;
    private JdbcTemplate jdbc;
    private PgVectorEmbeddingStore store;
    private TravelKnowledgeService service;
    private ConversationMapper conversations;

    @BeforeEach
    void setup() {
        String host = System.getenv().getOrDefault("PGVECTOR_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("PGVECTOR_PORT", "15432"));
        String database = System.getenv().getOrDefault("TRAVEL_DATABASE", "travel_planner_db");
        String user = System.getenv().getOrDefault("PGVECTOR_USER", "chat");
        String password = System.getenv("PGVECTOR_PASSWORD");
        var source =
                new DriverManagerDataSource(
                        "jdbc:postgresql://" + host + ":" + port + "/" + database, user, password);
        new ResourceDatabasePopulator(new ClassPathResource("db/03-knowledge.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        repository = new KnowledgeRepository(jdbc);
        store =
                PgVectorEmbeddingStore.builder()
                        .host(host)
                        .port(port)
                        .database(database)
                        .user(user)
                        .password(password)
                        .table("agent_embeddings")
                        .dimension(1024)
                        .createTable(false)
                        .build();
        float[] vector = new float[1024];
        vector[0] = 1;
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(vector)));
        when(model.embedAll(anyList()))
                .thenAnswer(
                        inv -> {
                            List<?> batch = inv.getArgument(0);
                            return Response.from(
                                    batch.stream().map(ignored -> Embedding.from(vector)).toList());
                        });
        when(augmenter.buildQueryVariants(anyString()))
                .thenAnswer(inv -> List.of(inv.<String>getArgument(0)));
        conversations = mock(ConversationMapper.class);
        when(conversations.selectById("session-a"))
                .thenReturn(Conversation.builder().userId(alice).isDelete((short) 0).build());
        var parser = new TravelDocumentParser();
        ReflectionTestUtils.setField(parser, "maxBytes", 20971520L);
        ReflectionTestUtils.setField(parser, "maxChars", 200000);
        service =
                new TravelKnowledgeService(
                        parser, new TravelKnowledgeSplitter(), repository, model, store);
        ReflectionTestUtils.setField(service, "maxChunks", 1500);
        ReflectionTestUtils.setField(service, "profiles", "");
    }

    private RagContentRetriever retriever() {
        var retriever = new RagContentRetriever();
        Object[][] fields = {
            {"embeddingModel", model},
            {"embeddingStore", store},
            {"documents", repository},
            {"conversations", conversations},
            {"queryAugmenter", augmenter},
            {"reranker", mock(RagReranker.class)},
            {"ragMaxResults", 8},
            {"ragMinScore", 0.0},
            {"ragFallbackMinScore", 0.0},
            {"ragParentContextEnabled", true},
            {"ragParentContextMaxResults", 4},
            {"ragCacheEnabled", false},
            {"ragCacheVersion", "private-v1"},
            {"ragCacheTtlSeconds", 3600L},
            {"ragHybridEnabled", true},
            {"ragHybridAlpha", 0.85},
            {"ragHybridCandidateMultiplier", 4},
            {"ragRerankEnabled", false},
            {"ragRerankCandidates", 12},
            {"ragMultiQueryMaxMergedResults", 24}
        };
        for (Object[] field : fields)
            ReflectionTestUtils.setField(retriever, (String) field[0], field[1]);
        return retriever;
    }

    private MockMultipartFile file(String text) {
        return new MockMultipartFile(
                "file", "好友攻略.md", "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void clean() {
        if (store != null)
            for (String owner : List.of(alice, bob))
                store.removeAll(
                        dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey(
                                        "owner_id")
                                .isEqualTo(owner));
        if (jdbc != null)
            jdbc.update(
                    "DELETE FROM travel_knowledge_document WHERE owner_id IN (?,?)", alice, bob);
    }

    @Test
    void uploadRetrieveAfterRestartIsolateAndDelete() {
        var a = service.upload(alice, file("西安住宿经验：选择安静街区。Alice 私人资料。"));
        service.upload(bob, file("西安住宿经验：Bob 绝密资料。"));
        assertEquals("READY", a.status());
        assertTrue(a.chunkCount() > 0);
        assertThrows(
                BusinessException.class,
                () -> service.upload(alice, file("西安住宿经验：选择安静街区。Alice 私人资料。")));
        var query =
                Query.from(
                        "西安住宿",
                        dev.langchain4j.rag.query.Metadata.from(
                                UserMessage.from("西安住宿"), "session-a", List.of()));
        // 新建检索器没有上传时的内存父段，仍能从向量 metadata 回填。
        var result = retriever().retrieve(query);
        assertFalse(result.isEmpty());
        String texts =
                result.stream()
                        .map(c -> c.textSegment().text())
                        .collect(java.util.stream.Collectors.joining());
        assertTrue(texts.contains("Alice"));
        assertTrue(texts.contains("好友攻略.md"));
        assertFalse(texts.contains("Bob"));
        assertTrue(
                result.stream()
                        .anyMatch(
                                c ->
                                        "parent"
                                                .equals(
                                                        c.textSegment()
                                                                .metadata()
                                                                .getString("chunk_type"))));
        assertThrows(BusinessException.class, () -> service.delete(bob, a.id()));
        String previous = repository.revision(alice);
        service.delete(alice, a.id());
        assertNotEquals(previous, repository.revision(alice));
        assertTrue(retriever().retrieve(query).isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM agent_embeddings WHERE metadata->>'document_id'=?",
                        Integer.class,
                        a.id()));
        service.delete(alice, a.id()); // 重复清理幂等。
    }

    @Test
    void missingIdentityOrEmptyLibraryNeverInvokesModels() {
        var retriever = retriever();
        clearInvocations(model, augmenter);
        assertTrue(retriever.retrieve(Query.from("西安")).isEmpty());
        assertTrue(retriever.retrieveForOwner(alice, "西安").isEmpty());
        verifyNoInteractions(model, augmenter);
    }

    @Test
    void versionedPlanningMemoryStillRetrievesFromOriginalOwnedSession() {
        service.upload(alice, file("Alice 的西安私人攻略"));
        String scoped = cn.zhixing.travel.memory.MemoryScope.encode("session-a", 7, 2);
        var query =
                Query.from(
                        "西安",
                        dev.langchain4j.rag.query.Metadata.from(
                                UserMessage.from("西安"), scoped, List.of()));
        var result = retriever().retrieve(query);
        assertFalse(result.isEmpty());
        verify(conversations).selectById("session-a");
        assertTrue(
                result.stream()
                        .anyMatch(content -> content.textSegment().text().contains("Alice")));
    }

    @Test
    void interruptedIngestionIsNotSearchable() {
        when(model.embedAll(anyList()))
                .thenThrow(new IllegalStateException("embedding unavailable"));
        assertThrows(BusinessException.class, () -> service.upload(alice, file("失败的私人资料")));
        assertEquals("FAILED", repository.list(alice).get(0).status());
        assertTrue(retriever().retrieveForOwner(alice, "私人资料").isEmpty());
    }

    @Test
    void langchainInjectsPrivateExcerptsBeforeCallingPlannerModel() {
        service.upload(alice, file("Alice 的西安住宿攻略：优先选择安静街区。"));
        var chat = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(chat.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenAnswer(
                        inv -> {
                            dev.langchain4j.model.chat.request.ChatRequest request =
                                    inv.getArgument(0);
                            String messages = request.messages().toString();
                            assertTrue(messages.contains("Alice"));
                            assertTrue(messages.contains("好友攻略.md"));
                            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                                    .aiMessage(
                                            dev.langchain4j.data.message.AiMessage.from(
                                                    "推荐安静街区，资料：好友攻略.md"))
                                    .build();
                        });
        var agent =
                dev.langchain4j.service.AiServices.builder(
                                cn.zhixing.travel.agent.food.FoodPlannerService.class)
                        .chatModel(chat)
                        .contentRetriever(retriever())
                        .chatMemoryProvider(
                                id ->
                                        dev.langchain4j.memory.chat.MessageWindowChatMemory
                                                .builder()
                                                .id("food:" + id)
                                                .maxMessages(10)
                                                .build())
                        .build();
        assertTrue(agent.chat("session-a", "西安住宿经验").contains("资料："));
        verify(chat, times(1)).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class));
    }

    @Test
    void authenticatedControllerUploadSearchAndDeleteUseServerIdentity() throws Exception {
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new cn.zhixing.travel.controller.KnowledgeController(
                                        service, retriever()))
                        .setControllerAdvice(
                                new cn.zhixing.travel.exception.GlobalExceptionHandler())
                        .build();
        cn.zhixing.travel.utils.RequestContext.set(
                new cn.zhixing.travel.utils.RequestContextInfo(alice));
        try {
            var response =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.multipart(
                                                    "/knowledge/documents")
                                            .file(file("Alice 西安旅行私人经验")))
                            .andReturn()
                            .getResponse();
            var json =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertTrue(json.path("success").asBoolean());
            String id = json.path("data").path("id").asText();
            var result =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post("/knowledge/search")
                                            .contentType("application/json")
                                            .content("{\"query\":\"西安\"}"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(StandardCharsets.UTF_8);
            assertTrue(result.contains("Alice"));
            cn.zhixing.travel.utils.RequestContext.set(
                    new cn.zhixing.travel.utils.RequestContextInfo(bob));
            var denied =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.delete(
                                            "/knowledge/documents/" + id))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(StandardCharsets.UTF_8);
            assertTrue(denied.contains("404"));
            assertEquals("READY", repository.find(alice, id).status());
            cn.zhixing.travel.utils.RequestContext.set(
                    new cn.zhixing.travel.utils.RequestContextInfo(alice));
            mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                            "/knowledge/documents/" + id));
            assertEquals("DELETED", repository.find(alice, id).status());
        } finally {
            cn.zhixing.travel.utils.RequestContext.remove();
        }
    }
}
