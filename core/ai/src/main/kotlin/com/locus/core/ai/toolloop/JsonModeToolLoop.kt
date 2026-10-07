package com.locus.core.ai.toolloop

import com.locus.core.domain.agent.BulkCapExceededException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

@Serializable
data class ToolCallEnvelope(
    val thought: String? = null,
    val toolCall: ToolCallBody? = null,
    val finalAnswer: String? = null,
)

@Serializable
data class ToolCallBody(
    val name: String,
    val argumentsJson: String,
)

interface ToolExecutor {
    val schema: ToolSchema

    suspend fun execute(argumentsJson: String): String
}

interface JsonModeCompletionClient {
    suspend fun complete(
        systemPrompt: String,
        transcript: List<String>,
    ): String
}

class ToolLoopException(
    message: String,
) : Exception(message)

/**
 * JSON-mode tool loop (C-8): drives providers that lack native function calling -- including local
 * models -- through a manual request/parse/execute/append cycle. Every model turn must be a single
 * JSON object matching [ToolCallEnvelope]; the loop appends the raw model text and the raw tool
 * result to the transcript verbatim, and stops at [maxIterations] to guarantee termination.
 */
@Suppress("ThrowsCount", "MaxLineLength", "TooGenericExceptionCaught")
class JsonModeToolLoop(
    private val client: JsonModeCompletionClient,
    private val tools: List<ToolExecutor>,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxIterations: Int = 8,
) {
    suspend fun run(userMessage: String): String {
        val toolsById = tools.associateBy { it.schema.name }
        val systemPrompt = buildSystemPrompt(tools.map { it.schema })
        val transcript = mutableListOf("USER: $userMessage")

        repeat(maxIterations) {
            val raw = client.complete(systemPrompt, transcript)
            transcript += "ASSISTANT: $raw"
            val envelope =
                parseEnvelope(raw)
                    ?: throw ToolLoopException(
                        "model turn was not valid JSON per the required schema: $raw",
                    )

            envelope.finalAnswer?.let {
                return it
            }
            val call =
                envelope.toolCall ?: throw ToolLoopException("turn had neither toolCall nor finalAnswer")
            val tool = toolsById[call.name]
            if (tool == null) {
                transcript += "TOOL_ERROR: unknown tool '${call.name}'"
                return@repeat
            }
            try {
                val result = tool.execute(call.argumentsJson)
                transcript += "TOOL_RESULT(${call.name}): $result"
            } catch (e: BulkCapExceededException) {
                transcript += "TOOL_ERROR: ${e.message}"
            } catch (e: Exception) {
                val result =
                    """{"error":"${(e.message ?: "tool failed").replace("\"", "'")}"}"""
                transcript += "TOOL_RESULT(${call.name}): $result"
            }
        }
        throw ToolLoopException("exceeded $maxIterations iterations without a final answer")
    }

    private fun parseEnvelope(raw: String): ToolCallEnvelope? =
        runCatching { json.decodeFromString(ToolCallEnvelope.serializer(), raw.trim()) }
            .getOrNull()

    private fun buildSystemPrompt(schemas: List<ToolSchema>): String =
        buildString {
            appendLine("You must respond with exactly one JSON object per turn, matching this shape:")
            appendLine(
                """{"thought": string?, "toolCall": {"name": string, "argumentsJson": string}?, "finalAnswer": string?}""",
            )
            appendLine("Set exactly one of toolCall or finalAnswer. Available tools:")
            schemas.forEach {
                appendLine("- ${it.name}: ${it.description} | args schema: ${it.parametersJsonSchema}")
            }
        }
}
