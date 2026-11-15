package com.company.incert.kms.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(
                "service", "Incert KMS Simulator API",
                "status", "UP",
                "consoleUiUrl", "http://localhost:3001",
                "swaggerUiUrl", "http://localhost:8090/swagger-ui.html"
        );
    }
}

