package cn.zhixing.travel.agent.requirement;

import cn.zhixing.travel.agent.food.FoodFollowUp;
import cn.zhixing.travel.agent.state.TravelWorkflowState;
import cn.zhixing.travel.memory.EffectivePreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** 合并需求增量，按依赖使旧分支失效，并由代码复核必填项。 */
public final class RequirementChanges {
    public static final String TRANSPORT = "transport";
    public static final String ACCOMMODATION = "accommodation";
    public static final String FOOD = "food";
    private static final List<String> ALL = List.of(TRANSPORT, ACCOMMODATION, FOOD);
    private static final List<Field> FIELDS =
            List.of(
                    new Field(
                            "origin",
                            Requirements::getOrigin,
                            Requirements::setOrigin,
                            List.of(TRANSPORT),
                            true),
                    new Field(
                            "destination",
                            Requirements::getDestination,
                            Requirements::setDestination,
                            ALL,
                            true),
                    new Field(
                            "startDate",
                            Requirements::getStartDate,
                            Requirements::setStartDate,
                            ALL,
                            true),
                    new Field(
                            "people", Requirements::getPeople, Requirements::setPeople, ALL, true),
                    new Field(
                            "special",
                            Requirements::getSpecial,
                            Requirements::setSpecial,
                            ALL,
                            true),
                    new Field(
                            "transportPreferences",
                            Requirements::getTransportPreferences,
                            Requirements::setTransportPreferences,
                            List.of(TRANSPORT),
                            false),
                    new Field(
                            "accommodationPreferences",
                            Requirements::getAccommodationPreferences,
                            Requirements::setAccommodationPreferences,
                            List.of(ACCOMMODATION),
                            false),
                    new Field(
                            "foodPreferences",
                            Requirements::getFoodPreferences,
                            Requirements::setFoodPreferences,
                            List.of(FOOD),
                            false));

    private RequirementChanges() {}

