package com.example.rag;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RecipeController {
    private final RecipeService service;
    public RecipeController(RecipeService service) { this.service = service; }
    @GetMapping("/catalog") public Object catalog() { return service.catalog(); }
    @PostMapping("/search") public Object search(@RequestBody RecipeService.Options options) { return service.search(options); }
    @PostMapping("/calibrate") public Object calibrate(@RequestBody RecipeService.Options options) { return service.calibrate(options); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(502).body(Map.of("message", e.getMessage())); }
}
