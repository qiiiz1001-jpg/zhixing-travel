package cn.zhixing.travel.agent.requirement;

import java.text.Normalizer;
import java.util.Set;

public final class TripCities {
    private static final Set<String> PROVINCE_NAMES =
            Set.of(
                    "河北",
                    "山西",
                    "辽宁",
                    "黑龙江",
                    "江苏",
                    "浙江",
                    "安徽",
                    "福建",
                    "江西",
                    "山东",
                    "河南",
                    "湖北",
                    "湖南",
                    "广东",
                    "海南",
                    "四川",
                    "贵州",
                    "云南",
                    "陕西",
                    "甘肃",
                    "青海",
                    "台湾",
                    "内蒙古",
                    "广西",
                    "西藏",
                    "宁夏",
                    "新疆",
                    "内蒙古自治区",
                    "广西壮族自治区",
                    "西藏自治区",
                    "宁夏回族自治区",
                    "新疆维吾尔自治区");

    private TripCities() {}

    public static boolean provinceOnly(String value) {
        String name = normalize(value);
        // “吉林”也可以指吉林市，带“省”的写法才明确表示省级范围。
        return PROVINCE_NAMES.contains(name)
                || name.endsWith("省")
                        && (PROVINCE_NAMES.contains(name.substring(0, name.length() - 1))
                                || "吉林省".equals(name));
    }

    public static boolean isSameCity(String first, String second) {
        String city = cityName(first);
        return !city.isBlank() && city.equals(cityName(second));
    }

    public static String cityName(String value) {
        return normalize(value).replaceFirst("(特别行政区|市)$", "");
    }

    public static String askForCity(String role, String province) {
        return "请补充" + role + "「" + province.strip() + "」的具体城市，再查交通和住宿。";
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFKC);
    }
}
