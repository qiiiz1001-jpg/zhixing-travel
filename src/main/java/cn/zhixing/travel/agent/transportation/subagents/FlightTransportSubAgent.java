package cn.zhixing.travel.agent.transportation.subagents;

import cn.zhixing.travel.agent.requirement.Requirements;

import com.alibaba.dashscope.utils.JsonUtils;

import cn.zhixing.travel.config.LiveTravelQueries;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class FlightTransportSubAgent {

    @Autowired private LiveTravelQueries liveQueries;
    @Autowired private FlightTransportSubService flightTransportSubService;

    public String execute(String sessionId, Requirements requirements) {
        if (liveQueries != null) {
            String actual = liveQueries.transport("flight", requirements);
            if (actual != null) return actual;
        }
        String prompt = JsonUtils.toJson(requirements);
        return flightTransportSubService.chat(sessionId, prompt);
    }
}
