package com.locus.core.ai.toolloop

import com.locus.core.ai.tools.AppendToNoteTool
import com.locus.core.ai.tools.CreateFolderTool
import com.locus.core.ai.tools.CreateNoteTool
import com.locus.core.ai.tools.ListFoldersTool
import com.locus.core.ai.tools.MergeNotesTool
import com.locus.core.ai.tools.MoveNoteTool
import com.locus.core.ai.tools.ReadNoteTool
import com.locus.core.ai.tools.SearchNotesTool
import com.locus.core.ai.tools.SetReminderTool
import com.locus.core.ai.tools.TagNoteTool
import com.locus.core.ai.tools.TrashNoteTool
import com.locus.core.ai.tools.UpdateNoteTool
import com.locus.core.domain.agent.AgentRunCoordinator
import com.locus.core.domain.agent.CallOrigin
import com.locus.core.domain.agent.PendingToolCall
import com.locus.core.domain.agent.WriteToolName
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.ProviderRole
import com.locus.core.domain.providers.StreamEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool orchestrator (C-3, C-4, C-8): single entry point for tool invocation.
 * Registers all read and write tools, routes invocations through [AgentRunCoordinator],
 * and picks [NativeFunctionCallingBridge] vs [JsonModeToolLoop] based on
 * [com.locus.core.domain.providers.ProviderCapabilities.supportsNativeTools].
 */
@Singleton
class ToolOrchestrator(
    private val tools: List<ToolExecutor>,
    private val agentRunCoordinator: AgentRunCoordinator? = null,
    private val defaultAdapter: ProviderAdapter? = null,
    private val maxIterations: Int = 8,
) {
    @Suppress("LongParameterList")
    @Inject
    constructor(
        searchNotesTool: SearchNotesTool,
        readNoteTool: ReadNoteTool,
        listFoldersTool: ListFoldersTool,
        createNoteTool: CreateNoteTool,
        updateNoteTool: UpdateNoteTool,
        appendToNoteTool: AppendToNoteTool,
        moveNoteTool: MoveNoteTool,
        tagNoteTool: TagNoteTool,
        mergeNotesTool: MergeNotesTool,
        trashNoteTool: TrashNoteTool,
        createFolderTool: CreateFolderTool,
        setReminderTool: SetReminderTool,
        agentRunCoordinator: AgentRunCoordinator,
    ) : this(
        tools =
            listOf(
                searchNotesTool,
                readNoteTool,
                listFoldersTool,
                createNoteTool,
                updateNoteTool,
                appendToNoteTool,
                moveNoteTool,
                tagNoteTool,
                mergeNotesTool,
                trashNoteTool,
                createFolderTool,
                setReminderTool,
            ),
        agentRunCoordinator = agentRunCoordinator,
    )

    constructor(
        searchNotesTool: SearchNotesTool,
        readNoteTool: ReadNoteTool,
        listFoldersTool: ListFoldersTool,
    ) : this(
        tools = listOf(searchNotesTool, readNoteTool, listFoldersTool),
        agentRunCoordinator = null,
    )

    suspend fun run(
        userMessage: String,
        adapter: ProviderAdapter? = defaultAdapter,
        coordinator: AgentRunCoordinator? = agentRunCoordinator,
    ): String {
        val activeAdapter =
            adapter
                ?: throw IllegalArgumentException(
                    "ProviderAdapter must be provided to ToolOrchestrator",
                )

        coordinator?.resetRun()
        val coordinatedTools = coordinateTools(coordinator)

        return if (activeAdapter.capabilities.supportsNativeTools) {
            NativeFunctionCallingBridge(activeAdapter, coordinatedTools, maxIterations).run(userMessage)
        } else {
            val client = AdapterJsonModeClient(activeAdapter)
            JsonModeToolLoop(client, coordinatedTools, maxIterations = maxIterations).run(userMessage)
        }
    }

    suspend fun runWithJsonClient(
        userMessage: String,
        client: JsonModeCompletionClient,
        coordinator: AgentRunCoordinator? = agentRunCoordinator,
    ): String {
        coordinator?.resetRun()
        val coordinatedTools = coordinateTools(coordinator)
        return JsonModeToolLoop(client, coordinatedTools, maxIterations = maxIterations).run(userMessage)
    }

    private fun coordinateTools(coordinator: AgentRunCoordinator?): List<ToolExecutor> {
        val activeCoordinator = coordinator ?: return tools
        return tools.map { tool ->
            CoordinatingToolExecutor(tool, activeCoordinator)
        }
    }
}