    public static Map<String, Object> apply(TravelWorkflowState state, RequirementInfo change) {
        if (change == null || change.getRequirements() == null) {
            throw new IllegalStateException("需求分析未返回有效结构");
        }
        String intent = change.getIntent();
        // 兼容旧测试与旧结构；生产提示词要求显式返回意图。
        if (intent == null || intent.isBlank()) intent = "MODIFY";
        if (!List.of("NEW", "MODIFY", "RETRY").contains(intent)) {
            throw new IllegalStateException("未知规划意图：" + intent);
        }
        boolean fresh = "NEW".equals(intent);
        Set<String> affected = new LinkedHashSet<>();
        if (fresh) affected.addAll(ALL);
        if (!state.<String>value("knowledgeRevision")
                .orElse("")
                .equals(state.<String>value("plannedKnowledgeRevision").orElse(""))) {
            // 上传或删除资料后不能复用基于旧知识生成的方案。
            affected.addAll(ALL);
        }
        List<String> clears = change.getClearFields() == null ? List.of() : change.getClearFields();
        for (String name : clears) {
            if (FIELDS.stream().noneMatch(field -> field.name().equals(name))) {
                throw new IllegalStateException("未知需求字段：" + name);
            }
        }

        Requirements merged = new Requirements();
        for (Field field : FIELDS) {
            String previous = normalize(field.get().apply(state.requirements()));
            String patch = normalize(field.get().apply(change.getRequirements()));
            String value =
                    clears.contains(field.name())
                            ? null
                            : patch != null ? patch : fresh ? null : previous;
            field.set().accept(merged, value);
            if (!field.name().endsWith("Preferences") && !Objects.equals(previous, value))
                affected.addAll(field.branches());
        }
        Map<String, String> modes =
                fresh
                        ? new HashMap<>()
                        : new HashMap<>(state.value("preferenceModes", Map.<String, String>of()));
        for (String category : ALL) {
            String field = category + "Preferences";
            if (clears.contains(field)) modes.put(category, "INHERIT");
            if (normalize(EffectivePreferences.value(change.getRequirements(), category)) != null)
                modes.put(category, "OVERRIDE");
        }
        if (change.getPreferenceModes() != null)
            for (var entry : change.getPreferenceModes().entrySet()) {
                if (!ALL.contains(entry.getKey())
                        || !List.of("INHERIT", "OVERRIDE", "IGNORE").contains(entry.getValue()))
                    throw new IllegalStateException("未知本次偏好模式");
                modes.put(entry.getKey(), entry.getValue());
                if (!"OVERRIDE".equals(entry.getValue()))
                    EffectivePreferences.set(merged, entry.getKey(), null);
            }
        Requirements effective =
                EffectivePreferences.resolve(
                        merged,
                        state.value("longTermPreferences", Map.<String, String>of()),
                        modes);
        Map<String, String> desired = EffectivePreferences.fingerprints(effective);
        Map<String, String> planned =
                state.value("plannedInputFingerprints", Map.<String, String>of());
        // 旧检查点没有指纹，按原始本次条件计算，避免把新默认值误当成旧方案已采用的条件。
        if (planned.isEmpty()) planned = EffectivePreferences.fingerprints(state.requirements());
        for (String category : ALL)
            if (!desired.get(category).equals(planned.get(category))) affected.add(category);
        // “换几家餐厅”可以不改变字段，但仍要求重跑该分支；RETRY 不强制刷新。
        if (!"RETRY".equals(intent) && change.getAffectedBranches() != null) {
            for (String branch : change.getAffectedBranches()) {
                if (!ALL.contains(branch)) throw new IllegalStateException("未知规划分支：" + branch);
                affected.add(branch);
            }
        }

        String foodAction = fresh ? "" : FoodFollowUp.validatedAction(change.getFoodAction());
        Requirements before = state.effectiveRequirements();
        boolean sameFoodSelection =
                TripCities.isSameCity(before.getDestination(), effective.getDestination())
                        && Objects.equals(before.getSpecial(), effective.getSpecial())
                        && Objects.equals(
                                before.getFoodPreferences(), effective.getFoodPreferences());
        String previousFood = !foodAction.isBlank() && sameFoodSelection ? state.foodOutput() : "";
        if (!foodAction.isBlank()) affected.add(FOOD);

        Map<String, Object> updates = new HashMap<>();
        for (String branch : ALL) {
            String key = branch + "Output";
            String old = state.<String>value(key).orElse("");
            if (old.isBlank()) affected.add(branch);
            if (affected.contains(branch)) updates.put(key, "");
        }
        if (affected.contains(TRANSPORT)) {
            updates.put("drivingTransportOutput", "");
            updates.put("trainTransportOutput", "");
            updates.put("flightTransportOutput", "");
        }
        String firstMissing =
                FIELDS.stream()
                        .filter(Field::required)
                        .filter(
                                field -> {
                                    String value = normalize(field.get().apply(merged));
                                    return value == null
                                            || (("origin".equals(field.name())
                                                            || "destination".equals(field.name()))
                                                    && TripCities.provinceOnly(value));
                                })
                        .map(Field::name)
                        .findFirst()
                        .orElse("");
        updates.put("requirements", merged);
        updates.put("effectiveRequirements", effective);
        updates.put("preferenceModes", modes);
        updates.put("desiredInputFingerprints", desired);
        updates.put("responseType", firstMissing.isEmpty() ? "PLAN" : "ASK");
        updates.put("intent", intent);
        updates.put("foodAction", foodAction);
        updates.put(
                "foodLimit",
                !foodAction.isBlank() || change.getFoodLimit() != null
                        ? FoodFollowUp.limit(change.getFoodLimit())
                        : 0);
        updates.put("foodPreviousOutput", previousFood);
        updates.put("foodReply", "");
        updates.put(
                "hadPreviousPlan",
                state.hasCompletePlan()
                        || !state.value("plannedInputFingerprints", Map.of()).isEmpty());
        updates.put("affectedBranches", new ArrayList<>(affected));
        updates.put("missing", !firstMissing.isEmpty());
        updates.put("firstMissing", firstMissing);
        updates.put(
                "nextStep",
                !firstMissing.isEmpty()
                        ? "askMissing"
                        : affected.isEmpty() ? "reuse" : "planParallel");
        // 清除旧追问/汇总；完整方案只能由校验后的 assemble 产生。
        updates.put("output", "");
        return updates;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Field(
            String name,
            Function<Requirements, String> get,
            BiConsumer<Requirements, String> set,
            List<String> branches,
            boolean required) {}
}
