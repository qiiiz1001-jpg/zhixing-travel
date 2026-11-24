package cn.zhixing.travel.config;

import cn.zhixing.travel.agent.food.FoodFollowUp;
import cn.zhixing.travel.agent.food.LocalFoodTerms;
import cn.zhixing.travel.agent.requirement.Requirements;
import cn.zhixing.travel.agent.requirement.TripCities;
import cn.zhixing.travel.memory.MemoryModels.PlannerInput;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class LiveTravelQueries {
    private static final Logger LOG = LoggerFactory.getLogger(LiveTravelQueries.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Pattern DATE = Pattern.compile("\\d{4}[-年]\\d{1,2}[-月]\\d{1,2}");
    private static final Pattern PEOPLE = Pattern.compile("\\d+");
    private static final Pattern HOTEL_PRICE_RANGE =
            Pattern.compile(
                    "(?<![0-9])([0-9]{1,6})\\s*元?\\s*[-~～至到–—]\\s*[¥￥]?\\s*([0-9]{1,6})(?![0-9])");
    private static final Pattern HOTEL_PRICE_CEILING =
            Pattern.compile(
                    "(?:预算|每晚|每间|不超过|不高于|最多|上限)\\s*(?:为|在|控制在|是|[:：])?\\s*[¥￥]?\\s*([0-9]{1,6})(?![0-9])"
                        + "|(?<![0-9])([0-9]{1,6})\\s*(?:元(?:以内|以下|左右|一晚|/晚)?|以内|以下|左右)");
    private static final Pattern ECONOMY_HOTEL = Pattern.compile("便宜|低价|经济型|实惠|省钱|性价比");
    private static final int ECONOMY_HOTEL_CEILING = 400;

    private final ObjectProvider<McpToolConfig.ToolClients> clients;
    private final ObjectMapper json;
    private final Environment env;
    private final LocalFoodTerms localFoodTerms;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    LiveTravelQueries(
            ObjectProvider<McpToolConfig.ToolClients> clients,
            ObjectMapper json,
            Environment env,
            LocalFoodTerms localFoodTerms) {
        this.clients = clients;
        this.json = json;
        this.env = env;
        this.localFoodTerms = localFoodTerms;
    }

    public String transport(String role, Requirements input) {
        if (!usesTuniu(role)) return null;
        if (TripCities.provinceOnly(input.getOrigin()))
            return TripCities.askForCity("出发地", input.getOrigin());
        if (TripCities.provinceOnly(input.getDestination()))
            return TripCities.askForCity("目的地", input.getDestination());
        boolean train = role.equals("train");
        String label = train ? "高铁" : "航班";
        LocalDate date = date(input.getStartDate());
        if (date == null) return label + "查询需要明确的出发日期。";
        try {
            JsonNode result =
                    query(
                            role,
                            train ? "searchLowestPriceTrain" : "searchLowestPriceFlight",
                            Map.of(
                                    "departureCityName",
                                    TripCities.cityName(input.getOrigin()),
                                    "arrivalCityName",
                                    TripCities.cityName(input.getDestination()),
                                    "departureDate",
                                    date.toString()));
            if (!result.path("successCode").asBoolean(true)
                    || !result.path("success").asBoolean(true)) {
                return "途牛暂未返回"
                        + input.getOrigin()
                        + "到"
                        + input.getDestination()
                        + "在 "
                        + date
                        + " 的可用"
                        + label
                        + "结果。";
            }
            JsonNode timetables = result.path("data");
            if (!timetables.isArray()) throw new IllegalStateException("数据源未返回班次列表");
            List<String> options = new ArrayList<>();
            for (JsonNode row : timetables) {
                String number = row.path(train ? "trainNum" : "flightNumber").asText();
                if (number.isBlank() || (train && !number.matches("[GDC]\\d+"))) continue;
                String departure = row.path("departureTime").asText();
                String arrival = row.path("arrivalTime").asText();
                if (!departure.startsWith(date.toString()) || arrival.isBlank()) continue;
                long minutes;
                try {
                    minutes =
                            Duration.between(
                                            LocalDateTime.parse(departure.replace(' ', 'T')),
                                            LocalDateTime.parse(arrival.replace(' ', 'T')))
                                    .toMinutes();
                } catch (java.time.DateTimeException ignored) {
                    continue;
                }
                if (minutes < 1) continue;
                String origin = row.path(train ? "departStationName" : "departureAirport").asText();
                String destination =
                        row.path(train ? "destStationName" : "arrivalAirport").asText();
                options.add(
                        "- "
                                + number
                                + " · "
                                + origin
                                + " → "
                                + destination
                                + " · "
                                + departure
                                + " → "
                                + arrival
                                + " · "
                                + minutes
                                + " 分钟"
                                + detail(
                                        train
                                                ? row.path("price").path("edzPrice")
                                                : row.path("basePrice"),
                                        train ? "二等座 ¥" : "基础票价 ¥")
                                + (train ? "" : detail(row.path("totalTax"), "税费 ¥"))
                                + detail(
                                        train
                                                ? row.path("seatAvailable").path("edzNum")
                                                : row.path("remainingSeats"),
                                        "余座 "));
                if (options.size() == 5) break;
            }
            return options.isEmpty()
                    ? "途牛未返回 " + date + " 符合条件的" + label + "。"
                    : "【"
                            + label
                            + "查询 · 途牛】\n"
                            + String.join("\n", options)
                            + "\n以上为车次或航班的段内用时，接驳用时待查。";
        } catch (Exception error) {
            return unavailable(role, label, error);
        }
    }

    public String hotels(Requirements input) {
        if (!usesTuniu("hotel")) return null;
        if (TripCities.provinceOnly(input.getDestination()))
            return TripCities.askForCity("目的地", input.getDestination());
        LocalDate date = date(input.getStartDate());
        if (date == null) return "酒店查询需要明确的入住日期。";
        try {
            var people = PEOPLE.matcher(input.getPeople() == null ? "" : input.getPeople());
            int adults = people.find() ? Integer.parseInt(people.group()) : 1;
            HotelBudget budget = hotelBudget(input.getAccommodationPreferences());
            Map<String, Object> arguments =
                    new HashMap<>(
                            Map.of(
                                    "cityName", TripCities.cityName(input.getDestination()),
                                    "checkIn", date.toString(),
                                    "checkOut", date.plusDays(1).toString(),
                                    "adultNum", adults));
            if (budget != null) arguments.put("prices", budget.min() + "-" + budget.max());
            JsonNode result = query("hotel", "tuniuHotelSearch", arguments);
            JsonNode listedHotels = result.path("hotels");
            if (!result.path("success").asBoolean(true) || !listedHotels.isArray())
                throw new IllegalStateException("数据源未返回酒店列表");
            String city = input.getDestination();
            String resultCity = result.path("cityInfo").path("cityName").asText();
            if (!resultCity.isBlank() && !TripCities.isSameCity(city, resultCity))
                return "途牛返回的城市与目的地「" + city + "」不一致，请核对目的地后重新查询。";
            List<JsonNode> hotels = new ArrayList<>();
            boolean hasLocalHotel = false;
            for (JsonNode hotel : listedHotels) {
                if (hotel.path("hotelName").asText().isBlank()) continue;
                String hotelCity = hotel.path("cityName").asText();
                if (!TripCities.isSameCity(city, hotelCity.isBlank() ? resultCity : hotelCity))
                    continue;
                hasLocalHotel = true;
                BigDecimal price = hotelPrice(hotel);
                if (budget == null || budget.includes(price)) hotels.add(hotel);
            }
            if (!hasLocalHotel && !listedHotels.isEmpty()) return "途牛未返回能核实城市为「" + city + "」的酒店。";
            if (budget != null) hotels.sort(Comparator.comparing(LiveTravelQueries::hotelPrice));
            String priceFilter = budget == null ? "" : " · 每晚 " + budget.description();
            if (hotels.isEmpty()) {
                return budget == null
                        ? "途牛未返回该日期的酒店。"
                        : "途牛暂未返回每晚 " + budget.description() + " 的酒店，可以调整预算后再查。";
            }
            var options =
                    hotels.stream()
                            .limit(5)
                            .map(
                                    hotel ->
                                            "- "
                                                    + hotel.path("hotelName").asText()
                                                    + detail(hotel.path("address"), "")
                                                    + detail(hotel.path("lowestPrice"), "起价 ¥"))
                            .toList();
            return "入住 "
                    + date
                    + " · 退房 "
                    + date.plusDays(1)
                    + " · 途牛查询"
                    + priceFilter
                    + "\n\n"
                    + String.join("\n", options);
        } catch (Exception error) {
            return unavailable("hotel", "酒店", error);
        }
    }

    private static HotelBudget hotelBudget(String preference) {
        if (preference == null || preference.isBlank()) return null;
        String text = Normalizer.normalize(preference, Normalizer.Form.NFKC);
        var range = HOTEL_PRICE_RANGE.matcher(text);
        if (range.find()) {
            int min = Integer.parseInt(range.group(1));
            int max = Integer.parseInt(range.group(2));
            if (min <= max) return new HotelBudget(min, max);
        }
        var ceiling = HOTEL_PRICE_CEILING.matcher(text);
        if (ceiling.find()) {
            return new HotelBudget(
                    0,
                    Integer.parseInt(
                            ceiling.group(1) != null ? ceiling.group(1) : ceiling.group(2)));
        }
        return ECONOMY_HOTEL.matcher(text).find()
                ? new HotelBudget(0, ECONOMY_HOTEL_CEILING)
                : null;
    }

    private static BigDecimal hotelPrice(JsonNode hotel) {
        String price = hotel.path("lowestPrice").asText().strip();
        try {
            return new BigDecimal(price);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record HotelBudget(int min, int max) {
        boolean includes(BigDecimal price) {
            return price != null
                    && price.compareTo(BigDecimal.valueOf(min)) >= 0
                    && price.compareTo(BigDecimal.valueOf(max)) <= 0;
        }

        String description() {
            return min == 0 ? "¥" + max + " 以内" : "¥" + min + "–" + max;
        }
    }

    public String food(Requirements input) {
        String key = env.getProperty("mcp.amap-key", "").strip();
        if (!enabled() || key.isEmpty() || !env.getProperty("mcp.amap-url", "").isBlank())
            return null;
        try {
            boolean local = localFoodTerms.requested(input);
            PlannerInput turn = input instanceof PlannerInput planner ? planner : null;
            String action = turn == null ? "" : FoodFollowUp.validatedAction(turn.getFoodAction());
            String previous =
                    turn == null || turn.getFoodPreviousOutput() == null
                            ? ""
                            : turn.getFoodPreviousOutput();
            Set<String> excluded =
                    action.isBlank() ? Set.of() : FoodFollowUp.previousPlaces(previous);
            int requested = turn == null ? 0 : turn.getFoodLimit();
            int limit = requested > 0 ? FoodFollowUp.limit(requested) : local ? 3 : 5;
            List<String> dishes =
                    local
                            ? localFoodTerms.forTrip(
                                    input,
                                    excluded.isEmpty()
                                            ? Set.of()
                                            : FoodFollowUp.previousDishes(previous),
                                    limit)
                            : List.of();
            if (local && dishes.isEmpty()) return "暂时无法确定当地特色菜的查询词。可以告诉我想尝哪道菜。";
            String keyword =
                    local ? String.join("|", dishes) : foodKeyword(input.getFoodPreferences());
            String region = "北京".equals(input.getDestination()) ? "北京市" : input.getDestination();
            int pageSize = local || !action.isBlank() ? 25 : Math.max(5, limit);
            JsonNode response = searchFood(key, region, keyword, local, pageSize, 1);
            if (!"1".equals(response.path("status").asText())) {
                return response.path("info").asText().contains("QPS")
                        ? "高德餐厅查询达到频率限制，请稍后重试。"
                        : "高德餐厅查询暂不可用，请稍后重试。";
            }
            var candidates = json.createArrayNode();
            response.path("pois").forEach(candidates::add);
            Map<String, JsonNode> matches =
                    local ? localFoodMatches(candidates, dishes, excluded) : Map.of();
            List<String> places =
                    local ? List.of() : ordinaryFoodResults(candidates, excluded, limit);
            String missed =
                    local
                            ? dishes.stream()
                                    .filter(dish -> !matches.containsKey(dish))
                                    .findFirst()
                                    .orElse("")
                            : "";
            if (!action.isBlank() && (local ? matches.size() < limit : places.size() < limit)
                    || !missed.isBlank()) {
                try {
                    boolean byDish = !missed.isBlank();
                    JsonNode extra =
                            searchFood(
                                    key,
                                    region,
                                    byDish ? missed : keyword,
                                    local,
                                    pageSize,
                                    byDish ? 1 : 2);
                    if ("1".equals(extra.path("status").asText()))
                        extra.path("pois").forEach(candidates::add);
                } catch (IOException error) {
                    if (matches.isEmpty() && places.isEmpty()) throw error;
                    LOG.warn("补查餐厅失败 ({})", error.getClass().getSimpleName());
                }
                if (local) return localFoodResults(localFoodMatches(candidates, dishes, excluded));
                places = ordinaryFoodResults(candidates, excluded, limit);
            }
            if (local) return localFoodResults(matches);
            return places.isEmpty() ? "高德未返回符合条件的餐厅。" : "高德地点查询\n\n" + String.join("\n", places);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return unavailable("amap", "餐厅", error);
        }
    }

    private static List<String> ordinaryFoodResults(
            JsonNode candidates, Set<String> excluded, int limit) {
        List<String> places = new ArrayList<>();
        Set<String> used = new HashSet<>(excluded);
        for (JsonNode poi : candidates) {
            String name = poi.path("name").asText();
            if (!name.isBlank() && used.add(FoodFollowUp.placeKey(name)))
                places.add("- " + name + detail(poi.path("address"), ""));
            if (places.size() == limit) break;
        }
        return places;
    }

    private static String foodKeyword(String preference) {
        if (preference == null || preference.isBlank() || "无".equals(preference.strip()))
            return "特色餐厅";
        String keyword = preference.strip();
        return keyword.substring(0, Math.min(40, keyword.length()));
    }

    private JsonNode searchFood(
            String key, String region, String keyword, boolean local, int pageSize, int page)
            throws IOException, InterruptedException {
        var url =
                URI.create(
                        "https://restapi.amap.com/v5/place/text?key="
                                + encode(key)
                                + "&region="
                                + encode(region)
                                + "&city_limit=true&types=050000"
                                + (local ? "&show_fields=business" : "")
                                + "&page_size="
                                + pageSize
                                + "&page_num="
                                + page
                                + "&keywords="
                                + encode(keyword));
        var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(8)).GET().build();
        JsonNode response = placeResponse(request);
        if (response.path("info").asText().contains("QPS")) {
            Thread.sleep(1200);
            response = placeResponse(request);
        }
        return response;
    }

    static Map<String, JsonNode> localFoodMatches(JsonNode candidates, List<String> dishes) {
        return localFoodMatches(candidates, dishes, Set.of());
    }

    private static Map<String, JsonNode> localFoodMatches(
            JsonNode candidates, List<String> dishes, Set<String> excluded) {
        Map<String, JsonNode> matches = new LinkedHashMap<>();
        Set<String> used = new HashSet<>(excluded);
        for (String dish : dishes) {
            JsonNode byTag = null;
            for (JsonNode poi : candidates) {
                String name = poi.path("name").asText();
                if (name.isBlank() || used.contains(FoodFollowUp.placeKey(name))) continue;
                if (name.contains(dish)) {
                    byTag = poi;
                    break;
                }
                if (byTag == null && poi.path("business").path("tag").asText().contains(dish)) {
                    byTag = poi;
                }
            }
            if (byTag != null) {
                matches.put(dish, byTag);
                used.add(FoodFollowUp.placeKey(byTag.path("name").asText()));
            }
        }
        return matches;
    }

    static String localFoodResults(Map<String, JsonNode> matches) {
        if (matches.isEmpty()) return "高德暂未返回能对应当地特色菜的餐厅。";
        var places =
                matches.entrySet().stream()
                        .map(
                                entry -> {
                                    String dish = entry.getKey();
                                    JsonNode poi = entry.getValue();
                                    String name = poi.path("name").asText();
                                    String evidence = name.contains(dish) ? "" : " · 高德特色菜标签";
                                    return "- "
                                            + dish
                                            + " · "
                                            + name
                                            + detail(poi.path("address"), "")
                                            + evidence;
                                })
                        .toList();
        return "按当地菜品查询 · 高德地点\n\n" + String.join("\n", places);
    }

    private JsonNode placeResponse(HttpRequest request) throws IOException, InterruptedException {
        var result = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (result.statusCode() != 200) throw new IOException("HTTP " + result.statusCode());
        return json.readTree(result.body());
    }

    private boolean enabled() {
        return env.getProperty("mcp.enabled", Boolean.class, false);
    }

    private boolean usesTuniu(String role) {
        return enabled()
                && !env.getProperty("mcp.tuniu-key", "").isBlank()
                && env.getProperty("mcp." + role + "-url", "").isBlank();
    }

    private JsonNode query(String role, String name, Map<String, ?> arguments) throws Exception {
        var configuration = clients.getIfAvailable();
        var client = configuration == null ? null : configuration.clients().get(role);
        if (client == null
                || !configuration.queryOnly().getOrDefault(client, Set.of()).contains(name)) {
            throw new IllegalStateException("查询工具未启用");
        }
        var request =
                ToolExecutionRequest.builder()
                        .name(name)
                        .arguments(json.writeValueAsString(arguments))
                        .build();
        var execution = client.executeTool(request);
        if (execution.isError()) throw new IllegalStateException("查询调用失败");
        JsonNode result = json.readTree(execution.resultText());
        // 途牛火车与机票把文本 JSON 放在 MCP 的结构化 result 字段内。
        if (result != null && result.path("result").isTextual()) {
            result = json.readTree(result.path("result").asText());
        }
        if (result == null || !result.isObject()) {
            throw new IllegalStateException("数据源未返回有效结果");
        }
        return result;
    }

    static LocalDate date(String input) {
        if (input == null) return null;
        LocalDate today = LocalDate.now(SHANGHAI);
        input = input.strip();
        var date = DATE.matcher(input);
        try {
            if (date.find()) {
                String[] parts = date.group().split("[-年月]");
                return LocalDate.of(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            }
            if (input.startsWith("大后天")) return today.plusDays(3);
            if (input.startsWith("后天")) return today.plusDays(2);
            if (input.startsWith("明天")) return today.plusDays(1);
            if (input.startsWith("今天")) return today;
            if (input.matches("(本|这|下)周[一二三四五六日天].*")) {
                int day = "一二三四五六日天".indexOf(input.charAt(2));
                return today.minusDays(today.getDayOfWeek().getValue() - 1)
                        .plusDays(input.startsWith("下") ? 7 : 0)
                        .plusDays(Math.min(day, 6));
            }
        } catch (java.time.DateTimeException ignored) {
            return null;
        }
        return null;
    }

    private static String detail(JsonNode node, String prefix) {
        String value = node.asText("").strip();
        return value.isEmpty() ? "" : " · " + prefix + value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unavailable(String role, String label, Exception error) {
        LOG.warn("实时查询失败 {} ({})", role, error.getClass().getSimpleName());
        return label + "数据源暂不可用，请稍后重试。";
    }
}
