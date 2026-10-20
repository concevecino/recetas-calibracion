package com.example.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

class IndexedRecipeTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RecipeIndex index = new RecipeIndex(mapper);
    IndexedRecipeTest() throws Exception {}
    ObjectProvider<ChatModel> provider(ChatModel model) {
        var factory = new StaticListableBeanFactory();
        if (model != null) factory.addBean("model", model);
        return factory.getBeanProvider(ChatModel.class);
    }
    @Test void AC1_indexFindsRecipeWithAccentsAndDeterministicRanking() {
        var result = index.search("TORTÍLLA española", 1, null, 0);
        assertThat(result.recipes()).hasSize(1);
        assertThat(result.recipes().getFirst().path("id").asText()).isEqualTo("receta_0001");
        assertThat(result.candidates()).isLessThan(result.catalogSize());
        assertThat(index.search("TORTÍLLA española", 1, null, 0)).isEqualTo(result);
    }
    @Test void AC2_filtersAndInputValidation() {
        var results = index.search("garbanzos", 10, "vegana", 60).recipes();
        assertThat(results).isNotEmpty().allSatisfy(r -> {
            assertThat(r.path("dietas").toString()).contains("vegana");
            assertThat(r.path("tiempo_minutos").asInt()).isLessThanOrEqualTo(60);
        });
        assertThatThrownBy(() -> index.search("", 3, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.search("pollo", 11, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void AC3_retrievalReducesContextAndPropagatesActualProviderUsage() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("Tortilla [receta_0001]"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(123, 20)).build()));
        var service = new RecipeAnswerService(index, mapper, provider(model));
        var answer = service.answer(new RecipeAnswerService.Request("tortilla española", 1, null, 0, 4000));
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(1)).call(prompt.capture());
        String sent = prompt.getValue().getContents();
        assertThat(sent).contains("receta_0001").doesNotContain("receta_0061", "texto_busqueda");
        assertThat(answer.recipeIds()).containsExactly("receta_0001");
        assertThat(answer.usage().contextEstimatedTokens()).isLessThan(answer.usage().fullCatalogEstimatedTokens() / 10);
        assertThat(answer.usage().contextEstimatedTokens()).isLessThanOrEqualTo(4000);
        assertThat(answer.usage().actualInputTokens()).isEqualTo(123L);
        assertThat(answer.usage().actualOutputTokens()).isEqualTo(20L);
        System.out.printf("Contexto estimado: %d; catálogo compacto: %d; reducción: %.2f%%%n",
                answer.usage().contextEstimatedTokens(), answer.usage().fullCatalogEstimatedTokens(),
                100.0 * (1 - (double) answer.usage().contextEstimatedTokens() / answer.usage().fullCatalogEstimatedTokens()));
    }
    @Test void AC4_emptyResultsAndInsufficientBudgetNeverCallClaude() {
        var model = mock(ChatModel.class);
        var service = new RecipeAnswerService(index, mapper, provider(model));
        for (var request : List.of(new RecipeAnswerService.Request("xyznotfound", 3, null, 0, 4000),
                new RecipeAnswerService.Request("tortilla", 3, null, 0, 2))) {
            var answer = service.answer(request);
            assertThat(answer.recipeIds()).isEmpty();
            assertThat(answer.usage().actualInputTokens()).isZero();
        }
        verifyNoInteractions(model);
    }
    @Test void AC5_budgetNeverTruncatesJsonAndAcceptsOnlyWholeRecipes() throws Exception {
        var service = new RecipeAnswerService(index, mapper, provider(null));
        for (int budget : List.of(2, 500, 1000, 4000)) {
            var context = service.context(index.search("pollo arroz", 10, null, 0).recipes(), budget);
            assertThat(RecipeAnswerService.estimate(context.json())).isLessThanOrEqualTo(budget);
            assertThat(mapper.readTree(context.json()).size()).isEqualTo(context.ids().size());
        }
    }
    @Test void AC6_restWorksOfflineAndReportsConfigurationAndProviderErrors() throws Exception {
        var service = new RecipeAnswerService(index, mapper, provider(null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IndexedRecipeController(index, service)).build();
        mvc.perform(get("/api/recipes").param("query", "tortilla")).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipes[0].id").exists());
        mvc.perform(get("/api/recipes").param("query", " ")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/recipes/answer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"tortilla\"}")).andExpect(status().isServiceUnavailable());
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("secret-provider-body"));
        mvc = MockMvcBuilders.standaloneSetup(new IndexedRecipeController(index,
                new RecipeAnswerService(index, mapper, provider(model)))).build();
        mvc.perform(post("/api/recipes/answer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"tortilla\"}")).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-provider-body"))));
    }
}
