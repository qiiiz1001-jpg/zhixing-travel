package cn.zhixing.travel.agent.food;

import cn.zhixing.travel.config.LiveTravelQueries;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class FoodPlannerAgent {

    @Autowired private LiveTravelQueries liveQueries;
    @Autowired private FoodPlannerService foodPlannerService;

    /** 完整需求中包含本轮合并后的口味、预算等偏好。 */
    public String execute(
            String sessionId, cn.zhixing.travel.agent.requirement.Requirements requirements) {
        if (liveQueries != null) {
            String actual = liveQueries.food(requirements);
            if (actual != null) return actual;
        }
        return foodPlannerService.chat(
                sessionId, com.alibaba.dashscope.utils.JsonUtils.toJson(requirements));
    }
}
