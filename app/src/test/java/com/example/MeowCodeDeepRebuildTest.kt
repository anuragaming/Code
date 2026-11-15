package com.example

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.agent.MeowAiAgent
import com.example.agent.WorkspaceToolExecutor
import com.example.api.AiProviderConfig
import com.example.api.ApiProtocol
import com.example.api.GeminiClient
import com.example.data.MeowCodeDao
import com.example.data.MeowCodeRepository
import com.example.data.WorkspaceFile
import com.example.editor.EditorController
import com.example.editor.EditorLanguage
import com.example.editor.IndentationEngine
import com.example.editor.LanguageRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MeowCodeDeepRebuildTest {

    // --- PART 1: MULTI-LANGUAGE CODE EDITOR & INDENTATION TESTS ---

    @Test
    fun pythonEnterAfterDefIndentsFourSpaces() {
        val input = "def hello():"
        val value = TextFieldValue(input, TextRange(input.length))
        val result = IndentationEngine.handleEnter(value, EditorLanguage.PYTHON)

        assertEquals("def hello():\n    ", result.text)
        assertEquals(TextRange("def hello():\n    ".length), result.selection)
    }

    @Test
    fun pythonEnterPreservesNestedBlockAndDedentsAfterReturn() {
        val code = "if x > 10:\n    print(x)"
        val value = TextFieldValue(code, TextRange(code.length))
        val afterPrint = IndentationEngine.handleEnter(value, EditorLanguage.PYTHON)
        assertEquals("if x > 10:\n    print(x)\n    ", afterPrint.text)

        val returnCode = "def calc():\n    return 42"
        val retVal = TextFieldValue(returnCode, TextRange(returnCode.length))
        val afterReturn = IndentationEngine.handleEnter(retVal, EditorLanguage.PYTHON)
        assertEquals("def calc():\n    return 42\n", afterReturn.text)
    }

    @Test
    fun pythonMiddleKeywordElifDedentsWhenTypingColon() {
        val before = "if a:\n    x = 1\n    elif b"
        val oldVal = TextFieldValue(before, TextRange(before.length))
        val typed = IndentationEngine.handleCharacterTyped(oldVal, ':', EditorLanguage.PYTHON)
        assertEquals("if a:\n    x = 1\nelif b:", typed.text)
    }

    @Test
    fun htmlEnterAfterOpenTagCreatesClosingTagAndIndents() {
        val input = "<div>"
        val value = TextFieldValue(input, TextRange(input.length))
        val result = IndentationEngine.handleEnter(value, EditorLanguage.HTML)

        assertEquals("<div>\n  \n</div>", result.text)
        assertEquals(TextRange("<div>\n  ".length), result.selection)
    }

    @Test
    fun htmlEnterBetweenTagsSplitsCleanlyWithoutDuplicateClosingTag() {
        val input = "<div></div>"
        val cursorBetween = "<div>".length
        val value = TextFieldValue(input, TextRange(cursorBetween))
        val result = IndentationEngine.handleEnter(value, EditorLanguage.HTML)

        assertEquals("<div>\n  \n</div>", result.text)
        assertEquals(TextRange("<div>\n  ".length), result.selection)
    }

    @Test
    fun htmlVoidTagDoesNotGenerateClosingTag() {
        val input = "<br>"
        val value = TextFieldValue(input, TextRange(input.length))
        val result = IndentationEngine.handleEnter(value, EditorLanguage.HTML)

        assertEquals("<br>\n", result.text)
    }

    @Test
    fun javascriptEnterBetweenBracesExpandsWithProperCursor() {
        val input = "function hello() {}"
        val cursorBetween = "function hello() {".length
        val value = TextFieldValue(input, TextRange(cursorBetween))
        val result = IndentationEngine.handleEnter(value, EditorLanguage.JAVASCRIPT)

        assertEquals("function hello() {\n  \n}", result.text)
        assertEquals(TextRange("function hello() {\n  ".length), result.selection)
    }

    @Test
    fun tabAndShiftTabIndentAndDedentMultiLineSelection() {
        val code = "line1()\nline2()"
        val valSel = TextFieldValue(code, TextRange(0, code.length))
        val indented = IndentationEngine.handleTab(valSel, EditorLanguage.PYTHON)
        assertEquals("    line1()\n    line2()", indented.text)

        val dedented = IndentationEngine.handleShiftTab(indented, EditorLanguage.PYTHON)
        assertEquals("line1()\nline2()", dedented.text)
    }

    @Test
    fun autoClosingPairsAndSelectionWrappingWork() {
        val raw = TextFieldValue("hello", TextRange(0, 5))
        val wrapped = IndentationEngine.handleCharacterTyped(raw, '"', EditorLanguage.PYTHON)
        assertEquals("\"hello\"", wrapped.text)
        assertEquals(TextRange(1, 6), wrapped.selection)

        val emptyPair = TextFieldValue("()", TextRange(1))
        val backspaced = IndentationEngine.handleBackspace(emptyPair, EditorLanguage.JAVASCRIPT)
        assertEquals("", backspaced.text)
        assertEquals(TextRange(0), backspaced.selection)
    }

    @Test
    fun undoAndRedoPreserveCursorSelection() {
        val controller = EditorController()
        val s1 = TextFieldValue("def a():", TextRange(8))
        controller.resetHistory(s1)

        val s2 = controller.performEnter(s1, EditorLanguage.PYTHON)
        assertEquals("def a():\n    ", s2.text)

        val undone = controller.undo(s2)
        assertNotNull(undone)
        assertEquals("def a():", undone!!.text)
        assertEquals(TextRange(8), undone.selection)

        val redone = controller.redo()
        assertNotNull(redone)
        assertEquals("def a():\n    ", redone!!.text)
        assertEquals(TextRange("def a():\n    ".length), redone.selection)
    }

    @Test
    fun languageRegistryDetectsExtensionsCorrectly() {
        assertEquals(EditorLanguage.PYTHON, LanguageRegistry.detectLanguage("app/api.py"))
        assertEquals(EditorLanguage.HTML, LanguageRegistry.detectLanguage("index.html"))
        assertEquals(EditorLanguage.JAVASCRIPT, LanguageRegistry.detectLanguage("main.js"))
        assertEquals(EditorLanguage.TYPESCRIPT, LanguageRegistry.detectLanguage("view.tsx"))
        assertEquals(EditorLanguage.CSS, LanguageRegistry.detectLanguage("style.css"))
        assertEquals(EditorLanguage.KOTLIN, LanguageRegistry.detectLanguage("Main.kt"))
        assertEquals(EditorLanguage.JSON, LanguageRegistry.detectLanguage("config.json"))
    }

    // --- PART 2: CUSTOM AI API & MOTAPI PROTOCOL TESTS ---

    @Test
    fun motApiUrlNormalizationNeverDuplicatesV1() {
        val cfgNoV1 = AiProviderConfig.motApiPreset("sk-test").copy(baseUrl = "https://motapis.com")
        val cfgWithV1 = AiProviderConfig.motApiPreset("sk-test").copy(baseUrl = "https://motapis.com/v1")
        val cfgFull = AiProviderConfig.motApiPreset("sk-test").copy(baseUrl = "https://motapis.com/v1/chat/completions")

        val expectedChat = "https://motapis.com/v1/chat/completions"
        assertEquals(expectedChat, GeminiClient.buildChatEndpointUrl(cfgNoV1))
        assertEquals(expectedChat, GeminiClient.buildChatEndpointUrl(cfgWithV1))
        assertEquals(expectedChat, GeminiClient.buildChatEndpointUrl(cfgFull))

        val expectedModels = "https://motapis.com/v1/models"
        assertEquals(expectedModels, GeminiClient.buildModelsEndpointUrl(cfgNoV1))
        assertEquals(expectedModels, GeminiClient.buildModelsEndpointUrl(cfgWithV1))
        assertEquals(expectedModels, GeminiClient.buildModelsEndpointUrl(cfgFull))
    }

    @Test
    fun payloadBuilderUsesCorrectSchemaPerProtocol() {
        val openAiCfg = AiProviderConfig.motApiPreset("sk-mot")
        val openAiJson = JSONObject(
            GeminiClient.buildRequestPayload(openAiCfg, "Hello", "Be concise", stream = true)
        )
        assertEquals("claude-sonnet-5-5", openAiJson.getString("model"))
        assertEquals(true, openAiJson.optBoolean("stream"))
        assertEquals(2, openAiJson.getJSONArray("messages").length())

        val anthropicCfg = AiProviderConfig.anthropicPreset("sk-ant")
        val antJson = JSONObject(
            GeminiClient.buildRequestPayload(anthropicCfg, "Hello", "System text", stream = false)
        )
        assertEquals("System text", antJson.getString("system"))
        assertEquals(1, antJson.getJSONArray("messages").length())

        val geminiCfg = AiProviderConfig.geminiPreset("AIza-test")
        val gemJson = JSONObject(
            GeminiClient.buildRequestPayload(geminiCfg, "Hello", "Sys", stream = false)
        )
        assertTrue(gemJson.has("contents"))
        assertTrue(gemJson.has("generationConfig"))
    }

    @Test
    fun responseParsersHandleOpenAiAnthropicGeminiAndSseChunks() {
        val openAiResp = """{"choices":[{"message":{"content":"Hello from MotAPI!"}}]}"""
        assertEquals(
            "Hello from MotAPI!",
            GeminiClient.parseNonStreamingResponse(openAiResp, ApiProtocol.OPENAI_COMPATIBLE).getOrNull()
        )

        val antResp = """{"content":[{"type":"text","text":"Part A "},{"type":"text","text":"Part B"}]}"""
        assertEquals(
            "Part A Part B",
            GeminiClient.parseNonStreamingResponse(antResp, ApiProtocol.ANTHROPIC_MESSAGES).getOrNull()
        )

        val sseLine = """{"choices":[{"delta":{"content":"stream_token"}}]}"""
        assertEquals("stream_token", GeminiClient.extractSseDeltaChunk(sseLine, ApiProtocol.OPENAI_COMPATIBLE))

        val modelsJson = """{"data":[{"id":"gpt-4o"},{"id":"claude-sonnet-5-5"}]}"""
        val models = GeminiClient.parseModelsList(modelsJson)
        assertTrue(models.contains("claude-sonnet-5-5"))
    }

    @Test
    fun formatHttpErrorReportsClearDiagnosticsWithoutFallback() {
        val msg401 = GeminiClient.formatHttpError(401, """{"error":{"message":"Invalid key"}}""", "MotAPI", "claude-sonnet-5-5")
        assertTrue(msg401.contains("401"))
        assertTrue(msg401.contains("Unauthorized", ignoreCase = true))
        assertTrue(msg401.contains("MotAPI"))

        val msg404 = GeminiClient.formatHttpError(404, """{"error":{"message":"Model not found"}}""", "MotAPI", "claude-sonnet-5-5")
        assertTrue(msg404.contains("404"))
        assertTrue(msg404.contains("claude-sonnet-5-5"))
    }

    // --- PART 3: AUTONOMOUS AGENT & SANDBOXED WORKSPACE TOOL TESTS ---

    @Test
    fun workspaceToolExecutorSandboxesPathsAndExecutesTools() = runBlocking {
        val fakeDao = InMemoryMeowDao()
        val repo = MeowCodeRepository(fakeDao)
        repo.saveFile("api.py", "api.py", "", false, "def hi():\n    print('meow')\n")
        val tempDir = createTempDir(prefix = "meow_workspace_test")

        try {
            val executor = WorkspaceToolExecutor(
                repository = repo,
                workspaceRootDir = tempDir,
                filesSnapshotProvider = { fakeDao.files.value },
                activeFileProvider = { fakeDao.files.value.firstOrNull() },
                activeCursorLineProvider = { 1 }
            )

            // Reject path traversal
            val traversal = executor.executeTool("read_file", JSONObject().put("path", "../../etc/passwd"))
            assertFalse(traversal.success)
            assertTrue(traversal.output.contains("Path traversal", ignoreCase = true))

            // Write and verify a new file
            val writeRes = executor.executeTool(
                "write_file",
                JSONObject().put("path", "src/utils.py").put("content", "def add(a, b):\n    return a + b\n")
            )
            assertTrue(writeRes.success)
            assertEquals("src/utils.py", writeRes.modifiedFilePath)

            // Replace snippet
            val replaceRes = executor.executeTool(
                "replace_in_file",
                JSONObject()
                    .put("path", "src/utils.py")
                    .put("search", "return a + b")
                    .put("replace", "return int(a) + int(b)")
            )
            assertTrue(replaceRes.success)
            val updated = fakeDao.files.value.first { it.path == "src/utils.py" }.content
            assertTrue(updated.contains("return int(a) + int(b)"))

            // Destructive delete requires user confirmation unless confirmed=true
            val unconfirmedDel = executor.executeTool("delete_file", JSONObject().put("path", "src/utils.py"), confirmedDestructive = false)
            assertTrue(unconfirmedDel.requiresUserConfirmation)
            assertFalse(unconfirmedDel.success)

            val confirmedDel = executor.executeTool("delete_file", JSONObject().put("path", "src/utils.py"), confirmedDestructive = true)
            assertTrue(confirmedDel.success)
            assertNull(fakeDao.files.value.firstOrNull { it.path == "src/utils.py" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun agentResponseParserParsesJsonToolCalls() {
        val fakeDao = InMemoryMeowDao()
        val repo = MeowCodeRepository(fakeDao)
        val tempDir = createTempDir(prefix = "meow_agent_parse")
        try {
            val executor = WorkspaceToolExecutor(repo, tempDir, { fakeDao.files.value }, { null }, { 1 })
            val agent = MeowAiAgent(executor, {}, {}, { false }, { false }, { false })

            val rawModelOutput = """
                ```json
                {
                  "thought": "Creating hello.py",
                  "plan": ["Write hello.py"],
                  "tool_calls": [
                    {
                      "tool": "write_file",
                      "args": {
                        "path": "hello.py",
                        "content": "print('hello')"
                      }
                    }
                  ],
                  "is_complete": true,
                  "final_summary": "Created hello.py"
                }
                ```
            """.trimIndent()

            val parsed = agent.parseAgentResponse(rawModelOutput)
            assertEquals("Creating hello.py", parsed.thought)
            assertEquals(1, parsed.toolCalls.size)
            assertEquals("write_file", parsed.toolCalls[0].tool)
            assertEquals("hello.py", parsed.toolCalls[0].args.getString("path"))
            assertTrue(parsed.isComplete)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

private class InMemoryMeowDao : MeowCodeDao {
    val files = MutableStateFlow<List<WorkspaceFile>>(emptyList())
    private val settings = MutableStateFlow<List<com.example.data.MeowSetting>>(emptyList())
    private val servers = MutableStateFlow<List<com.example.data.RemoteServer>>(emptyList())
    private val repos = MutableStateFlow<List<com.example.data.GitRepo>>(emptyList())
    private val exts = MutableStateFlow<List<com.example.data.ExtensionItem>>(emptyList())

    override fun getAllWorkspaceFiles(): Flow<List<WorkspaceFile>> = files
    override suspend fun insertFile(file: WorkspaceFile) {
        files.value = files.value.filterNot { it.path == file.path } + file
    }
    override suspend fun deleteFileByPath(path: String) {
        files.value = files.value.filterNot { it.path == path }
    }
    override suspend fun deleteFilesUnderFolder(folderPrefix: String) {
        files.value = files.value.filterNot { it.path.startsWith(folderPrefix) }
    }

    override fun getAllSettings(): Flow<List<com.example.data.MeowSetting>> = settings
    override suspend fun insertSetting(setting: com.example.data.MeowSetting) {
        settings.value = settings.value.filterNot { it.key == setting.key } + setting
    }
    override suspend fun getSettingValue(key: String): String? = settings.value.firstOrNull { it.key == key }?.value

    override fun getAllServers(): Flow<List<com.example.data.RemoteServer>> = servers
    override suspend fun insertServer(server: com.example.data.RemoteServer) {
        servers.value = servers.value + server
    }
    override suspend fun deleteServerById(id: Int) {
        servers.value = servers.value.filterNot { it.id == id }
    }

    override fun getAllGitRepos(): Flow<List<com.example.data.GitRepo>> = repos
    override suspend fun insertGitRepo(repo: com.example.data.GitRepo) {
        repos.value = repos.value + repo
    }
    override suspend fun deleteGitRepoById(id: Int) {
        repos.value = repos.value.filterNot { it.id == id }
    }

    override fun getAllExtensions(): Flow<List<com.example.data.ExtensionItem>> = exts
    override suspend fun getExtensionById(id: String): com.example.data.ExtensionItem? = exts.value.firstOrNull { it.id == id }
    override suspend fun insertExtension(extension: com.example.data.ExtensionItem) {
        exts.value = exts.value.filterNot { it.id == extension.id } + extension
    }
    override suspend fun insertExtensions(extensions: List<com.example.data.ExtensionItem>) {
        exts.value = extensions
    }
}
