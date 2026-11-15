package com.example.agent

import com.example.api.AiProviderConfig
import com.example.api.GeminiClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

enum class AgentExecutionState {
    IDLE,
    RUNNING,
    PAUSED,
    AWAITING_CONFIRMATION,
    COMPLETED,
    STOPPED,
    FAILED
}

data class AgentLogEntry(
    val stepNumber: Int,
    val actionType: String, // "THOUGHT", "TOOL_CALL", "FILE_EDIT", "VERIFY"
    val description: String,
    val status: String // "RUNNING", "SUCCESS", "FAILED"
)

data class PendingAgentConfirmation(
    val toolName: String,
    val targetPath: String,
    val argsJson: String,
    val description: String
)

/**
 * Autonomous AI Coding Agent that works with MotAPI (`claude-sonnet-5-5`) or any configured
 * OpenAI-compatible, Anthropic, or Gemini endpoint.
 *
 * Architecture:
 * 1. Planner: Requests a structured JSON action plan + tool calls from the configured AI model.
 * 2. Context Manager: Gathers workspace file list, active file, cursor line, and recent tool outputs.
 * 3. Tool Executor: Executes real workspace tools (`read_file`, `write_file`, `replace_in_file`, etc.).
 * 4. Verifier: Inspects modified files for delimiter/syntax diagnostics and feeds results back to the model.
 */
