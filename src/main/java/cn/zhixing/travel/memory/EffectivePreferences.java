package cn.zhixing.travel.memory;

import cn.zhixing.travel.agent.requirement.Requirements;
import cn.zhixing.travel.agent.state.TravelWorkflowState;
import cn.zhixing.travel.memory.MemoryModels.PlannerInput;

import org.springframework.beans.BeanUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 默认偏好不写入本次原始需求；按类别整体覆盖，禁止自动拼接冲突条件。 */
public final class EffectivePreferences {
    private EffectivePreferences() {}

    public static String value(Requirements requirements, String category) {
        return switch (category) {
            case "transport" -> requirements.getTransportPreferences();
            case "accommodation" -> requirements.getAccommodationPreferences();
            case "food" -> requirements.getFoodPreferences();
            default -> throw new IllegalArgumentException("未知偏好类别");
        };
    }

    public static void set(Requirements requirements, String category, String text) {
        switch (category) {
            case "transport" -> requirements.setTransportPreferences(text);
            case "accommodation" -> requirements.setAccommodationPreferences(text);
            case "food" -> requirements.setFoodPreferences(text);
            default -> throw new IllegalArgumentException("未知偏好类别");
        }
    }

    public static Requirements resolve(
            Requirements raw, Map<String, String> defaults, Map<String, String> modes) {
        Requirements result = new Requirements();
        BeanUtils.copyProperties(raw, result);
        for (String category : MemoryModels.CATEGORIES) {
            String mode = mode(raw, modes, category);
            String text =
                    "IGNORE".equals(mode)
                            ? null
                            : "OVERRIDE".equals(mode)
                                    ? value(raw, category)
                                    : defaults.get(category);
            set(result, category, text == null || text.isBlank() ? null : text);
        }
        return result;
    }

    public static String mode(Requirements raw, Map<String, String> modes, String category) {
        String own = value(raw, category);
        return modes.getOrDefault(category, own == null || own.isBlank() ? "INHERIT" : "OVERRIDE");
    }

    public static Map<String, String> fingerprints(Requirements effective) {
        Map<String, String> fingerprints = new HashMap<>();
        for (String category : MemoryModels.CATEGORIES) {
            List<String> fields =
                    new ArrayList<>(
                            Arrays.asList(
                                    effective.getDestination(),
                                    effective.getStartDate(),
                                    effective.getPeople(),
                                    effective.getSpecial(),
                                    value(effective, category)));
            if ("transport".equals(category)) fields.add(effective.getOrigin());
            // 带长度编码，避免分隔符出现在用户条件中时出现碰撞。
            String text =
                    fields.stream()
                            .map(field -> field == null ? "-1:" : field.length() + ":" + field)
                            .collect(java.util.stream.Collectors.joining());
            try {
                fingerprints.put(
                        category,
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(text.getBytes(StandardCharsets.UTF_8))));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
        return fingerprints;
    }

    public static PlannerInput plannerInput(TravelWorkflowState state, String category) {
        PlannerInput input = new PlannerInput();
        BeanUtils.copyProperties(state.effectiveRequirements(), input);
        input.setUserRequest(TravelMemoryService.clip(state.userText(), 1200));
        if ("food".equals(category)) {
            input.setFoodAction(state.<String>value("foodAction").orElse(""));
            input.setFoodLimit(state.<Integer>value("foodLimit").orElse(0));
            input.setFoodPreviousOutput(state.<String>value("foodPreviousOutput").orElse(""));
        }
        Map<String, String> summary = state.value("summarySections", Map.<String, String>of());
        input.setMemorySummary(
                summary.getOrDefault("common", "") + "\n" + summary.getOrDefault(category, ""));
        input.setRecentConversation(
                state.value("recentConversation", List.<Map<String, String>>of()));
        Map<String, String> sources = new HashMap<>();
        Map<String, String> modes = state.value("preferenceModes", Map.<String, String>of());
        for (String branch : MemoryModels.CATEGORIES)
            sources.put(branch, mode(state.requirements(), modes, branch));
        input.setPreferenceSources(sources);
        return input;
    }
}
