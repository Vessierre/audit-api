package com.auditoria.ai.service;

import com.auditoria.ai.dto.NaturalAuditRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);
    private static final String FALLBACK_MESSAGE =
            "A Api gratuita do gemini não é humilde e recusou todas as requisições que tentei fazer :(";

    private static final int RETRY_ATTEMPTS = 3;
    private static final long TIMEOUT_SECONDS = 120;

    private final PromptService promptService;
    private final NaturalInputProcessor naturalInputProcessor;
    private final ExecutorService modelExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final GoogleGenAiChatModel hydrogenChatModel;
    private final GoogleGenAiChatModel flashChatModel;
    private final GoogleGenAiChatModel flashLiteChatModel;

    public AuditService(
            PromptService promptService,
            NaturalInputProcessor naturalInputProcessor,

            @Qualifier("hydrogenChatModel")
            GoogleGenAiChatModel hydrogenChatModel,

            @Qualifier("flashChatModel")
            GoogleGenAiChatModel flashChatModel,

            @Qualifier("flashLiteChatModel")
            GoogleGenAiChatModel flashLiteChatModel
    ) {
        this.promptService = promptService;
        this.naturalInputProcessor = naturalInputProcessor;
        this.hydrogenChatModel = hydrogenChatModel;
        this.flashChatModel = flashChatModel;
        this.flashLiteChatModel = flashLiteChatModel;
    }

    public String generateNaturalTextWithHydrogen(NaturalAuditRequest request) {
        return generateNaturalText(request, hydrogenChatModel, "hydrogen", RETRY_ATTEMPTS);
    }

    public String generateNaturalTextWithFlash(NaturalAuditRequest request) {
        return generateNaturalText(request, flashChatModel, "flash", RETRY_ATTEMPTS);
    }

    public String generateNaturalTextWithFlashLite(NaturalAuditRequest request) {
        return generateNaturalText(request, flashLiteChatModel, "flash-lite", 1);
    }

    private String generateNaturalText(
            NaturalAuditRequest request,
            GoogleGenAiChatModel chatModel,
            String modelName,
            int maxAttempts
    ) {
        NaturalInputProcessor.ProcessedNaturalInput input =
                naturalInputProcessor.process(request.entrada());

        return callModelWithRetry(
                chatModel,
                promptService.buildNaturalPrompt(input.text()),
                modelName,
                maxAttempts
        );
    }

    private String callModelWithRetry(
            GoogleGenAiChatModel chatModel,
            String prompt,
            String modelName,
            int maxAttempts
    ) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            LOGGER.info("Modelo {}: tentativa {}/{} iniciada.", modelName, attempt, maxAttempts);

            Future<String> response = modelExecutor.submit(() -> chatModel.call(prompt));

            try {
                String text = response.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (text != null && !text.isBlank()) {
                    LOGGER.info("Modelo {}: tentativa {}/{} concluída com sucesso.",
                            modelName, attempt, maxAttempts);
                    return text;
                }

                LOGGER.warn("Modelo {}: tentativa {}/{} retornou texto vazio.",
                        modelName, attempt, maxAttempts);

            } catch (TimeoutException exception) {
                LOGGER.warn("Modelo {}: tentativa {}/{} excedeu {} segundos.",
                        modelName, attempt, maxAttempts, TIMEOUT_SECONDS);

            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();

                LOGGER.warn("Modelo {}: tentativa {}/{} foi interrompida.",
                        modelName, attempt, maxAttempts);
                break;

            } catch (ExecutionException exception) {
                LOGGER.warn("Modelo {}: tentativa {}/{} falhou: {}",
                        modelName, attempt, maxAttempts, exception.getCause().getMessage());

            } finally {
                response.cancel(true);
            }
        }

        LOGGER.error("Modelo {} não respondeu após {} tentativa(s).", modelName, maxAttempts);
        return FALLBACK_MESSAGE;
    }

    @PreDestroy
    void closeExecutor() {
        modelExecutor.shutdownNow();
    }
}