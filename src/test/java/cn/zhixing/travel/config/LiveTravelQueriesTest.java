package cn.zhixing.travel.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.agent.food.LocalFoodTerms;
import cn.zhixing.travel.agent.requirement.Requirements;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutionResult;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

class LiveTravelQueriesTest {
    @Test
    @SuppressWarnings("unchecked")
    void hotelBudgetIsSentAndProviderRowsAreFilteredAndSorted() throws Exception {
        var client = mock(McpClient.class);
        var clients = mock(ObjectProvider.class);
        when(clients.getIfAvailable())
                .thenReturn(
                        new McpToolConfig.ToolClients(
                                Map.of("hotel", client),
                                Map.of(client, Set.of("tuniuHotelSearch"))));
        when(client.executeTool(any()))
                .thenReturn(
                        ToolExecutionResult.builder()
                                .resultText(
                                        """
{"cityInfo":{"cityName":"北京市"},"hotels":[
  {"hotelName":"上海的低价结果","cityName":"上海","lowestPrice":100},
  {"hotelName":"超出预算","lowestPrice":430},
  {"hotelName":"价格较高","lowestPrice":"385.50"},
  {"hotelName":"符合两种预算","lowestPrice":260},
  {"hotelName":"缺少报价"}
]}
""")
                                .build());
        var json = new ObjectMapper();
        var environment =
                new MockEnvironment()
                        .withProperty("mcp.enabled", "true")
                        .withProperty("mcp.tuniu-key", "test-fixture");
        var lookup =
                new LiveTravelQueries(
                        clients,
                        json,
                        environment,
                        new LocalFoodTerms(mock(ChatModel.class), json));
        var input = new Requirements("威海", "北京", "2026-11-26", "3人", "无");

        input.setAccommodationPreferences("便宜一点");
        String economy = lookup.hotels(input);
        assertTrue(economy.indexOf("符合两种预算") < economy.indexOf("价格较高"));
        assertFalse(economy.contains("超出预算"));
        assertFalse(economy.contains("上海"));
        assertFalse(economy.contains("缺少报价"));

        input.setAccommodationPreferences("每晚 300 元以内");
        String explicit = lookup.hotels(input);
        assertTrue(explicit.contains("符合两种预算"));
        assertFalse(explicit.contains("价格较高"));

        var calls = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(client, times(2)).executeTool(calls.capture());
        assertEquals(
                "0-400",
                json.readTree(calls.getAllValues().get(0).arguments()).path("prices").asText());
        assertEquals("0-300", json.readTree(calls.getValue().arguments()).path("prices").asText());
        assertEquals(3, json.readTree(calls.getValue().arguments()).path("adultNum").asInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hotelLookupSkipsProvinceAndRejectsProviderFallbackCity() throws Exception {
        var client = mock(McpClient.class);
        var clients = mock(ObjectProvider.class);
        when(clients.getIfAvailable())
                .thenReturn(
                        new McpToolConfig.ToolClients(
                                Map.of("hotel", client),
                                Map.of(client, Set.of("tuniuHotelSearch"))));
        when(client.executeTool(any()))
                .thenReturn(
                        ToolExecutionResult.builder()
                                .resultText(
                                        """
{"cityInfo":{"cityName":"上海"},"hotels":[{"hotelName":"上海酒店","cityName":"上海"}]}
""")
                                .build());
        var json = new ObjectMapper();
        var environment =
                new MockEnvironment()
                        .withProperty("mcp.enabled", "true")
                        .withProperty("mcp.tuniu-key", "test-fixture");
        var lookup =
                new LiveTravelQueries(
                        clients,
                        json,
                        environment,
                        new LocalFoodTerms(mock(ChatModel.class), json));
        var trip = new Requirements("威海", "黑龙江", "2026-11-26", "5", "无");

        assertTrue(lookup.hotels(trip).contains("具体城市"));
        verifyNoInteractions(client);
        trip.setDestination("哈尔滨市");
        String reply = lookup.hotels(trip);
        assertTrue(reply.contains("不一致"));
        assertFalse(reply.contains("上海酒店"));
        var argument = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(client).executeTool(argument.capture());
        assertEquals(
                "哈尔滨", json.readTree(argument.getValue().arguments()).path("cityName").asText());
    }

    @Test
    void beijingSpecialtiesUseDishNamesAndSkipUnrelatedRestaurants() throws Exception {
        var model = mock(ChatModel.class);
        var json = new ObjectMapper();
        var terms = new LocalFoodTerms(model, json);
        var input = new Requirements("威海", "北京", "2026-11-26", "3人", "无");
        for (String request : List.of("当地特色", "北京当地特色", "北京的特色")) {
            input.setFoodPreferences(request);
            assertTrue(terms.requested(input));
            assertEquals(List.of("烤鸭", "涮肉", "炸酱面"), terms.forTrip(input));
        }
        verifyNoInteractions(model);
        String result =
                LiveTravelQueries.localFoodResults(
                        LiveTravelQueries.localFoodMatches(
                                json.readTree(
                                        """
                                        [{"id":"1","name":"重庆火锅"}, {"id":"2","name":"示例烤鸭馆"},
                                         {"id":"3","name":"示例涮肉馆"}, {"id":"4","name":"示例炸酱面"}]
                                        """),
                                terms.forTrip(input)));
        assertTrue(result.contains("烤鸭 · 示例烤鸭馆"));
        assertTrue(result.contains("涮肉 · 示例涮肉馆"));
        assertTrue(result.contains("炸酱面 · 示例炸酱面"));
        assertFalse(result.contains("重庆火锅"));
    }

    @Test
    void jinanDishesCanMatchProviderSpecialtyTagsWithoutDishInTheName() throws Exception {
        var model = mock(ChatModel.class);
        var json = new ObjectMapper();
        var terms = new LocalFoodTerms(model, json);
        var input = new Requirements("威海", "济南市", "2026-11-26", "5人", "3天");
        input.setFoodPreferences("当地特色");
        assertEquals(List.of("把子肉", "甜沫", "油旋"), terms.forTrip(input));
        assertEquals(
                List.of("九转大肠", "糖醋鲤鱼", "葱烧海参"),
                terms.forTrip(input, Set.of("把子肉", "甜沫", "油旋"), 3));
        var candidates =
                json.readTree(
                        """
                        [{"id":"1","name":"辣牛蛙","business":{"tag":"牛蛙,川菜"}},
                         {"id":"2","name":"济南小吃铺","business":{"tag":"甜沫,小米粥"}},
                         {"id":"3","name":"聚丰德","business":{"tag":"九转大肠,老济南油旋"}},
                         {"id":"4","name":"示例把子肉","address":"示例地址"}]
                        """);
        String result =
                LiveTravelQueries.localFoodResults(
                        LiveTravelQueries.localFoodMatches(candidates, terms.forTrip(input)));
        assertTrue(result.contains("甜沫 · 济南小吃铺 · 高德特色菜标签"));
        assertTrue(result.contains("油旋 · 聚丰德 · 高德特色菜标签"));
        assertTrue(result.contains("把子肉 · 示例把子肉 · 示例地址"));
        assertFalse(result.contains("辣牛蛙"));
        verifyNoInteractions(model);
    }

    @Test
    void anotherCityUsesSpecificModelTermsAndRejectsGenericTerms() throws Exception {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("[\"米粉\",\"特色美食\",\"米粉\",\"油茶\"]");
        var terms = new LocalFoodTerms(model, new ObjectMapper());
        var input = new Requirements("威海", "桂林", "2026-11-26", "3人", "无");
        input.setFoodPreferences("当地特色，不吃辣");
        assertEquals(List.of("米粉", "油茶"), terms.forTrip(input));
        verify(model).chat(contains("当地特色，不吃辣"));
    }
}
