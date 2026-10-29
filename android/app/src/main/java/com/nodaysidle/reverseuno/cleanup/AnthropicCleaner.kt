package com.nodaysidle.reverseuno.cleanup

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import kotlin.jvm.optionals.getOrNull

/** Messages API call for the formatter, matching completeWithAnthropic in src/main/format.ts. */
class AnthropicCleaner(private val apiKey: String, private val timeoutMs: Long = 8000) {

    // Thinking stays on at low effort: with it off, Haiku answered short dictations as a chatbot;
    // at the default (medium) it overran the time budget.
    suspend fun complete(model: String, system: String, raw: String): String = withClient { client ->
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096)
            .system(system)
            .addUserMessage(raw)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()
        val message = client.messages().create(params)
        check(message.stopReason().getOrNull() != StopReason.REFUSAL) { "format refused by the model" }
        message.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("")
    }

    /** true = key accepted, false = key rejected; throws on network or server errors. */
    suspend fun checkKey(): Boolean = withClient { client ->
        try {
            client.models().list()
            true
        } catch (e: UnauthorizedException) {
            false
        } catch (e: PermissionDeniedException) {
            false
        }
    }

    private suspend fun <T> withClient(block: (AnthropicClient) -> T): T = withContext(Dispatchers.IO) {
        // No retries: a retry cannot finish inside the dictation's time budget.
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofMillis(timeoutMs))
            .maxRetries(0)
            .build()
        try {
            block(client)
        } finally {
            client.close()
        }
    }
}
