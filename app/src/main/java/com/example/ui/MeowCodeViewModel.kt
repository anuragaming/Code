package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.api.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Stack

class MeowCodeViewModel(application: Application) : AndroidViewModel(application) {

    private val database = MeowCodeDatabase.getDatabase(application)
    private val repository = MeowCodeRepository(database.dao())

    // --- State Observables from DB ---
    val workspaceFiles: StateFlow<List<WorkspaceFile>> = repository.workspaceFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<List<MeowSetting>> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val remoteServers: StateFlow<List<RemoteServer>> = repository.remoteServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gitRepos: StateFlow<List<GitRepo>> = repository.gitRepos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val extensions: StateFlow<List<ExtensionItem>> = repository.extensions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Active UI states ---
    private val _selectedPath = MutableStateFlow<String?>(null)
    val selectedPath: StateFlow<String?> = _selectedPath.asStateFlow()

    private val _openTabs = MutableStateFlow<List<String>>(emptyList())
    val openTabs: StateFlow<List<String>> = _openTabs.asStateFlow()

    private val _currentEditorContent = MutableStateFlow("")
    val currentEditorContent: StateFlow<String> = _currentEditorContent.asStateFlow()

    private val _activePanel = MutableStateFlow("explorer") // explorer, search, git, extensions, ai, vps, settings
    val activePanel: StateFlow<String> = _activePanel.asStateFlow()

    // Editor Undo/Redo Stacks
    private val undoStack = Stack<String>()
    private val redoStack = Stack<String>()

    // Global Search states
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchReplaceText = MutableStateFlow("")
    val searchReplaceText: StateFlow<String> = _searchReplaceText.asStateFlow()

    // AI Assistant states
    private val _aiChatHistory = MutableStateFlow<List<ChatMessage>>(listOf(
        ChatMessage("assistant", "🐾 Hello! I am MeowCoder, your AI Programming companion. Ask me to write code, create scripts, review errors, or search your workspace!")
    ))
    val aiChatHistory: StateFlow<List<ChatMessage>> = _aiChatHistory.asStateFlow()

    private val _aiApiUrl = MutableStateFlow("")
    val aiApiUrl: StateFlow<String> = _aiApiUrl.asStateFlow()

    private val _aiApiKey = MutableStateFlow("")
    val aiApiKey: StateFlow<String> = _aiApiKey.asStateFlow()

    private val _aiModel = MutableStateFlow("gemini-3.5-flash")
    val aiModel: StateFlow<String> = _aiModel.asStateFlow()

