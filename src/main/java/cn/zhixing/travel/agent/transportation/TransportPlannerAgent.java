package cn.zhixing.travel.agent.transportation;

import cn.zhixing.travel.agent.state.TravelWorkflowState;

import com.alibaba.dashscope.utils.JsonUtils;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 交通主agent，负责将用户请求路由到对应的子agent */
@Component
@Slf4j
public class TransportPlannerAgent {

    @Value("${mcp.enabled:false}")
    private boolean mcpEnabled;

    @Value("${mcp.tuniu-key:}")
    private String tuniuKey;

    @Autowired private TransportPlannerService transportPlannerService;

    public String execute(String sessionId, TravelWorkflowState state) {

        if (mcpEnabled && tuniuKey != null && !tuniuKey.isBlank()) {
            return "【自驾】\n"
                    + readableDrive(state.drivingTransportOutput())
                    + "\n\n【高铁】\n"
                    + state.trainTransportOutput()
                    + "\n\n【航班】\n"
                    + state.flightTransportOutput();
        }
        TransportAssemble transportAssemble = new TransportAssemble();
        BeanUtils.copyProperties(state.effectiveRequirements(), transportAssemble);
        transportAssemble.setDrivePlan(state.drivingTransportOutput());
        transportAssemble.setFlightPlan(state.flightTransportOutput());
        transportAssemble.setTrainPlan(state.trainTransportOutput());

        String prompt = JsonUtils.toJson(transportAssemble);
        return transportPlannerService.chat(sessionId, prompt);
    }

    private String readableDrive(String output) {
        int metrics = output.indexOf("【指标】");
        return (metrics < 0 ? output : output.substring(0, metrics)).strip();
    }
}
