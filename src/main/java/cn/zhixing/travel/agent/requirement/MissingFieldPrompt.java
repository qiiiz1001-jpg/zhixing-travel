package cn.zhixing.travel.agent.requirement;

import cn.zhixing.travel.agent.state.TravelWorkflowState;

public final class MissingFieldPrompt {
    private MissingFieldPrompt() {}

    public static String ask(String fieldKey, Requirements requirements) {
        if (TravelWorkflowState.KEY_ORIGIN.equals(fieldKey)
                && TripCities.provinceOnly(requirements.getOrigin())) {
            return TripCities.askForCity("出发地", requirements.getOrigin());
        }
        if (TravelWorkflowState.KEY_DESTINATION.equals(fieldKey)
                && TripCities.provinceOnly(requirements.getDestination())) {
            return TripCities.askForCity("目的地", requirements.getDestination());
        }
        return ask(fieldKey);
    }

    public static String ask(String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) return "请补充你的行程信息。";
        return switch (fieldKey) {
            case TravelWorkflowState.KEY_ORIGIN -> "请告诉我你的具体出发地是哪？比如：广东省深圳市南山区石鼓地铁站";
            case TravelWorkflowState.KEY_DESTINATION -> "请告诉我你的目的地是哪？例如：深圳湾公园";
            case TravelWorkflowState.KEY_START_DATE -> "请告诉我出发日期时间？例如：2026-02-10 09:00|明天|下周一";
            case TravelWorkflowState.KEY_PEOPLE -> "请告诉我出行人数。可以直接回复数字，例如 2。";
            case TravelWorkflowState.KEY_SPECIAL -> "请告诉我特殊要求？例如：预算/避开早上/无";
            default -> "请补充行程信息：" + fieldKey;
        };
    }
}