class MeowAiAgent(
    private val toolExecutor: WorkspaceToolExecutor,
    private val logWriter: suspend (AgentLogEntry) -> Unit,
    private val onFileModified: suspend (String) -> Unit,
    private val isPausedProvider: () -> Boolean,
    private val isStopRequestedProvider: () -> Boolean,
    private val onRequireConfirmation: suspend (PendingAgentConfirmation) -> Boolean
) {

    companion object {
        private const val MAX_AGENT_ITERATIONS = 5

        val AGENT_SYSTEM_PROMPT = """
You are the Meow Code Autonomous AI Software Engineering Agent.
You have direct access to the user's project workspace via structured tools.

Always respond with a single valid JSON object (no markdown fences outside the JSON) matching this schema:
{
  "thought": "Your concise technical analysis of the current state and what needs to be done.",
  "plan": [
    "Step 1 ...",
    "Step 2 ..."
  ],
  "tool_calls": [
    {
      "tool": "write_file",
      "args": {
        "path": "src/example.py",
        "content": "full file content here"
      }
    }
  ],
  "is_complete": true,
  "final_summary": "Clear summary of all files created/modified and verification results."
}

Available tools:
- "list_files": {} -> Lists all files in the workspace.
- "read_file": {"path": "relative/path.py"} -> Reads file content with line numbers.
- "write_file": {"path": "relative/path.py", "content": "..."} -> Creates or overwrites a file with complete working code.
- "create_file": {"path": "relative/path.py", "content": "..."} -> Creates a new file with complete working code.
- "replace_in_file": {"path": "relative/path.py", "search": "exact old snippet", "replace": "new snippet"} -> Replaces an exact snippet inside an existing file.
- "insert_into_file": {"path": "relative/path.py", "line": 10, "content": "inserted lines"} -> Inserts code at a 1-indexed line number.
- "delete_file": {"path": "relative/path.py"} -> Deletes a file (requires user confirmation).
- "rename_file": {"old_path": "old.py", "new_path": "new.py"} -> Renames/moves a file.
- "search_files": {"query": "keyword"} -> Searches across all project files.
- "create_folder": {"path": "folder/subfolder"} -> Creates a directory in the workspace.
- "get_active_editor_context": {} -> Returns the active editor file and cursor line.
- "apply_code_patch": {"path": "relative/path.py", "patch": "..."} -> Applies a search/replace patch or full file update.

Rules:
1. Never output placeholder comments like "// TODO" or truncated code. Always write complete, properly indented, production-ready code.
2. When modifying existing code or fixing a bug, read the file first if its content is not already in the context.
3. Set "is_complete": false if you need to inspect the output of "read_file" or "search_files" before writing code in the next iteration.
4. Set "is_complete": true once all requested files have been created or updated.
""".trimIndent()
    }

    suspend fun executeAutonomousRun(
        goal: String,
        config: AiProviderConfig,
        onStateChange: (AgentExecutionState) -> Unit
    ) {
        onStateChange(AgentExecutionState.RUNNING)

        logWriter(
            AgentLogEntry(
                stepNumber = 1,
                actionType = "THOUGHT",
                description = "Starting Autonomous Agent with ${config.providerName} (${config.modelId}) for goal: \"$goal\"",
                status = "SUCCESS"
            )
        )

        if (config.apiKey.isBlank()) {
            logWriter(
                AgentLogEntry(
                    stepNumber = 2,
                    actionType = "VERIFY",
                    description = "Aborted: Missing API Key for ${config.providerName}. Open AI Settings to configure your API key.",
                    status = "FAILED"
                )
            )
            onStateChange(AgentExecutionState.FAILED)
            return
        }

        val conversationHistory = mutableListOf<String>()
        val initialListing = toolExecutor.executeTool("list_files", JSONObject())
        val activeContext = toolExecutor.executeTool("get_active_editor_context", JSONObject())
        conversationHistory.add("Initial Workspace Files:\n${initialListing.output}")
        conversationHistory.add("Active Editor Context:\n${activeContext.output}")

        var stepCounter = 2
        val modifiedFiles = linkedSetOf<String>()

        for (iteration in 1..MAX_AGENT_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            waitIfPaused(onStateChange)
            if (isStopRequestedProvider()) {
                logWriter(
                    AgentLogEntry(
                        stepNumber = stepCounter++,
                        actionType = "VERIFY",
                        description = "Agent stopped by user.",
                        status = "FAILED"
                    )
                )
                onStateChange(AgentExecutionState.STOPPED)
                return
            }

            val iterationPrompt = buildString {
                appendLine("USER GOAL:")
                appendLine(goal)
                appendLine()
                appendLine("ITERATION: $iteration of $MAX_AGENT_ITERATIONS")
                appendLine("WORKSPACE CONTEXT & TOOL HISTORY:")
                conversationHistory.takeLast(8).forEach { entry ->
                    appendLine(entry)
                    appendLine("---")
                }
                appendLine("Respond ONLY with the JSON object matching the schema.")
            }

            val aiResult = GeminiClient.generateWithConfig(
                config = config,
                prompt = iterationPrompt,
                systemInstruction = AGENT_SYSTEM_PROMPT
            )

            if (aiResult.isFailure) {
                val errorMsg = aiResult.exceptionOrNull()?.message ?: "Unknown API error"
                logWriter(
                    AgentLogEntry(
                        stepNumber = stepCounter++,
                        actionType = "VERIFY",
                        description = "API Request Failed: $errorMsg",
                        status = "FAILED"
                    )
                )
                onStateChange(AgentExecutionState.FAILED)
                return
            }

            val rawResponse = aiResult.getOrThrow()
            val parsedResponse = parseAgentResponse(rawResponse)

            if (parsedResponse.thought.isNotBlank()) {
                logWriter(
                    AgentLogEntry(
                        stepNumber = stepCounter++,
                        actionType = "THOUGHT",
                        description = parsedResponse.thought,
                        status = "SUCCESS"
                    )
                )
            }

            if (parsedResponse.plan.isNotEmpty() && iteration == 1) {
                logWriter(
                    AgentLogEntry(
                        stepNumber = stepCounter++,
                        actionType = "THOUGHT",
                        description = "Execution Plan:\n" + parsedResponse.plan.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n"),
                        status = "SUCCESS"
                    )
                )
            }

            var executedAnyTool = false
            for (call in parsedResponse.toolCalls) {
                currentCoroutineContext().ensureActive()
                waitIfPaused(onStateChange)
                if (isStopRequestedProvider()) {
                    onStateChange(AgentExecutionState.STOPPED)
                    return
                }

                executedAnyTool = true
                val targetHint = call.args.optString("path", call.args.optString("file", call.args.optString("query", "")))
                logWriter(
                    AgentLogEntry(
                        stepNumber = stepCounter,
                        actionType = "TOOL_CALL",
                        description = "Running `${call.tool}`" + if (targetHint.isNotBlank()) " on `$targetHint`" else "",
                        status = "RUNNING"
                    )
                )

                var result = toolExecutor.executeTool(call.tool, call.args, confirmedDestructive = false)
                if (result.requiresUserConfirmation) {
                    onStateChange(AgentExecutionState.AWAITING_CONFIRMATION)
                    val approved = onRequireConfirmation(
                        PendingAgentConfirmation(
                            toolName = call.tool,
                            targetPath = result.targetPath,
                            argsJson = call.args.toString(),
                            description = result.output
                        )
                    )
                    onStateChange(AgentExecutionState.RUNNING)
                    result = if (approved) {
                        toolExecutor.executeTool(call.tool, call.args, confirmedDestructive = true)
                    } else {
                        ToolExecutionResult(
                            toolName = call.tool,
                            targetPath = result.targetPath,
                            success = false,
                            output = "User declined destructive action '${call.tool}' on '${result.targetPath}'."
                        )
                    }
                }

                result.modifiedFilePath?.let { path ->
                    modifiedFiles.add(path)
                    onFileModified(path)
                }

                logWriter(
                    AgentLogEntry(
                        stepNumber = stepCounter++,
                        actionType = if (result.modifiedFilePath != null) "FILE_EDIT" else "TOOL_CALL",
                        description = "[${call.tool}] ${result.output.take(400)}",
                        status = if (result.success) "SUCCESS" else "FAILED"
                    )
                )

                conversationHistory.add("Tool `${call.tool}` result (success=${result.success}):\n${result.output}")
            }

            if (parsedResponse.isComplete || (!executedAnyTool && iteration >= 1)) {
                val summaryText = parsedResponse.finalSummary.ifBlank {
                    if (modifiedFiles.isNotEmpty()) {
                        "Completed task. Modified files: ${modifiedFiles.joinToString(", ")}"
                    } else {
                        parsedResponse.thought.ifBlank { "Task completed." }
                    }
                }
                logWriter(
                    AgentLogEntry(
                        stepNumber = stepCounter++,
                        actionType = "VERIFY",
                        description = summaryText,
                        status = "SUCCESS"
                    )
                )
                onStateChange(AgentExecutionState.COMPLETED)
                return
            }
        }

        logWriter(
            AgentLogEntry(
                stepNumber = stepCounter,
                actionType = "VERIFY",
                description = "Reached maximum agent iterations ($MAX_AGENT_ITERATIONS). Modified files: ${modifiedFiles.joinToString(", ").ifBlank { "none" }}",
                status = "SUCCESS"
            )
        )
        onStateChange(AgentExecutionState.COMPLETED)
    }

    private suspend fun waitIfPaused(onStateChange: (AgentExecutionState) -> Unit) {
        while (isPausedProvider() && !isStopRequestedProvider()) {
            onStateChange(AgentExecutionState.PAUSED)
            delay(250)
        }
        if (!isStopRequestedProvider()) {
            onStateChange(AgentExecutionState.RUNNING)
        }
    }

    data class ParsedAgentTurn(
        val thought: String,
        val plan: List<String>,
        val toolCalls: List<ParsedToolCall>,
        val isComplete: Boolean,
        val finalSummary: String
    )

    data class ParsedToolCall(
        val tool: String,
        val args: JSONObject
    )

    /**
     * Parses the structured JSON response from the model, with fallback extraction if the model
     * wrapped JSON in markdown fences or returned legacy `files` arrays or `FILE:/CONTENT:` blocks.
     */
    fun parseAgentResponse(raw: String): ParsedAgentTurn {
        val cleaned = extractJsonObjectString(raw)
        if (cleaned != null) {
            try {
                val json = JSONObject(cleaned)
                val thought = json.optString("thought", "")
                val planList = mutableListOf<String>()
                val planArr = json.optJSONArray("plan") ?: json.optJSONArray("logs")
                if (planArr != null) {
                    for (i in 0 until planArr.length()) {
                        val step = planArr.optString(i)
                        if (step.isNotBlank()) planList.add(step)
                    }
                }

                val calls = mutableListOf<ParsedToolCall>()
                val callsArr = json.optJSONArray("tool_calls") ?: json.optJSONArray("actions")
                if (callsArr != null) {
                    for (i in 0 until callsArr.length()) {
                        val obj = callsArr.optJSONObject(i) ?: continue
                        val toolName = obj.optString("tool", obj.optString("action", "")).trim()
                        val argsObj = obj.optJSONObject("args") ?: obj
                        if (toolName.isNotEmpty()) {
                            calls.add(ParsedToolCall(toolName, argsObj))
                        }
                    }
                }

                // Also support legacy `files: [{name, path, content}]` array if returned
                val filesArr = json.optJSONArray("files")
                if (filesArr != null) {
                    for (i in 0 until filesArr.length()) {
                        val fileObj = filesArr.optJSONObject(i) ?: continue
                        val path = fileObj.optString("path", fileObj.optString("name", "")).trim()
                        val content = fileObj.optString("content", "")
                        if (path.isNotEmpty()) {
                            val args = JSONObject().apply {
                                put("path", path)
                                put("content", content)
                            }
                            calls.add(ParsedToolCall("write_file", args))
                        }
                    }
                }

                val isComplete = json.optBoolean("is_complete", calls.isEmpty())
                val summary = json.optString("final_summary", json.optString("summary", ""))
                return ParsedAgentTurn(
                    thought = thought,
                    plan = planList,
                    toolCalls = calls,
                    isComplete = isComplete,
                    finalSummary = summary
                )
            } catch (_: Exception) {
            }
        }

        // Fallback if model emitted legacy THOUGHT:/FILE:/CONTENT: or markdown code blocks
        val fallbackCalls = mutableListOf<ParsedToolCall>()
        val fileRegex = Regex("""FILE:\s*([^\n]+)\nCONTENT:\s*\n```[a-zA-Z]*\n([\s\S]*?)```""")
        fileRegex.findAll(raw).forEach { m ->
            val path = m.groupValues[1].trim()
            val content = m.groupValues[2].trim()
            val args = JSONObject().apply {
                put("path", path)
                put("content", content)
            }
            fallbackCalls.add(ParsedToolCall("write_file", args))
        }

        return ParsedAgentTurn(
            thought = raw.take(500),
            plan = emptyList(),
            toolCalls = fallbackCalls,
            isComplete = true,
            finalSummary = if (fallbackCalls.isNotEmpty()) "Applied ${fallbackCalls.size} file change(s)." else raw.take(500)
        )
    }

    private fun extractJsonObjectString(raw: String): String? {
        val trimmed = raw.trim()
        val fenceRegex = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""", RegexOption.IGNORE_CASE)
        fenceRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }
        return null
    }
}
