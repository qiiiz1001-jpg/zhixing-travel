package cn.zhixing.travel.agent.requirement;

import cn.zhixing.travel.agent.accommodation.AccommodationPlannerAgent;
import cn.zhixing.travel.agent.food.FoodFollowUp;
import cn.zhixing.travel.agent.food.FoodPlannerAgent;
import cn.zhixing.travel.agent.state.TravelWorkflowState;
import cn.zhixing.travel.agent.transportation.TransportPlannerAgent;
import cn.zhixing.travel.agent.transportation.subagents.DrivingTransportSubAgent;
import cn.zhixing.travel.agent.transportation.subagents.FlightTransportSubAgent;
import cn.zhixing.travel.agent.transportation.subagents.TrainTransportSubAgent;
import cn.zhixing.travel.exception.BusinessException;

import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
@Slf4j
@SuppressWarnings("ALL")
public class RequirementAgent {

    @Autowired private RequirementAgentService requirementAgentService;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    @Autowired
    @Qualifier("transportExecutor")
    private Executor transportExecutor;

    @Autowired private TransportPlannerAgent transportPlannerAgent;

    @Autowired private AccommodationPlannerAgent accommodationPlannerAgent;

    @Autowired private FoodPlannerAgent foodPlannerAgent;

    @Autowired private DrivingTransportSubAgent drivingTransportSubAgent;

    @Autowired private TrainTransportSubAgent trainTransportSubAgent;

    @Autowired private FlightTransportSubAgent flightTransportSubAgent;

    @Autowired(required = false)
    private cn.zhixing.travel.memory.TravelMemoryService memoryService;

    private CompiledGraph<TravelWorkflowState> graph;

    @Autowired private org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver checkpointSaver;

