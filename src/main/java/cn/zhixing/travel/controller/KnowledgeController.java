package cn.zhixing.travel.controller;

import cn.zhixing.travel.knowledge.TravelDocumentParser;
import cn.zhixing.travel.knowledge.TravelKnowledgeService;
import cn.zhixing.travel.model.dto.ApiResult;
import cn.zhixing.travel.rag.RagContentRetriever;
import cn.zhixing.travel.utils.RequestContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {
    private final TravelKnowledgeService knowledge;
    private final RagContentRetriever retriever;

    public KnowledgeController(TravelKnowledgeService knowledge, RagContentRetriever retriever) {
        this.knowledge = knowledge;
        this.retriever = retriever;
    }

    @GetMapping("/formats")
    public ApiResult<?> formats() {
        return ApiResult.success(
                Map.of(
                        "extensions",
                        TravelDocumentParser.EXTENSIONS.stream().sorted().toList(),
                        "maxBytes",
                        20971520,
                        "maxTextChars",
                        200000,
                        "ocrEnabled",
                        false));
    }

    @GetMapping("/documents")
    public ApiResult<?> list() {
        return ApiResult.success(knowledge.list(RequestContext.getUserId()));
    }

    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    public ApiResult<?> upload(@RequestParam("file") MultipartFile file) {
        return ApiResult.success(knowledge.upload(RequestContext.getUserId(), file));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResult<?> delete(@PathVariable String id) {
        knowledge.delete(RequestContext.getUserId(), id);
        return ApiResult.success();
    }

    @PostMapping("/search")
    public ApiResult<?> search(@Valid @RequestBody Search request) {
        return ApiResult.success(
                retriever.retrieveForOwner(RequestContext.getUserId(), request.query()).stream()
                        .map(content -> content.textSegment().text())
                        .toList());
    }

    public record Search(@NotBlank @Size(max = 2000) String query) {}
}
