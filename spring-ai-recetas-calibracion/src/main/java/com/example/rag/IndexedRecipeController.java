package com.example.rag;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
@RequestMapping("/api/recipes")
public class IndexedRecipeController {
    private final RecipeIndex index;
    private final RecipeAnswerService answers;
    public IndexedRecipeController(RecipeIndex index, RecipeAnswerService answers) { this.index = index; this.answers = answers; }
    @GetMapping public Object search(@RequestParam String query, @RequestParam(defaultValue="3") int topK,
            @RequestParam(required=false) String diet, @RequestParam(defaultValue="0") int maxMinutes) {
        return index.search(query, topK, diet, maxMinutes);
    }
    @PostMapping("/answer") public Object answer(@RequestBody RecipeAnswerService.Request request) { return answers.answer(request); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("message", e.getMessage())); }
}
