package cn.zhixing.travel.controller;

import cn.zhixing.travel.memory.MemoryModels.Operation;
import cn.zhixing.travel.memory.TravelMemoryService;
import cn.zhixing.travel.model.dto.ApiResult;
import cn.zhixing.travel.utils.RequestContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/memory/preferences")
public class MemoryController {
    private final TravelMemoryService memory;

    public MemoryController(TravelMemoryService memory) {
        this.memory = memory;
    }

    @GetMapping
    public ApiResult<?> get() {
        return ApiResult.success(memory.profile(RequestContext.getUserId()));
    }

    @PutMapping
    public ApiResult<?> update(@Valid @RequestBody PreferenceRequest request) {
        var operations =
                request.preferences().entrySet().stream()
                        .map(
                                entry ->
                                        new Operation(
                                                entry.getKey(),
                                                entry.getValue() == null
                                                                || entry.getValue().isBlank()
                                                        ? "DELETE"
                                                        : "SET",
                                                entry.getValue()))
                        .toList();
        return ApiResult.success(
                memory.update(
                                RequestContext.getUserId(),
                                request.expectedVersion(),
                                request.operationId(),
                                operations)
                        .profile());
    }

    public record PreferenceRequest(
            long expectedVersion,
            @NotBlank String operationId,
            @NotNull Map<String, String> preferences) {}
}
