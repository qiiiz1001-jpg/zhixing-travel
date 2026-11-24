package cn.zhixing.travel.agent.requirement;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Requirements implements Serializable {
    // 保持已有 PostgreSQL 检查点中的 Requirements 可反序列化。
    private static final long serialVersionUID = -8369688441657424487L;

    private String origin;
    private String destination;
    private String startDate;
    private String people;
    private String special;
    private String transportPreferences;
    private String accommodationPreferences;
    private String foodPreferences;

    public Requirements(
            String origin, String destination, String startDate, String people, String special) {
        this.origin = origin;
        this.destination = destination;
        this.startDate = startDate;
        this.people = people;
        this.special = special;
    }

    // 模型可能把预算单独返回，归入全局要求以沿用现有存储和分支规则。
    @JsonCreator
    private static Requirements fromJson(
            @JsonProperty("origin") String origin,
            @JsonProperty("destination") String destination,
            @JsonProperty("startDate") String startDate,
            @JsonProperty("people") String people,
            @JsonProperty("special") String special,
            @JsonProperty("transportPreferences") String transportPreferences,
            @JsonProperty("accommodationPreferences") String accommodationPreferences,
            @JsonProperty("foodPreferences") String foodPreferences,
            @JsonProperty("budget") String budget) {
        if (budget != null && !budget.isBlank()) {
            String amount = budget.strip();
            if (special == null || special.isBlank() || "无".equals(special.strip())) {
                special = "总预算：" + amount;
            } else if (!special.contains(amount)) {
                special = special + "；总预算：" + amount;
            }
        }
        return new Requirements(
                origin,
                destination,
                startDate,
                people,
                special,
                transportPreferences,
                accommodationPreferences,
                foodPreferences);
    }
}
