package cn.zhixing.travel.agent.transportation;

import cn.zhixing.travel.agent.requirement.Requirements;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransportAssemble extends Requirements implements Serializable {
    private String drivePlan;
    private String trainPlan;
    private String flightPlan;
}
