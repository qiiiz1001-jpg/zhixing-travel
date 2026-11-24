package cn.zhixing.travel.agent.requirement;

import cn.zhixing.travel.memory.MemoryModels.Operation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RequirementInfo implements Serializable {
    private String sessionId;
    private String userText;
    private Boolean missing;
    private String firstMissing;
    private Requirements requirements; // 已收集到的需求
    private String intent; // NEW / MODIFY / RETRY；requirements 在修改时只返回本轮增量。
    private List<String> affectedBranches;
    private String foodAction;
    private Integer foodLimit;
    private List<String> clearFields;
    private List<Operation> memoryOperations;
    private Boolean memoryOnly;
    private String memoryClarification;
    private Map<String, String> preferenceModes;

    public RequirementInfo(
            String sessionId,
            String userText,
            Boolean missing,
            String firstMissing,
            Requirements requirements) {
        this.sessionId = sessionId;
        this.userText = userText;
        this.missing = missing;
        this.firstMissing = firstMissing;
        this.requirements = requirements;
    }
}