    @PostConstruct
    public void initGraph() throws GraphStateException {
        // 父图
        StateGraph<TravelWorkflowState> workflow =
                new StateGraph<>(TravelWorkflowState.scheme, TravelWorkflowState::new);
        StateGraph<TravelWorkflowState> accommodationSubgraph =
                new StateGraph<>(TravelWorkflowState.scheme, TravelWorkflowState::new);
        StateGraph<TravelWorkflowState> foodSubgraph =
                new StateGraph<>(TravelWorkflowState.scheme, TravelWorkflowState::new);
        // 交通子图
        StateGraph<TravelWorkflowState> transportSubgraph =
                new StateGraph<>(TravelWorkflowState.scheme, TravelWorkflowState::new);

        // 每轮都分析新消息；旧 missing=false 不能跳过变更提取。
        workflow.addNode(
                "collect",
                AsyncNodeAction.node_async(
                        state -> {
                            Map<String, Object> collected = new HashMap<>();
                            collected.put("sessionId", state.sessionId());
                            if ("重新查询实时信息".equals(state.userText().strip()) && !state.missing()) {
                                var refresh = new RequirementInfo();
                                refresh.setIntent("MODIFY");
                                refresh.setRequirements(new Requirements());
                                refresh.setAffectedBranches(
                                        java.util.List.of("transport", "accommodation", "food"));
                                return RequirementChanges.apply(state, refresh);
                            }
                            String foodAction = FoodFollowUp.explicitAction(state.userText());
                            if (!foodAction.isBlank()
                                    && !state.missing()
                                    && state.hasCompletePlan()) {
                                var change = new RequirementInfo();
                                change.setIntent("MODIFY");
                                change.setRequirements(new Requirements());
                                change.setFoodAction(foodAction);
                                return RequirementChanges.apply(state, change);
                            }
                            collected.put("userText", state.userText());
                            collected.put("requirements", state.requirements());
                            collected.put("hasCompletedPlan", state.hasCompletePlan());
                            collected.put("missing", state.missing());
                            collected.put("firstMissing", state.firstMissing());
                            collected.put(
                                    "longTermPreferences",
                                    state.value("longTermPreferences", Map.of()));
                            collected.put("summary", state.value("summarySections", Map.of()));
                            collected.put(
                                    "recentConversation",
                                    state.value("recentConversation", java.util.List.of()));
                            collected.put(
                                    "preferenceModes", state.value("preferenceModes", Map.of()));
                            collected.put(
                                    "previousSuggestions",
                                    Map.of(
                                            "transport",
                                                    cn.zhixing.travel.memory.TravelMemoryService
                                                            .clip(state.transportOutput(), 1800),
                                            "accommodation",
                                                    cn.zhixing.travel.memory.TravelMemoryService
                                                            .clip(
                                                                    state.accommodationOutput(),
                                                                    1800),
                                            "food",
                                                    cn.zhixing.travel.memory.TravelMemoryService
                                                            .clip(state.foodOutput(), 1800)));
                            String userMessage =
                                    com.alibaba.dashscope.utils.JsonUtils.toJson(collected);
                            RequirementInfo change =
                                    requirementAgentService.chat(
                                            state.executionMemoryId(), userMessage);
                            if (change == null) throw new IllegalStateException("需求分析未返回有效结构");
                            Map<String, Object> updates = new HashMap<>();
                            String clarification = change.getMemoryClarification();
                            if (clarification != null && !clarification.isBlank()) {
                                return Map.of(
                                        "nextStep",
                                        "memoryReply",
                                        "responseType",
                                        "CLARIFY",
                                        "output",
                                        cn.zhixing.travel.memory.TravelMemoryService.clip(
                                                clarification, 300));
                            }
                            if (memoryService != null
                                    && !state.<String>value("memoryUserId").orElse("").isBlank()
                                    && change.getMemoryOperations() != null
                                    && !change.getMemoryOperations().isEmpty()) {
                                updates.putAll(
                                        memoryService.applyOperations(
                                                state, change.getMemoryOperations()));
                            }
                            Map<String, Object> mergedState = new HashMap<>(state.data());
                            mergedState.putAll(updates);
                            TravelWorkflowState current = new TravelWorkflowState(mergedState);
                            if (Boolean.TRUE.equals(change.getMemoryOnly())) {
                                String notice = updates.getOrDefault("memoryNotice", "").toString();
                                updates.put(
                                        "output",
                                        notice.isBlank()
                                                ? "请说明要记住或忘记的交通、住宿或美食偏好。"
                                                : notice.strip());
                                updates.put("nextStep", "memoryReply");
                                updates.put("responseType", "MEMORY");
                                return updates;
                            }
                            if (change.getRequirements() == null)
                                change.setRequirements(new Requirements());
                            updates.putAll(RequirementChanges.apply(current, change));
                            if ("NEW".equals(change.getIntent())) {
                                updates.put(
                                        "tripGeneration",
                                        state.<Long>value("tripGeneration").orElse(0L) + 1);
                                updates.put("summarySections", Map.of());
                                updates.put("recentConversation", java.util.List.of());
                                if (memoryService != null
                                        && !current.<String>value("memoryUserId")
                                                .orElse("")
                                                .isBlank())
                                    memoryService.resetSession(
                                            current.<String>value("memoryUserId").orElseThrow(),
                                            state.sessionId(),
                                            current.<Long>value("preferenceVersion").orElse(0L));
                            }
                            return updates;
                        }));

        workflow.addNode("memoryReply", AsyncNodeAction.node_async(state -> Map.of()));
        workflow.addEdge("memoryReply", GraphDefinition.END);

        // 询问缺失的字段信息
        workflow.addNode(
                "askMissing",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=askMissing {}", state);
                            log.info("正在执行askMissing");
                            String output =
                                    MissingFieldPrompt.ask(
                                            state.firstMissing(), state.requirements());
                            Map<String, Object> updates = Map.of("output", output);
                            log.info("UPDATES node=askMissing {}", updates);
                            return updates;
                        }));

        // 中转
        workflow.addNode(
                "planParallel",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=planParallel {}", state);
                            log.info("正在执行planParallel");
                            Map<String, Object> updates = Map.of();
                            log.info("UPDATES node=planParallel {}", updates);
                            return updates;
                        }));

        accommodationSubgraph.addNode(
                "accommodationPlan",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=accommodationPlan {}", state);
                            log.info("正在执行accommodationPlan");
                            String res =
                                    planOrFallback(
                                            "住宿",
                                            "未查询到住宿实时信息。",
                                            () ->
                                                    accommodationPlannerAgent.execute(
                                                            state.executionMemoryId(),
                                                            cn.zhixing.travel.memory
                                                                    .EffectivePreferences
                                                                    .plannerInput(
                                                                            state,
                                                                            "accommodation")));
                            log.info("accommodationPlan AI的回复:{}", res);
                            Map<String, Object> updates =
                                    Map.of("nextStep", "assemble", "accommodationOutput", res);
                            log.info("UPDATES node=accommodationPlan {}", updates);
                            return updates;
                        }));

        foodSubgraph.addNode(
                "foodPlan",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=foodPlan {}", state);
                            log.info("正在执行foodPlan");
                            String res =
                                    planOrFallback(
                                            "美食",
                                            "暂时无法查询实时餐厅信息，可以稍后重新查询。",
                                            () ->
                                                    foodPlannerAgent.execute(
                                                            state.executionMemoryId(),
                                                            cn.zhixing.travel.memory
                                                                    .EffectivePreferences
                                                                    .plannerInput(state, "food")));
                            var food =
                                    FoodFollowUp.apply(
                                            state.<String>value("foodAction").orElse(""),
                                            state.<String>value("foodPreviousOutput").orElse(""),
                                            res);
                            Map<String, Object> updates =
                                    Map.of(
                                            "foodOutput",
                                            food.result(),
                                            "foodReply",
                                            food.reply(),
                                            "nextStep",
                                            "assemble");
                            log.info("UPDATES node=foodPlan {}", updates);
                            return updates;
                        }));

        // 中转
        transportSubgraph.addNode(
                "transportPlan",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=transportPlan {}", state);
                            log.info("正在执行transportPlan");
                            Map<String, Object> updates = Map.of();
                            log.info("UPDATES node=transportPlan {}", updates);
                            return updates;
                        }));

        // 自驾
        transportSubgraph.addNode(
                "drivePlan",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=drivePlan {}", state);
                            log.info("正在执行drivePlan");
                            String res =
                                    planOrFallback(
                                            "自驾",
                                            "未查询到自驾路线。",
                                            () ->
                                                    drivingTransportSubAgent.execute(
                                                            state.executionMemoryId(),
                                                            cn.zhixing.travel.memory
                                                                    .EffectivePreferences
                                                                    .plannerInput(
                                                                            state, "transport")));
                            //            log.info("自驾 AI的回复:{}", res);
                            Map<String, Object> updates =
                                    Map.of(
                                            "nextStep",
                                            "transportAssemble",
                                            "drivingTransportOutput",
                                            res);
                            log.info("UPDATES node=drivePlan {}", updates);
                            return updates;
                        }));

        // 高铁
        transportSubgraph.addNode(
                "trainPlan",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=trainPlan {}", state);
                            log.info("正在执行trainPlan");
                            String res =
                                    planOrFallback(
                                            "火车",
                                            "未查询到火车票实时信息。",
                                            () ->
                                                    trainTransportSubAgent.execute(
                                                            state.executionMemoryId(),
                                                            cn.zhixing.travel.memory
                                                                    .EffectivePreferences
                                                                    .plannerInput(
                                                                            state, "transport")));
                            //            log.info("高铁 AI的回复:{}", res);
                            Map<String, Object> updates =
                                    Map.of(
                                            "nextStep",
                                            "transportAssemble",
                                            "trainTransportOutput",
                                            res);
                            log.info("UPDATES node=trainPlan {}", updates);
                            return updates;
                        }));

        // 飞机
        transportSubgraph.addNode(
                "flightPlan",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=flightPlan {}", state);
                            log.info("正在执行flightPlan");
                            String res =
                                    planOrFallback(
                                            "航班",
                                            "未查询到航班实时信息。",
                                            () ->
                                                    flightTransportSubAgent.execute(
                                                            state.executionMemoryId(),
                                                            cn.zhixing.travel.memory
                                                                    .EffectivePreferences
                                                                    .plannerInput(
                                                                            state, "transport")));
                            //            log.info("飞机 AI的回复:{}", res);
                            Map<String, Object> updates =
                                    Map.of(
                                            "nextStep",
                                            "transportAssemble",
                                            "flightTransportOutput",
                                            res);
                            log.info("UPDATES node=flightPlan {}", updates);
                            return updates;
                        }));

        // 交通汇总
        transportSubgraph.addNode(
                "transportAssemble",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=transportAssemble {}", state);
                            log.info("正在执行transportAssemble");
                            String res =
                                    planOrFallback(
                                            "交通汇总",
                                            "【自驾】\n"
                                                    + state.drivingTransportOutput()
                                                    + "\n\n【火车】\n"
                                                    + state.trainTransportOutput()
                                                    + "\n\n【航班】\n"
                                                    + state.flightTransportOutput(),
                                            () ->
                                                    transportPlannerAgent.execute(
                                                            state.executionMemoryId(), state));
                            //            log.info("交通汇总 AI的回复:{}", res);
                            Map<String, Object> updates =
                                    Map.of("nextStep", "assemble", "transportOutput", res);
                            log.info("UPDATES node=transportAssemble {}", updates);
                            return updates;
                        }));

        workflow.addNode(
                "assemble",
                AsyncNodeAction.node_async(
                        state -> {
                            log.info("STATE before node=assemble {}", state);
                            if (state.missing()
                                    || state.transportOutput().isBlank()
                                    || state.accommodationOutput().isBlank()
                                    || state.foodOutput().isBlank()) {
                                throw new IllegalStateException("方案尚未完整，不能汇总旧结果");
                            }
                            // 汇总
                            log.info("正在执行assemble");
                            String transport =
                                    state.transportOutput() == null
                                            ? ""
                                            : state.transportOutput().trim();
                            String accommodation =
                                    state.accommodationOutput() == null
                                            ? ""
                                            : state.accommodationOutput().trim();
                            String food =
                                    state.foodOutput() == null ? "" : state.foodOutput().trim();

                            Requirements requirements = state.effectiveRequirements();
                            String origin =
                                    Optional.ofNullable(requirements.getOrigin()).orElse("未提供");
                            String destination =
                                    Optional.ofNullable(requirements.getDestination())
                                            .orElse("未提供");
                            String startDate =
                                    Optional.ofNullable(requirements.getStartDate()).orElse("未提供");
                            String people =
                                    Optional.ofNullable(requirements.getPeople()).orElse("未提供");
                            String special =
                                    Optional.ofNullable(requirements.getSpecial()).orElse("未提供");

                            String output =
                                    """
                                    【行程规划汇总】
                                    出发地：%s
                                    目的地：%s
                                    出发时间：%s
                                    人数：%s
                                    偏好：%s
                                    交通偏好：%s
                                    住宿偏好：%s
                                    美食偏好：%s


                                    【交通方案】
                                    %s


                                    【住宿方案】
                                    %s


                                    【美食方案】
                                    %s

                                    """
                                            .formatted(
                                                    origin,
                                                    destination,
                                                    startDate,
                                                    people,
                                                    special,
                                                    Optional.ofNullable(
                                                                    requirements
                                                                            .getTransportPreferences())
                                                            .orElse("无"),
                                                    Optional.ofNullable(
                                                                    requirements
                                                                            .getAccommodationPreferences())
                                                            .orElse("无"),
                                                    Optional.ofNullable(
                                                                    requirements
                                                                            .getFoodPreferences())
                                                            .orElse("无"),
                                                    transport,
                                                    accommodation,
                                                    food);

                            String notice = state.<String>value("memoryNotice").orElse("");
                            StringBuilder inherited = new StringBuilder();
                            Map<String, String> modes =
                                    state.value("preferenceModes", Map.<String, String>of());
                            for (String category :
                                    cn.zhixing.travel.memory.MemoryModels.CATEGORIES) {
                                String pref =
                                        cn.zhixing.travel.memory.EffectivePreferences.value(
                                                requirements, category);
                                if (pref != null
                                        && "INHERIT"
                                                .equals(
                                                        cn.zhixing.travel.memory
                                                                .EffectivePreferences.mode(
                                                                state.requirements(),
                                                                modes,
                                                                category)))
                                    inherited
                                            .append("本次参考长期")
                                            .append(
                                                    cn.zhixing.travel.memory.TravelMemoryService
                                                            .label(category))
                                            .append("偏好：")
                                            .append(pref)
                                            .append('\n');
                            }
                            output = notice + inherited + output;
                            Map<String, Object> updates =
                                    Map.of(
                                            "output",
                                            output,
                                            "plannedInputFingerprints",
                                            state.value("desiredInputFingerprints", Map.of()),
                                            "responseType",
                                            "PLAN",
                                            "plannedKnowledgeRevision",
                                            state.<String>value("knowledgeRevision").orElse(""));
                            log.info("UPDATES node=assemble {}", updates);
                            return updates;
                        }));

        // 子图边
        addGatedEntry(transportSubgraph, RequirementChanges.TRANSPORT, "transportPlan");
        addGatedEntry(accommodationSubgraph, RequirementChanges.ACCOMMODATION, "accommodationPlan");
        addGatedEntry(foodSubgraph, RequirementChanges.FOOD, "foodPlan");
        accommodationSubgraph.addEdge("accommodationPlan", GraphDefinition.END);
        foodSubgraph.addEdge("foodPlan", GraphDefinition.END);
        transportSubgraph.addEdge("transportPlan", "drivePlan");
        transportSubgraph.addEdge("transportPlan", "trainPlan");
        transportSubgraph.addEdge("transportPlan", "flightPlan");

        transportSubgraph.addEdge("drivePlan", "transportAssemble");
        transportSubgraph.addEdge("trainPlan", "transportAssemble");
        transportSubgraph.addEdge("flightPlan", "transportAssemble");

        transportSubgraph.addEdge("transportAssemble", GraphDefinition.END);

        // 父图
        workflow.addEdge(GraphDefinition.START, "collect");
        // 当执行到collect节点时，根据nextStep的值，选择执行askMissing或planParallel节点
        workflow.addConditionalEdges(
                "collect",
                state -> CompletableFuture.completedFuture(state.nextStep()),
                Map.of(
                        "askMissing",
                        "askMissing",
                        "planParallel",
                        "planParallel",
                        "reuse",
                        "assemble",
                        "memoryReply",
                        "memoryReply"));

        // 通过适配节点执行子图，仅返回各自结果，避免整份 State 在并行合并时互相覆盖。
        addBranchNode(
                workflow,
                "transportSubgraph",
                transportSubgraph.compile(),
                java.util.List.of(
                        "transportOutput",
                        "drivingTransportOutput",
                        "trainTransportOutput",
                        "flightTransportOutput"));
        addBranchNode(
                workflow,
                "accommodationBranch",
                accommodationSubgraph.compile(),
                java.util.List.of("accommodationOutput"));
        addBranchNode(
                workflow,
                "foodBranch",
                foodSubgraph.compile(),
                java.util.List.of("foodOutput", "foodReply"));

        workflow.addEdge("askMissing", "collect");

        workflow.addEdge("planParallel", "transportSubgraph");
        workflow.addEdge("planParallel", "accommodationBranch");
        workflow.addEdge("planParallel", "foodBranch");
        workflow.addEdge("transportSubgraph", "assemble");
        workflow.addEdge("accommodationBranch", "assemble");
        workflow.addEdge("foodBranch", "assemble");

        workflow.addEdge("assemble", GraphDefinition.END);

        CompileConfig compileConfig =
                CompileConfig.builder()
                        .checkpointSaver(checkpointSaver)
                        .interruptsAfter(java.util.List.of("askMissing"))
                        .build();

        this.graph = workflow.compile(compileConfig);

        System.out.println(graph.getGraph(GraphRepresentation.Type.MERMAID));
    }

    private String planOrFallback(String section, String fallback, Supplier<String> planner) {
        try {
            return planner.get();
        } catch (RuntimeException error) {
            rethrowBusinessFailure(error);
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ApiException
                        || cause instanceof InputRequiredException
                        || cause instanceof IOException
                        || cause instanceof TimeoutException
                        || isToolInvocationLimit(cause)) {
                    log.warn("{}规划暂不可用 ({})", section, cause.getClass().getSimpleName());
                    return fallback;
                }
            }
            throw error;
        }
    }

    private static boolean isToolInvocationLimit(Throwable error) {
        // LangChain4j 1.10 把工具轮次用尽报告为无专用类型的 RuntimeException。
        String message = error.getMessage();
        return error.getClass() == RuntimeException.class
                && message != null
                && message.startsWith("Something is wrong, exceeded ")
                && message.endsWith(" sequential tool invocations");
    }

    private static void rethrowBusinessFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof BusinessException business) throw business;
        }
    }

    /** 子图只交回自己拥有的结果；父图检查点保存合并后的完整状态。 */
    private void addBranchNode(
            StateGraph<TravelWorkflowState> workflow,
            String node,
            CompiledGraph<TravelWorkflowState> branchGraph,
            java.util.List<String> keys)
            throws GraphStateException {
        workflow.addNode(
                node,
                AsyncNodeAction.node_async(
                        state -> {
                            RunnableConfig childConfig =
                                    RunnableConfig.builder()
                                            .addParallelNodeExecutor(
                                                    "transportPlan", transportExecutor)
                                            .build();
                            TravelWorkflowState result =
                                    branchGraph
                                            .invoke(
                                                    GraphInput.args(new HashMap<>(state.data())),
                                                    childConfig)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "分支未返回状态：" + node));
                            Map<String, Object> updates = new HashMap<>();
                            for (String key : keys)
                                updates.put(key, result.<String>value(key).orElse(""));
                            return updates;
                        }));
    }

    /** 固定并行汇合结构，每个分支在子图入口条件路由到执行或复用。 */
    private void addGatedEntry(
            StateGraph<TravelWorkflowState> branchGraph, String branch, String execute)
            throws GraphStateException {
        branchGraph.addNode("route", AsyncNodeAction.node_async(state -> Map.of()));
        branchGraph.addNode("reuse", AsyncNodeAction.node_async(state -> Map.of()));
        branchGraph.addEdge(GraphDefinition.START, "route");
        branchGraph.addConditionalEdges(
                "route",
                state ->
                        CompletableFuture.completedFuture(
                                state.affects(branch) ? "execute" : "reuse"),
                Map.of("execute", execute, "reuse", "reuse"));
        branchGraph.addEdge("reuse", GraphDefinition.END);
    }

    public Flux<String> execute(String sessionId, String userText) {
        return executeState(sessionId, userText)
                .map(TravelWorkflowState::output)
                .flatMapMany(
                        output -> {
                            if (output == null || output.isBlank()) return Flux.just("没有结果哦~~");
                            return Flux.just(output);
                        });
    }

    public Mono<TravelWorkflowState> executeState(String sessionId, String userText) {
        return Mono.fromCallable(() -> runState(sessionId, userText))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** 在线程池中同步推进图；异常交给调用层，不能吞成空结果。 */
    public TravelWorkflowState runState(String sessionId, String userText) {
        return runState(sessionId, userText, null);
    }

    public TravelWorkflowState runState(
            String sessionId, String userText, String knowledgeRevision) {
        return runState(sessionId, userText, knowledgeRevision, Map.of());
    }

    public TravelWorkflowState runState(
            String sessionId,
            String userText,
            String knowledgeRevision,
            Map<String, Object> memoryInput) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("sessionId", sessionId);
        inputs.put("userText", userText);
        RunnableConfig config =
                RunnableConfig.builder()
                        .threadId(sessionId)
                        .addParallelNodeExecutor("planParallel", taskExecutor)
                        .addParallelNodeExecutor("transportPlan", taskExecutor)
                        .build();
        var previous = graph.stateOf(config);
        // 完成后也携带旧 State 从 collect 重新进入；失败后新消息同样需要重新分析。
        previous.ifPresent(snapshot -> inputs.putAll(snapshot.state().data()));
        if (knowledgeRevision != null) inputs.put("knowledgeRevision", knowledgeRevision);
        inputs.putAll(memoryInput);
        inputs.put("responseType", "PENDING");
        inputs.put("sessionId", sessionId);
        inputs.put("userText", userText);
        String next = previous.map(snapshot -> snapshot.next()).orElse(null);
        GraphInput input =
                "collect".equals(next) ? GraphInput.resume(inputs) : GraphInput.args(inputs);
        try {
            var output =
                    graph.invokeFinal(input, config)
                            .orElseThrow(() -> new IllegalStateException("旅行规划未返回状态"));
            return output.state();
        } catch (Exception error) {
            rethrowBusinessFailure(error);
            throw new IllegalStateException("旅行规划执行失败", error);
        }
    }
}
