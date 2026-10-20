package com.example.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: two billable requests, including the full compact catalog baseline. */
@SpringBootTest
@ActiveProfiles("claude")
@EnabledIfEnvironmentVariable(named="RUN_CLAUDE_LIVE", matches="true")
class ClaudeLiveTest {
    @Autowired RecipeAnswerService answers;
    @Autowired RecipeIndex index;
    @Autowired ChatModel model;
    @Test void actualInputUsageIsLowerThanFullCatalogBaseline() {
        String query = "tortilla española";
        var reduced = answers.answer(new RecipeAnswerService.Request(query, 1, null, 0, 4000));
        String full = "[" + String.join(",", index.catalog().stream().map(answers::compact).toList()) + "]";
        var baseline = model.call(new Prompt(List.of(new SystemMessage(RecipeAnswerService.SYSTEM),
                new UserMessage("Consulta: " + query + "\nRecetas JSON:\n" + full))));
        var fullInput = baseline.getMetadata().getUsage().getPromptTokens();
        assertThat(fullInput).isNotNull().isPositive();
        assertThat(reduced.usage().actualInputTokens()).isNotNull().isPositive().isLessThan(fullInput.longValue());
        assertThat(reduced.answer()).isNotBlank();
        System.out.printf("Tokens reales Claude: indexado=%d, catálogo=%d%n", reduced.usage().actualInputTokens(), fullInput);
    }
}
