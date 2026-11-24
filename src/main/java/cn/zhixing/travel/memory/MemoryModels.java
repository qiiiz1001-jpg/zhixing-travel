package cn.zhixing.travel.memory;

import cn.zhixing.travel.agent.requirement.Requirements;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public final class MemoryModels {
    public static final List<String> CATEGORIES = List.of("transport", "accommodation", "food");

    private MemoryModels() {}

    public record Operation(String category, String action, String content)
            implements Serializable {}

    public record Profile(
            long version,
            Map<String, String> preferences,
            String updatedAt,
            String lastOperationId) {
        public static Profile empty() {
            return new Profile(0, Map.of(), "", "");
        }
    }

    public record Update(Profile profile, boolean replayed) {}

    public record History(long sequence, short role, String content) {}

    public record Summary(
            long contextVersion,
            long revision,
            long floorSequence,
            long coveredSequence,
            int coveredOffset,
            Map<String, String> sections) {
        public static Summary empty(long version, long revision, long floor) {
            return new Summary(version, revision, floor, floor, 0, Map.of());
        }
    }

    /** 仅是发给角色的临时输入；图中的 requirements 始终保留本次原始条件。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class PlannerInput extends Requirements {
        private String userRequest;
        private String foodAction;
        private int foodLimit;
        private String foodPreviousOutput;
        private String memorySummary;
        private List<Map<String, String>> recentConversation;
        private Map<String, String> preferenceSources;
    }
}
