package cn.zhixing.travel.agent.food;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 饮食偏好与当前这一轮的增补或换店指令分开处理。 */
public final class FoodFollowUp {
    public static final String MORE = "MORE";
    public static final String REPLACE = "REPLACE";
    private static final Pattern MORE_FOOD =
            Pattern.compile("(?:美食|餐厅|小吃|吃的)(?:再)?(?:多来点|多一点|多一些|多推荐几家|再来点|再来几个|再来几家)");
    private static final Pattern REPLACE_FOOD =
            Pattern.compile("(?:美食|餐厅|小吃|吃的)(?:换一批|换几家|换几个|换一些|换点别的)");

    private FoodFollowUp() {}

    public static String explicitAction(String message) {
        String text = message.strip().replaceAll("[\\s，。！？!?,.]+", "");
        if (MORE_FOOD.matcher(text).matches()) return MORE;
        if (REPLACE_FOOD.matcher(text).matches()) return REPLACE;
        return "";
    }

    public static String validatedAction(String action) {
        return MORE.equals(action) || REPLACE.equals(action) ? action : "";
    }

    public static int limit(Integer count) {
        return count == null || count < 1 ? 3 : Math.min(6, count);
    }

    public static String placeKey(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFKC)
                .replaceAll("[\\s·•・]+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    public static Set<String> previousPlaces(String text) {
        Set<String> names = new HashSet<>();
        boolean local = text.startsWith("按当地菜品查询");
        for (String line : text.lines().toList()) {
            if (line.startsWith("- ")) names.add(placeKey(restaurant(line, local)));
            else if (line.startsWith("【名称】")) {
                names.add(placeKey(line.substring("【名称】".length()).replaceFirst("^[：:]\\s*", "")));
            }
        }
        return names;
    }

    public static Set<String> previousDishes(String text) {
        Set<String> dishes = new java.util.LinkedHashSet<>();
        for (String line : text.lines().filter(row -> row.startsWith("- ")).toList()) {
            String[] fields = line.substring(2).split(" · ");
            if (fields.length >= (text.startsWith("按当地菜品查询") ? 2 : 3)) dishes.add(fields[0]);
        }
        return dishes;
    }

    private static String restaurant(String line, boolean local) {
        String[] fields = line.substring(2).split(" · ");
        return fields.length >= 3 || (local && fields.length >= 2) ? fields[1] : fields[0];
    }

    public static Revision apply(String action, String previous, String found) {
        if (action.isBlank() || previous.isBlank()) return new Revision(found, "");
        Set<String> shown = previousPlaces(previous);
        if (shown.isEmpty()) return new Revision(found, "");
        boolean local = found.startsWith("按当地菜品查询");
        List<String> newRows = new ArrayList<>();
        for (String line : found.lines().filter(row -> row.startsWith("- ")).toList()) {
            if (shown.add(placeKey(restaurant(line, local)))) newRows.add(line);
        }
        if (newRows.isEmpty()) {
            String reply =
                    found.startsWith("按当地菜品查询") || found.startsWith("高德地点查询")
                            ? "暂未找到符合当前要求的新餐厅。"
                            : found.strip();
            return new Revision(previous, reply);
        }
        String items = String.join("\n", newRows);
        if (MORE.equals(action))
            return new Revision(
                    previous.stripTrailing() + "\n" + items,
                    "新增 " + newRows.size() + " 项推荐\n\n" + items);
        String heading = found.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        if (heading.startsWith("- ")) heading = "";
        String result = heading.isBlank() ? items : heading + "\n\n" + items;
        return new Revision(result, result);
    }

    public record Revision(String result, String reply) {}
}
