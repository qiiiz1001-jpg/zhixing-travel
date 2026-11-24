package cn.zhixing.travel.controller;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/runtime")
public class RuntimeController {
    private final Environment environment;

    public RuntimeController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        boolean infra = java.util.Arrays.asList(environment.getActiveProfiles()).contains("infra");
        return Map.of("modelEnabled", !infra, "mode", infra ? "infrastructure" : "model");
    }
}