internal class CoordinatingToolExecutor(
    private val delegate: ToolExecutor,
    private val coordinator: AgentRunCoordinator,
    private val origin: CallOrigin = CallOrigin.USER_CHAT_INSTRUCTION,
) : ToolExecutor {
    override val schema: ToolSchema get() = delegate.schema

    override suspend fun execute(argumentsJson: String): String {
        val toolName = WriteToolName.fromToolName(delegate.schema.name)
        return if (toolName != null) {
            val pendingCall =
                PendingToolCall(
                    tool = toolName,
                    origin = origin,
                    argumentsJson = argumentsJson,
                )
            coordinator.execute(pendingCall) {
                delegate.execute(argumentsJson)
            }
        } else {
            delegate.execute(argumentsJson)
        }
    }
}

internal class AdapterJsonModeClient(
    private val adapter: ProviderAdapter,
) : JsonModeCompletionClient {
    override suspend fun complete(
        systemPrompt: String,
        transcript: List<String>,
    ): String {
        val messages = mutableListOf<ProviderMessage>()
        messages += ProviderMessage(role = ProviderRole.SYSTEM, content = systemPrompt)

        for (line in transcript) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("USER: ") ->
                    messages +=
                        ProviderMessage(
                            role = ProviderRole.USER,
                            content = trimmed.removePrefix("USER: "),
                        )
                trimmed.startsWith("ASSISTANT: ") ->
                    messages +=
                        ProviderMessage(
                            role = ProviderRole.ASSISTANT,
                            content = trimmed.removePrefix("ASSISTANT: "),
                        )
                trimmed.startsWith("TOOL_RESULT(") -> {
                    val afterPrefix = trimmed.removePrefix("TOOL_RESULT(")
                    val toolName = afterPrefix.substringBefore("): ")
                    val result = afterPrefix.substringAfter("): ")
                    messages +=
                        ProviderMessage(
                            role = ProviderRole.TOOL,
                            content = result,
                            name = toolName,
                        )
                }
                trimmed.startsWith("TOOL_ERROR: ") ->
                    messages +=
                        ProviderMessage(
                            role = ProviderRole.TOOL,
                            content = """{"error":"${trimmed.removePrefix("TOOL_ERROR: ")}"}""",
                        )
            }
        }

        val collapsed = mergeConsecutiveRoles(messages)
        val responseText = StringBuilder()
        adapter.streamChat(collapsed, emptyList()).collect { event ->
            when (event) {
                is StreamEvent.TokenDelta -> responseText.append(event.text)
                is StreamEvent.Error -> throw ToolLoopException("Provider stream error: ${event.message}")
                is StreamEvent.ToolCallDelta -> {}
                is StreamEvent.Done -> {}
                is StreamEvent.Usage -> {}
            }
        }
        return responseText.toString()
    }

    private fun mergeConsecutiveRoles(messages: List<ProviderMessage>): List<ProviderMessage> {
        if (messages.isEmpty()) return emptyList()
        val merged = mutableListOf<ProviderMessage>()
        for (msg in messages) {
            val last = merged.lastOrNull()
            if (last != null && last.role == msg.role && msg.role != ProviderRole.TOOL) {
                merged[merged.lastIndex] =
                    last.copy(content = "${last.content}\n${msg.content}")
            } else {
                merged += msg
            }
        }
        return merged
    }
}