    private val _aiIsGenerating = MutableStateFlow(false)
    val aiIsGenerating: StateFlow<String> = _aiIsGenerating
        .map { if (it) "Thinking... 🐾" else "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Terminals
    private val _terminalTabs = MutableStateFlow(listOf("Bash Terminal 🐾", "Python Console 🐍", "Node Runtime ⚡"))
    val terminalTabs: StateFlow<List<String>> = _terminalTabs.asStateFlow()

    private val _activeTerminalIdx = MutableStateFlow(0)
    val activeTerminalIdx: StateFlow<Int> = _activeTerminalIdx.asStateFlow()

    private val _terminalLogs = MutableStateFlow<Map<Int, List<String>>>(mapOf(
        0 to listOf("meow@root:~$ neofetch", "🐾 MEOW CODE OS v1.0.0", "Shell: MeowShell 1.0", "Type 'help' to see list of simulated dev tools!"),
        1 to listOf(">>> # Interactive Python Environment", ">>> type python code or execute api.py via Run Button!"),
        2 to listOf("> NodeJS Local v18.14.2 ready.", "> execute Javascript files synchronously.")
    ))
    val terminalLogs: StateFlow<Map<Int, List<String>>> = _terminalLogs.asStateFlow()

    // System Monitor Simulations
    private val _cpuUsage = MutableStateFlow(12)
    val cpuUsage: StateFlow<Int> = _cpuUsage.asStateFlow()

    private val _ramUsage = MutableStateFlow(42) // in %
    val ramUsage: StateFlow<Int> = _ramUsage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedInitialData()
            
            // Read saved settings
            _aiApiUrl.value = repository.getSetting("ai_api_url") ?: ""
            _aiApiKey.value = repository.getSetting("ai_api_key") ?: ""
            _aiModel.value = repository.getSetting("ai_model") ?: "gemini-3.5-flash"

            // Simulate System Stats
            launch {
                while (true) {
                    kotlinx.coroutines.delay(2000)
                    _cpuUsage.value = (8..22).random()
                    _ramUsage.value = (38..46).random()
                }
            }
        }
    }

    // --- Panel actions ---
    fun selectPanel(panel: String) {
        _activePanel.value = panel
    }

    // --- Workspace File Actions ---
    fun selectFile(path: String) {
        viewModelScope.launch {
            val file = workspaceFiles.value.find { it.path == path } ?: return@launch
            if (file.isDirectory) return@launch

            _selectedPath.value = path
            _currentEditorContent.value = file.content
            
            // Manage Tab list
            val tabs = _openTabs.value.toMutableList()
            if (!tabs.contains(path)) {
                tabs.add(path)
                _openTabs.value = tabs
            }
            
            // Clear undo stacks for this file
            undoStack.clear()
            redoStack.clear()
        }
    }

    fun closeTab(path: String) {
        val tabs = _openTabs.value.toMutableList()
        tabs.remove(path)
        _openTabs.value = tabs

        if (_selectedPath.value == path) {
            if (tabs.isNotEmpty()) {
                selectFile(tabs.last())
            } else {
                _selectedPath.value = null
                _currentEditorContent.value = ""
            }
        }
    }

    private var autoSaveJob: kotlinx.coroutines.Job? = null

    fun updateEditorContent(newContent: String) {
        val current = _currentEditorContent.value
        if (current != newContent) {
            undoStack.push(current)
            if (undoStack.size > 50) undoStack.removeAt(0)
            redoStack.clear()
            _currentEditorContent.value = newContent
            
            // Debounce DB saves to optimize CPU/RAM overhead and prevent InputDispatcher crashes
            autoSaveJob?.cancel()
            autoSaveJob = viewModelScope.launch {
                kotlinx.coroutines.delay(1000)
                val path = _selectedPath.value ?: return@launch
                val file = workspaceFiles.value.find { it.path == path } ?: return@launch
                repository.saveFile(path, file.name, file.parentPath, false, newContent)
            }
        }
    }

    fun applyUndo() {
        if (undoStack.isNotEmpty()) {
            val state = undoStack.pop()
            redoStack.push(_currentEditorContent.value)
            _currentEditorContent.value = state
            
            autoSaveJob?.cancel()
            viewModelScope.launch {
                val path = _selectedPath.value ?: return@launch
                val file = workspaceFiles.value.find { it.path == path } ?: return@launch
                repository.saveFile(path, file.name, file.parentPath, false, state)
            }
        }
    }

    fun applyRedo() {
        if (redoStack.isNotEmpty()) {
            val state = redoStack.pop()
            undoStack.push(_currentEditorContent.value)
            _currentEditorContent.value = state

            autoSaveJob?.cancel()
            viewModelScope.launch {
                val path = _selectedPath.value ?: return@launch
                val file = workspaceFiles.value.find { it.path == path } ?: return@launch
                repository.saveFile(path, file.name, file.parentPath, false, state)
            }
        }
    }

    fun createFile(name: String, isFolder: Boolean) {
        viewModelScope.launch {
            // Check nesting based on selected path or folder
            var parentPath = ""
            val selected = _selectedPath.value
            if (selected != null) {
                val slIdx = selected.lastIndexOf('/')
                if (slIdx != -1) {
                    parentPath = selected.substring(0, slIdx)
                }
            }

            val fullPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            val initialContent = if (isFolder) "" else getPlaceholderFileContent(name)
            repository.saveFile(fullPath, name, parentPath, isFolder, initialContent)
            
            if (!isFolder) {
                selectFile(fullPath)
            }
        }
    }

    fun deleteFile(path: String, isDirectory: Boolean) {
        viewModelScope.launch {
            repository.deleteFile(path, isDirectory)
            closeTab(path)
        }
    }

    private fun getPlaceholderFileContent(name: String): String {
        return when {
            name.endsWith(".py") -> "#!/usr/bin/env python\n# Python source code\n\ndef main():\n    print(\"🐾 Welcome to a new Python module!\")\n\nif __name__ == '__main__':\n    main()\n"
            name.endsWith(".js") -> "// Javascript Runtime\nconsole.log(\"⚡ Script loaded!\");\n"
            name.endsWith(".html") -> "<html>\n  <body>\n    <h1>New Page</h1>\n  </body>\n</html>"
            name.endsWith(".css") -> "/* Styling stylesheet */\nbody {\n  color: #c0caf5;\n}"
            else -> "Text file workspace content.\n"
        }
    }

    // --- Search & Replace Actions ---
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateReplaceText(text: String) {
        _searchReplaceText.value = text
    }

    fun executeGlobalReplace() {
        val query = _searchQuery.value
        val replaceWith = _searchReplaceText.value
        if (query.isEmpty()) return

        viewModelScope.launch {
            val filesList = workspaceFiles.value
            for (file in filesList) {
                if (!file.isDirectory && file.content.contains(query)) {
                    val newContent = file.content.replace(query, replaceWith)
                    repository.saveFile(file.path, file.name, file.parentPath, false, newContent)
                    if (_selectedPath.value == file.path) {
                        _currentEditorContent.value = newContent
                    }
                }
            }
        }
    }

    // --- Integrated Runtime Terminal Commands ---
    fun switchTerminalTab(index: Int) {
        _activeTerminalIdx.value = index
    }

    fun submitTerminalCommand(input: String) {
        if (input.trim().isEmpty()) return
        val currentIdx = _activeTerminalIdx.value
        val history = _terminalLogs.value[currentIdx]?.toMutableList() ?: mutableListOf()
        
        // Setup prompts
        val promptStr = when (currentIdx) {
            0 -> "meow@root:~$"
            1 -> ">>>"
            else -> ">"
        }
        history.add("$promptStr $input")

        val cmd = input.trim()
        val parts = cmd.split(" ")
        val mainCommand = parts.firstOrNull() ?: ""

        when {
            cmd == "clear" -> history.clear()
            mainCommand == "help" -> {
                history.add("🐾 Meow Code Terminal Helper:")
                history.add("  - ls              : List active workspace files")
                history.add("  - cat <file>      : Read file contents in console")
                history.add("  - python <file>   : Run simulated python compiler")
                history.add("  - node <file>     : Run simulated javascript script")
                history.add("  - meow            : Trigger system core feline greeting!")
                history.add("  - git status      : Check repository commit differences")
                history.add("  - sysinfo         : Fetch hardware and battery metrics")
            }
            mainCommand == "ls" -> {
                val files = workspaceFiles.value
                val fileNames = files.joinToString("   ") { if (it.isDirectory) "📁 ${it.name}" else "📄 ${it.name}" }
                history.add(fileNames)
            }
            mainCommand == "cat" -> {
                val target = parts.getOrNull(1)
                if (target == null) {
                    history.add("Err: Specify a file. Usage: cat index.html")
                } else {
                    val found = workspaceFiles.value.find { it.name == target && !it.isDirectory }
                    if (found == null) {
                        history.add("Err: File '$target' not found in workspace.")
                    } else {
                        history.add(found.content)
                    }
                }
            }
            mainCommand == "meow" -> {
                history.add("   /\\_/\\  ")
                history.add("  ( o.o ) ")
                history.add("   > ^ <   Meow Code Engine active!")
                history.add("🐾 App name: Meow Code IDE")
                history.add("🐾 Package: com.aistudio.meowcode.gsqptx")
            }
            mainCommand == "sysinfo" -> {
                history.add("🖥️ CPU Core Usage: ${_cpuUsage.value}%")
                history.add("💾 App JVM Heap: ${_ramUsage.value}% allocation")
                history.add("🔋 Connected Platform Type: Android API ${android.os.Build.VERSION.SDK_INT}")
            }
            mainCommand == "python" -> {
                val target = parts.getOrNull(1)
                if (target == null) {
                    history.add("Err: Python script missing. Usage: python main.py")
                } else {
                    simulateCodeExecution(target, "python", history)
                }
            }
            mainCommand == "node" -> {
                val target = parts.getOrNull(1)
                if (target == null) {
                    history.add("Err: Javascript script missing. Usage: node script.js")
                } else {
                    simulateCodeExecution(target, "node", history)
                }
            }
            mainCommand == "git" -> {
                if (parts.getOrNull(1) == "status") {
                    history.add("On branch main")
                    history.add("Your branch is up to date with 'origin/main'.")
                    val unsaved = workspaceFiles.value.filter { System.currentTimeMillis() - it.lastModified < 180000 }
                    if (unsaved.isNotEmpty()) {
                        history.add("Changes not staged for commit:")
                        for (f in unsaved) {
                            history.add("  modified:   ${f.name}")
                        }
                    } else {
                        history.add("nothing to commit, working tree clean")
                    }
                } else {
                    history.add("Git tool initialized. Commands: status, commit, branch.")
                }
            }
            else -> {
                // If it looks like raw python inside Python Console
                if (currentIdx == 1) {
                    history.add(evaluatePythonSnippet(cmd))
                } else {
                    history.add("meow: command not found: $mainCommand. Type 'help' for instructions.")
                }
            }
        }

        val updatedLogs = _terminalLogs.value.toMutableMap()
        updatedLogs[currentIdx] = history
        _terminalLogs.value = updatedLogs
    }

    private fun evaluatePythonSnippet(line: String): String {
        return try {
            if (line.contains("print")) {
                val start = line.indexOf('(')
                val end = line.lastIndexOf(')')
                if (start != -1 && end != -1) {
                    return line.substring(start + 1, end).replace("\"", "").replace("'", "")
                }
            }
            if (line.contains("=")) {
                val parts = line.split("=")
                return ">>> Variable '${parts[0].trim()}' is assigned."
            }
            "Evaluated: $line => compiled cleanly."
        } catch (e: Exception) {
            "SyntaxError: Invalid code construct."
        }
    }

    private fun simulateCodeExecution(fileName: String, type: String, logs: MutableList<String>) {
        val file = workspaceFiles.value.find { it.name == fileName && !it.isDirectory }
        if (file == null) {
            logs.add("Err: File '$fileName' not found inside active workspace.")
            return
        }

        if (type == "python") {
            logs.add("[Python Compiler API v3]")
            logs.add("Running script '$fileName' on local VM...")
            
            // Check specific outputs
            if (file.name == "api.py") {
                logs.add(" * Serving Flask app 'api' (lazy loading)")
                logs.add(" * Environment: development")
                logs.add(" * Running on http://127.0.0.1:5000/ (Press CTRL+C to quit)")
                logs.add("🐾 Simulated Flask web server started meowing on Port 5000!")
            } else {
                // Parse prints from custom python file
                val lines = file.content.split("\n")
                var printedSomething = false
                for (ln in lines) {
                    if (ln.trim().startsWith("print")) {
                        val content = ln.substringAfter("(").substringBeforeLast(")")
                            .replace("\"", "").replace("'", "").trim()
                        logs.add(content)
                        printedSomething = true
                    }
                }
                if (!printedSomething) {
                    logs.add("🐾 Script executed. Finished with exit code 0")
                }
            }
        } else if (type == "node") {
            logs.add("[NodeJS Host Executable]")
            logs.add("Evaluating script '$fileName'...")
            
            // Output custom logs
            val lines = file.content.split("\n")
            var hasLogs = false
            for (ln in lines) {
                if (ln.contains("console.log")) {
                    val content = ln.substringAfter("(").substringBeforeLast(")")
                        .replace("\"", "").replace("'", "").trim()
                    logs.add("[Log] $content")
                    hasLogs = true
                }
            }
            if (!hasLogs) {
                logs.add("⚡ Execution complete. Memory footprint is compact.")
            }
        }
    }

    // Runs current editor file
    fun runActiveEditorCode() {
        val selected = _selectedPath.value ?: return
        val filename = selected.substringAfterLast('/')
        
        // Select terminal index based on filename
        val terminalIdx = if (filename.endsWith(".py")) 1 else if (filename.endsWith(".js")) 2 else 0
        _activeTerminalIdx.value = terminalIdx
        
        val runCmd = if (filename.endsWith(".py")) "python $filename" else if (filename.endsWith(".js")) "node $filename" else "cat $filename"
        submitTerminalCommand(runCmd)
    }

    // --- AI Assistant Prompting ---
    fun updateAiSettings(url: String, key: String, model: String) {
        _aiApiUrl.value = url
        _aiApiKey.value = key
        _aiModel.value = model

        viewModelScope.launch {
            repository.updateSetting("ai_api_url", url)
            repository.updateSetting("ai_api_key", key)
            repository.updateSetting("ai_model", model)
        }
    }

    fun sendAiPrompt(prompt: String) {
        if (prompt.trim().isEmpty() || _aiIsGenerating.value) return

        val originalHistory = _aiChatHistory.value.toMutableList()
        originalHistory.add(ChatMessage("user", prompt))
        _aiChatHistory.value = originalHistory

        _aiIsGenerating.value = true

        viewModelScope.launch {
            // Gather workspace text files to supply in system instruction context
            val filesContext = withContext(Dispatchers.IO) {
                workspaceFiles.value
                    .filter { !it.isDirectory && it.content.length < 5000 }
                    .take(5)
                    .joinToString("\n\n") { "File Path: ${it.path}\nContent:\n${it.content}" }
            }

            val systemInstruction = """You are MeowCoder, a powerful and clever coding assistant integrated inside Meow Code Android IDE.
You are talkative and love cat puns. The user's active edited file is: ${_selectedPath.value ?: "None"}.
The contents of their workspace files is:
$filesContext"""

            val response = GeminiClient.generateContent(
                prompt = prompt,
                customUrl = _aiApiUrl.value,
                customKey = _aiApiKey.value,
                customModel = _aiModel.value,
                systemInstruction = systemInstruction
            )

            originalHistory.add(ChatMessage("assistant", response))
            _aiChatHistory.value = originalHistory
            _aiIsGenerating.value = false
        }
    }

    fun applyAiCodeToEditor(code: String) {
        updateEditorContent(code)
    }

    // --- Git Integration Actions ---
    fun submitGitRepositoryClone(name: String, url: String) {
        viewModelScope.launch {
            val repo = GitRepo(
                name = name,
                url = url,
                isCloned = true,
                currentBranch = "main"
            )
            repository.saveGitRepo(repo)
            
            // Seed a file corresponding to cloned repo
            val sampleFileName = "${name.lowercase()}_service.py"
            repository.saveFile(
                path = sampleFileName,
                name = sampleFileName,
                parentPath = "",
                isDirectory = false,
                content = "# Cloned module from $name repository.\n\ndef ping_remote():\n    print('🐾 Connected successfully to $name')\n"
            )
            
            _terminalLogs.value[0]?.toMutableList()?.apply {
                add("Cloning into '$name'...")
                add("remote: Enumerating objects: 12, done.")
                add("remote: Counting objects: 100% (12/12), done.")
                add("Unpacking objects: 100% (12/12), 2.14 KiB | 545.00 KiB/s, done.")
                add("🐾 Repository cloned cleanly.")
            }?.let { logs ->
                val updated = _terminalLogs.value.toMutableMap()
                updated[0] = logs
                _terminalLogs.value = updated
            }
        }
    }

    fun mergeConflictResolver(repoId: Int) {
        viewModelScope.launch {
            val repoList = gitRepos.value
            val item = repoList.find { it.id == repoId } ?: return@launch
            repository.saveGitRepo(item.copy(remoteStatus = "Clean"))
        }
    }

    fun deleteGitRepo(id: Int) {
        viewModelScope.launch {
            repository.deleteGitRepo(id)
        }
    }

    // --- VPS Terminal Actions ---
    fun submitVpsServerCreation(name: String, ip: String, user: String, portStr: String) {
        viewModelScope.launch {
            val portInt = portStr.toIntOrNull() ?: 22
            val server = RemoteServer(
                name = name,
                ipAddress = ip,
                username = user,
                port = portInt,
                status = "Connected 🟢"
            )
            repository.saveServer(server)
            
            // Toggle terminal to VPS prompt
            val bashLogs = _terminalLogs.value[0]?.toMutableList() ?: mutableListOf()
            bashLogs.add("🐾 SSH tunnel created successfully to $user@$ip:$portInt")
            bashLogs.add("meow@$name:~$ ")
            
            val updated = _terminalLogs.value.toMutableMap()
            updated[0] = bashLogs
            _terminalLogs.value = updated
        }
    }

    fun removeServer(id: Int) {
        viewModelScope.launch {
            repository.deleteServer(id)
        }
    }

    // --- Extension Toggle Actions ---
    fun toggleExtensionInstallation(id: String) {
        viewModelScope.launch {
            repository.toggleExtension(id)
        }
    }

    // --- Web Localhost Server States and Actions ---
    private var serverSocket: java.net.ServerSocket? = null
    
    private val _isWebRunning = MutableStateFlow(false)
    val isWebRunning: StateFlow<Boolean> = _isWebRunning.asStateFlow()

    private val _serverPort = MutableStateFlow(8080)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    fun toggleWebLocalhostServer() {
        if (_isWebRunning.value) {
            stopWebLocalhostServer()
        } else {
            startWebLocalhostServer()
        }
    }

    fun startWebLocalhostServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val port = 8080
                _serverPort.value = port
                val socket = java.net.ServerSocket(port)
                serverSocket = socket
                _isWebRunning.value = true
                
                withContext(Dispatchers.Main) {
                    val currentIdx = _activeTerminalIdx.value
                    _terminalLogs.value[currentIdx]?.toMutableList()?.apply {
                        add("🌐 [Localhost Server] Started listening on http://localhost:$port")
                        add("🌐 Open Web Preview tab to run index.html!")
                    }?.let { logs ->
                        val updated = _terminalLogs.value.toMutableMap()
                        updated[currentIdx] = logs
                        _terminalLogs.value = updated
                    }
                }

                while (_isWebRunning.value) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    launch(Dispatchers.IO) {
                        try {
                            val reader = java.io.BufferedReader(java.io.InputStreamReader(client.getInputStream()))
                            val firstLine = reader.readLine() ?: ""
                            if (firstLine.isEmpty()) {
                                client.close()
                                return@launch
                            }
                            
                            val parts = firstLine.split(" ")
                            if (parts.size < 2) {
                                client.close()
                                return@launch
                            }
                            
                            var reqPath = parts[1]
                            val qIdx = reqPath.indexOf('?')
                            if (qIdx != -1) {
                                reqPath = reqPath.substring(0, qIdx)
                            }
                            
                            if (reqPath == "/" || reqPath.isEmpty()) {
                                reqPath = "index.html"
                            }
                            if (reqPath.startsWith("/")) {
                                reqPath = reqPath.substring(1)
                            }
                            
                            val filesList = workspaceFiles.value
                            val fileItem = filesList.find { it.path == reqPath }
                                ?: filesList.find { it.name == reqPath }
                                
                            val responseBytes: ByteArray
                            val mimeType: String
                            val statusLine: String
                            
                            if (fileItem != null && !fileItem.isDirectory) {
                                statusLine = "HTTP/1.1 200 OK"
                                responseBytes = fileItem.content.toByteArray()
                                mimeType = when {
                                    reqPath.endsWith(".html") -> "text/html; charset=utf-8"
                                    reqPath.endsWith(".css") -> "text/css; charset=utf-8"
                                    reqPath.endsWith(".js") -> "application/javascript; charset=utf-8"
                                    reqPath.endsWith(".json") -> "application/json; charset=utf-8"
                                    else -> "text/plain; charset=utf-8"
                                }
                            } else {
                                statusLine = "HTTP/1.1 404 Not Found"
                                responseBytes = "<h1>404 Not Found 🐾</h1><p>File '$reqPath' not found in Meow Code workspace.</p>".toByteArray()
                                mimeType = "text/html; charset=utf-8"
                            }
                            
                            val out = client.getOutputStream()
                            out.write("$statusLine\r\n".toByteArray())
                            out.write("Content-Type: $mimeType\r\n".toByteArray())
                            out.write("Content-Length: ${responseBytes.size}\r\n".toByteArray())
                            out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                            out.write("Connection: close\r\n\r\n".toByteArray())
                            out.write(responseBytes)
                            out.flush()
                            client.close()
                        } catch (e: Exception) {
                            try { client.close() } catch (ex: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isWebRunning.value = false
                    val currentIdx = _activeTerminalIdx.value
                    _terminalLogs.value[currentIdx]?.toMutableList()?.apply {
                        add("Err: Web Server failed: ${e.message}")
                    }?.let { logs ->
                        val updated = _terminalLogs.value.toMutableMap()
                        updated[currentIdx] = logs
                        _terminalLogs.value = updated
                    }
                }
            }
        }
    }

    fun stopWebLocalhostServer() {
        _isWebRunning.value = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val currentIdx = _activeTerminalIdx.value
        _terminalLogs.value[currentIdx]?.toMutableList()?.apply {
            add("🌐 [Localhost Server] Web server stopped.")
        }?.let { logs ->
            val updated = _terminalLogs.value.toMutableMap()
            updated[currentIdx] = logs
            _terminalLogs.value = updated
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopWebLocalhostServer()
    }
}

// Simple Helper models
data class ChatMessage(
    val sender: String, // "user", "assistant"
    val content: String
)
