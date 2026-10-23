package com.cobug.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @RequestMapping(
            value = {"/", "/health", "/ping", "/api/health", "/api/ping"},
            method = {RequestMethod.GET, RequestMethod.HEAD}
    )
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "COBUG API",
                "timestamp", System.currentTimeMillis()
        ));
    }
}