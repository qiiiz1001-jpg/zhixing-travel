package cn.zhixing.travel.config;

import cn.zhixing.travel.agent.NormalService;
import cn.zhixing.travel.agent.accommodation.AccommodationPlannerService;
import cn.zhixing.travel.agent.food.FoodPlannerService;
import cn.zhixing.travel.agent.requirement.RequirementAgentService;
import cn.zhixing.travel.agent.transportation.TransportPlannerService;
import cn.zhixing.travel.agent.transportation.subagents.DrivingTransportSubService;
import cn.zhixing.travel.agent.transportation.subagents.FlightTransportSubService;
import cn.zhixing.travel.agent.transportation.subagents.TrainTransportSubService;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AgentFactory implements ApplicationContextAware {
    @org.springframework.beans.factory.annotation.Value("${memory.recent.max-messages:20}")
    private int planningMaxMessages;

    @org.springframework.beans.factory.annotation.Value("${mcp.max-tool-rounds:8}")
    private int maxToolRounds;

    @Autowired private ChatModel chatModel;

    @Autowired private ChatMemoryStore chatMemoryStore;

    @Autowired private ContentRetriever contentRetriever;

    @Autowired private StreamingChatModel streamingChatModel;

    @Autowired private ObjectProvider<McpToolConfig.ToolClients> externalTools;

    private ApplicationContext applicationContext;

    public <T> T getAgent(Class<T> agentClass) {
        return applicationContext.getBean(agentClass);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Bean
    public NormalService normalService(@Qualifier("toolsNormal") McpToolProvider toolsNormal) {

        return AiServices.builder(NormalService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                // memory 和 会话id 存redis
                .chatMemoryProvider(
                        memoryId ->
                                MessageWindowChatMemory.builder()
                                        .id(memoryId)
                                        .chatMemoryStore(chatMemoryStore)
                                        .maxMessages(1000)
                                        .build())
                .contentRetriever(contentRetriever)
                //                .tools(new TimeTool(), ragTool, emailTool)
                .toolProvider(toolsNormal)
                .build();
    }

    @Bean
    public AccommodationPlannerService accommodationPlannerService(
            @Qualifier("toolsAccommodation") McpToolProvider toolsAccommodation) {
        if (!hasToolService("hotel")) return (sessionId, prompt) -> "未查询到住宿实时信息。";
        return plannerWithTools(AccommodationPlannerService.class, toolsAccommodation)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                // memory 和 会话id 存redis
                .chatMemoryProvider(
                        memoryId ->
                                MessageWindowChatMemory.builder()
                                        .id("accommodation:" + memoryId)
                                        .chatMemoryStore(chatMemoryStore)
                                        .maxMessages(planningMaxMessages)
                                        .build())
                .contentRetriever(contentRetriever)
                //                .tools(new TimeTool(), ragTool, emailTool)
                .build();
    }

    @Bean
    public FoodPlannerService foodPlannerService(
            @Qualifier("toolsFood") McpToolProvider toolsFood) {
        return plannerWithTools(FoodPlannerService.class, toolsFood)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                // memory 和 会话id 存redis
                .chatMemoryProvider(
                        memoryId ->
                                MessageWindowChatMemory.builder()
                                        .id("food:" + memoryId)
                                        .chatMemoryStore(chatMemoryStore)
                                        .maxMessages(planningMaxMessages)
                                        .build())
                .contentRetriever(contentRetriever)
                //                .tools(new TimeTool(), ragTool, emailTool)
                .build();
    }

    @Bean
    public TransportPlannerService transportPlannerService() {
        return AiServices.builder(TransportPlannerService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                // memory 和 会话id 存redis
                .chatMemoryProvider(
                        memoryId ->
                                MessageWindowChatMemory.builder()
                                        .id("transport:" + memoryId)
                                        .chatMemoryStore(chatMemoryStore)
                                        .maxMessages(planningMaxMessages)
                                        .build())
                //                .tools(new TimeTool(), ragTool, emailTool)
                .build();
    }

    @Bean
    public RequirementAgentService requirementAgentService(
            @Qualifier("toolsRequirement") McpToolProvider toolsRequirement) {
        return plannerWithTools(RequirementAgentService.class, toolsRequirement)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(
                        memoryId ->
                                MessageWindowChatMemory.builder()
                                        .id("requirement:" + memoryId)
                                        .chatMemoryStore(chatMemoryStore)
                                        .maxMessages(planningMaxMessages)
                                        .build())
                .build();
    }

    @Bean
    public DrivingTransportSubService drivingTransportSubService(
            @Qualifier("toolsDriving") McpToolProvider toolsDriving) {
        return plannerWithTools(DrivingTransportSubService.class, toolsDriving)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                // memory 和 会话id 存redis
                .chatMemoryProvider(
                        memoryId ->
                                MessageWindowChatMemory.builder()
                                        .id("driving:" + memoryId)
                                        .chatMemoryStore(chatMemoryStore)
                                        .maxMessages(planningMaxMessages)
                                        .build())
                .contentRetriever(contentRetriever)
                //                .tools(new TimeTool(), ragTool, emailTool)
                .build();
    }

    @Bean
    public FlightTransportSubService flightTransportSubService(
            @Qualifier("toolsFlight") McpToolProvider toolsFlight) {
        if (!hasToolService("flight")) return (sessionId, prompt) -> "未查询到航班实时信息。";
        return plannerWithTools(FlightTransportSubService.class, toolsFlight)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                // memory 和 会话id 存redis
                .chatMemoryProvider(
                        memoryId ->
                                MessageWindowChatMemory.builder()
                                        .id("flight:" + memoryId)
                                        .chatMemoryStore(chatMemoryStore)
                                        .maxMessages(planningMaxMessages)
                                        .build())
                .contentRetriever(contentRetriever)
                //                .tools(new TimeTool(), ragTool, emailTool)
                .build();
    }

    @Bean
    public TrainTransportSubService trainTransportSubService(
            @Qualifier("toolsTrain") McpToolProvider toolsTrain) {
        if (!hasToolService("train")) return (sessionId, prompt) -> "未查询到火车票实时信息。";
        return plannerWithTools(TrainTransportSubService.class, toolsTrain)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                // memory 和 会话id 存redis
                .chatMemoryProvider(
                        memoryId ->
                                MessageWindowChatMemory.builder()
                                        .id("train:" + memoryId)
                                        .chatMemoryStore(chatMemoryStore)
                                        .maxMessages(planningMaxMessages)
                                        .build())
                .contentRetriever(contentRetriever)
                //                .tools(new TimeTool(), ragTool, emailTool)
                .build();
    }

    private boolean hasToolService(String name) {
        var tools = externalTools.getIfAvailable();
        return tools != null && tools.clients().containsKey(name);
    }

    private <T> AiServices<T> plannerWithTools(Class<T> service, McpToolProvider provider) {
        return AiServices.builder(service)
                .toolProvider(provider)
                .maxSequentialToolsInvocations(maxToolRounds)
                .hallucinatedToolNameStrategy(
                        request ->
                                ToolExecutionResultMessage.from(
                                        request, "该工具当前不可用。请使用已提供的工具或说明缺少实时数据。"));
    }

    /** 核心线程数 默认的核心线程数为1 */
    private static final int CORE_POOL_SIZE = 6;

    /** 最大线程数 默认的最大线程数是Integer.MAX_VALUE 即2<sup>31</sup>-1 */
    private static final int MAX_POOL_SIZE = 50;

    /** 缓冲队列数 默认的缓冲队列数是Integer.MAX_VALUE 即2<sup>31</sup>-1 */
    private static final int QUEUE_CAPACITY = 100;

    /** 允许线程空闲时间 默认的线程空闲时间为60秒 */
    private static final int KEEP_ALIVE_SECONDS = 30;

    /** 线程池前缀名 */
    private static final String THREAD_NAME_PREFIX = "Task_Service_Async_";

    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        return newExecutor(THREAD_NAME_PREFIX);
    }

    /** 父分支等待交通子图时，子任务使用独立线程池，避免多会话嵌套等待耗尽父池。 */
    @Bean("transportExecutor")
    public ThreadPoolTaskExecutor transportExecutor() {
        return newExecutor("Transport_Async_");
    }

    private ThreadPoolTaskExecutor newExecutor(String prefix) {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(CORE_POOL_SIZE);
        taskExecutor.setMaxPoolSize(MAX_POOL_SIZE);
        taskExecutor.setQueueCapacity(QUEUE_CAPACITY);
        taskExecutor.setKeepAliveSeconds(KEEP_ALIVE_SECONDS);
        taskExecutor.setThreadNamePrefix(prefix);
        taskExecutor.setAllowCoreThreadTimeOut(false);
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 线程池初始化
        taskExecutor.initialize();
        return taskExecutor;
    }
}
