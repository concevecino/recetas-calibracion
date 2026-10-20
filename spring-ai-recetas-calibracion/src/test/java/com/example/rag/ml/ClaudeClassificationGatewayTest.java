package com.example.rag.ml;

import com.example.rag.ml.domain.MlException;
import com.example.rag.ml.infrastructure.ClaudeClassificationGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static com.example.rag.ml.domain.MlTypes.Experiment;
import static com.example.rag.ml.domain.MlTypes.ExperimentInput;
import static com.example.rag.ml.domain.MlTypes.Sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeClassificationGatewayTest {

    @Test
    void adapterUsesOneModelCallAndPropagatesUsage() {
        ChatModel model = mock(ChatModel.class);
        var factory = new StaticListableBeanFactory();
        factory.addBean("model", model);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("postre"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(21, 2)).build()));
        try (var gateway = new ClaudeClassificationGateway(
                factory.getBeanProvider(ChatModel.class), "fake", "30", "0")) {
            var experiment = experiment(gateway);
            var prediction = gateway.predict(experiment, List.of("principal", "postre"), List.of(
                    new Sample("train1", "Manzana con canela", "postre", null)),
                    new Sample("test1", "Tarta de fruta", "postre", null));
            assertThat(prediction.predicted()).isEqualTo("postre");
            assertThat(prediction.inputTokens()).isEqualTo(21L);
            assertThat(prediction.outputTokens()).isEqualTo(2L);
            var prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(model, times(1)).call(prompt.capture());
            assertThat(prompt.getValue().getContents()).contains("Manzana con canela", "Tarta de fruta");
            assertThat(prompt.getValue().getContents()).doesNotContain("Etiqueta: postre\nTexto a clasificar:\npostre");
        }
    }

    @Test
    void missingProviderAndInvalidLabelsFailExplicitly() {
        var factory = new StaticListableBeanFactory();
        try (var gateway = new ClaudeClassificationGateway(
                factory.getBeanProvider(ChatModel.class), "fake", "30", "0")) {
            assertThatThrownBy(() -> gateway.predict(experiment(gateway), List.of("a", "b"), List.of(),
                    new Sample("x", "text", "a", null))).isInstanceOfSatisfying(MlException.class,
                            exception -> assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
        }
        ChatModel model = mock(ChatModel.class);
        factory.addBean("model", model);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("unknown")))));
        try (var gateway = new ClaudeClassificationGateway(
                factory.getBeanProvider(ChatModel.class), "fake", "30", "0")) {
            assertThatThrownBy(() -> gateway.predict(experiment(gateway), List.of("a", "b"), List.of(),
                    new Sample("x", "text", "a", null))).isInstanceOfSatisfying(MlException.class,
                            exception -> assertThat(exception.code()).isEqualTo("INVALID_MODEL_OUTPUT"));
        }
    }

    private Experiment experiment(ClaudeClassificationGateway gateway) {
        return new Experiment("e", new ExperimentInput("s", "CLAUDE_INFERENCE", "CLAUDE_FEW_SHOT", 1.0,
                "nfc-word-count-v1", null, "", 1), "d", "SUCCEEDED", false, null, null,
                null, "now", gateway.configuration(), null);
    }
}
