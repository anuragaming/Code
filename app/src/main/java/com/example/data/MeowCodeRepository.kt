package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

class MeowCodeRepository(private val dao: MeowCodeDao) {

    val workspaceFiles: Flow<List<WorkspaceFile>> = dao.getAllWorkspaceFiles()
    val settings: Flow<List<MeowSetting>> = dao.getAllSettings()
    val remoteServers: Flow<List<RemoteServer>> = dao.getAllServers()
    val gitRepos: Flow<List<GitRepo>> = dao.getAllGitRepos()
    val extensions: Flow<List<ExtensionItem>> = dao.getAllExtensions()

    // Seeds initial assets if workspace is empty
    suspend fun seedInitialData() {
        val currentFiles = workspaceFiles.first()
        if (currentFiles.isEmpty()) {
            // Seed visual directory workspace
            val initialWorkspace = listOf(
                WorkspaceFile("index.html", "", "index.html", false, getIndexHtmlContent()),
                WorkspaceFile("style.css", "", "style.css", false, getStyleCssContent()),
                WorkspaceFile("script.js", "", "script.js", false, getScriptJsContent()),
                WorkspaceFile("api.py", "", "api.py", false, getApiPyContent()),
                WorkspaceFile("server.py", "", "server.py", false, getServerPyContent()),
                WorkspaceFile("config.json", "", "config.json", false, getConfigJsonContent()),
                WorkspaceFile("notes", "", "notes", true, ""),
                WorkspaceFile("notes/todo.txt", "notes", "todo.txt", false, getTodoTxtContent())
            )
            for (file in initialWorkspace) {
                dao.insertFile(file)
            }
        }

        val currentExtensions = extensions.first()
        if (currentExtensions.isEmpty()) {
            val defaultExtensions = listOf(
                ExtensionItem(
                    id = "theme.dracula",
                    name = "Dracula Eclipse Theme",
                    version = "v2.8.4",
                    description = "Gothic dark purple accent theme for midnight meow-coders.",
                    author = "Dracula-Org",
                    category = "Theme",
                    isInstalled = false
                ),
                ExtensionItem(
                    id = "theme.synthwave",
                    name = "Neon Synth Wave Theme",
                    version = "v1.0.0",
                    description = "Retro neon cyber colors with glowing magenta highlights.",
                    author = "CyberCat",
                    category = "Theme",
                    isInstalled = false
                ),
                ExtensionItem(
                    id = "lang.python",
                    name = "Python Intellisense Pro",
                    version = "v3.1.2",
                    description = "Real-time error analysis, auto-docstrings and syntax tools.",
                    author = "MeowSoft",
                    category = "Language",
                    isInstalled = true
                ),
                ExtensionItem(
                    id = "ai.meowmax",
                    name = "AI Meow-Coder Max",
                    version = "v2.5.0",
                    description = "Unlocks reasoning steps, cat chat avatars, and multi-file code editing.",
                    author = "AI-Cat-Lab",
                    category = "AI",
                    isInstalled = false
                ),
                ExtensionItem(
                    id = "git.lens",
                    name = "Git Lens Extra",
                    version = "v14.2.0",
                    description = "Inline git authorship line logs and branch diff comparison charts.",
                    author = "LinusMeow",
                    category = "Git",
                    isInstalled = false
                )
            )
            dao.insertExtensions(defaultExtensions)
        }
    }

    // File operations
    suspend fun saveFile(path: String, name: String, parentPath: String, isDirectory: Boolean, content: String) {
        val file = WorkspaceFile(
            path = path,
            parentPath = parentPath,
            name = name,
            isDirectory = isDirectory,
            content = content,
            lastModified = System.currentTimeMillis()
        )
        dao.insertFile(file)
    }

    suspend fun deleteFile(path: String, isDirectory: Boolean) {
        if (isDirectory) {
            dao.deleteFilesUnderFolder(path + "/")
        }
        dao.deleteFileByPath(path)
    }

    // Setting operations
    suspend fun updateSetting(key: String, value: String) {
        dao.insertSetting(MeowSetting(key, value))
    }

    suspend fun getSetting(key: String): String? {
        return dao.getSettingValue(key)
    }

    suspend fun saveAiProviderConfig(config: com.example.api.AiProviderConfig) {
        updateSetting("ai_provider_name", config.providerName)
        updateSetting("ai_protocol", config.protocol.id)
        updateSetting("ai_api_url", config.baseUrl)
        updateSetting("ai_api_key", config.apiKey)
        updateSetting("ai_model", config.modelId)
        updateSetting("ai_temperature", config.temperature.toString())
        updateSetting("ai_max_tokens", config.maxOutputTokens.toString())
        updateSetting("ai_timeout_sec", config.timeoutSeconds.toString())
        updateSetting("ai_streaming_enabled", config.streamingEnabled.toString())
    }

    suspend fun loadAiProviderConfig(): com.example.api.AiProviderConfig {
        val providerName = getSetting("ai_provider_name") ?: "MotAPI"
        val protocol = com.example.api.ApiProtocol.fromId(getSetting("ai_protocol"))
        val baseUrl = getSetting("ai_api_url")?.takeIf { it.isNotBlank() } ?: protocol.defaultBaseUrl
        val apiKey = getSetting("ai_api_key") ?: ""
        val modelId = getSetting("ai_model")?.takeIf { it.isNotBlank() && it != "gemini-3.5-flash" }
            ?: protocol.defaultModelId
        val temperature = getSetting("ai_temperature")?.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.2f
        val maxTokens = getSetting("ai_max_tokens")?.toIntOrNull()?.coerceIn(64, 32768) ?: 4096
        val timeoutSec = getSetting("ai_timeout_sec")?.toIntOrNull()?.coerceIn(5, 300) ?: 60
        val streaming = getSetting("ai_streaming_enabled")?.toBooleanStrictOrNull() ?: true
        return com.example.api.AiProviderConfig(
            providerName = providerName,
            protocol = protocol,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelId,
            temperature = temperature,
            maxOutputTokens = maxTokens,
            timeoutSeconds = timeoutSec,
            streamingEnabled = streaming
        )
    }

    // Remote Server IP
    suspend fun saveServer(server: RemoteServer) {
        dao.insertServer(server)
    }

    suspend fun deleteServer(id: Int) {
        dao.deleteServerById(id)
    }

    // Git integration
    suspend fun saveGitRepo(repo: GitRepo) {
        dao.insertGitRepo(repo)
    }

    suspend fun deleteGitRepo(id: Int) {
        dao.deleteGitRepoById(id)
    }

    // Extensions toggle
    suspend fun toggleExtension(id: String) {
        val ext = dao.getExtensionById(id)
        if (ext != null) {
            // If it is a Theme extension, make sure to uninstall other themes to prevent clash
            if (ext.category == "Theme" && !ext.isInstalled) {
                val allExts = dao.getAllExtensions().first()
                for (other in allExts) {
                    if (other.category == "Theme" && other.isInstalled) {
                        dao.insertExtension(other.copy(isInstalled = false))
                    }
                }
            }
            dao.insertExtension(ext.copy(isInstalled = !ext.isInstalled))
        }
    }

    // Initial code samples
    private fun getIndexHtmlContent(): String = """<!DOCTYPE html>
<html>
<head>
    <title>Hello Meow</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <div class="card">
        <h1>🐾 Meow Code Work Space</h1>
        <p>Welcome to Meow Code, your mobile IDE!</p>
        <button id="meow-btn">Click for Meow</button>
        <div id="output"></div>
    </div>
    <script src="script.js"></script>
</body>
</html>"""

    private fun getStyleCssContent(): String = """body {
    background-color: #1a1b26;
    color: #a9b1d6;
    font-family: sans-serif;
    display: flex;
    justify-content: center;
    align-items: center;
    height: 100vh;
}
.card {
    background-color: #24283b;
    padding: 30px;
    border-radius: 12px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.3);
    text-align: center;
}
button {
    background-color: #f7768e;
    color: white;
    border: none;
    padding: 10px 20px;
    border-radius: 6px;
    cursor: pointer;
    font-size: 16px;
}
button:hover {
    background-color: #ff9e64;
}"""

    private fun getScriptJsContent(): String = """document.getElementById("meow-btn").addEventListener("click", () => {
    const out = document.getElementById("output");
    out.innerText = "🐾 Meow! Code compiles beautifully on your device.";
    out.style.color = "#73daca";
    console.log("Button clicked!");
});"""

    private fun getApiPyContent(): String = """from flask import Flask, jsonify

app = Flask(__name__)

@app.route("/api/v1/meow")
def get_meow():
    return jsonify({
        "status": "success",
        "message": "Hello from Meow Core Server!",
        "cpu_usage": "1.2%",
        "battery": "94%"
    })

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5000)
"""

    private fun getServerPyContent(): String = """import socket

def run_server():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(("localhost", 8080))
    server.listen(5)
    print("🐾 Server listening on port 8080...")
    while True:
        client, addr = server.accept()
        print(f"Connected to {addr}")
        client.send("HTTP/1.1 200 OK\r\n\r\n🐾 Hello from Meow Socket Server!".encode())
        client.close()

if __name__ == "__main__":
    run_server()
"""

    private fun getConfigJsonContent(): String = """{
  "project_name": "MeowApp",
  "version": "1.0.0",
  "debug": true,
  "settings": {
    "tab_size": 4,
    "theme": "mocha",
    "ai_assist": "gemini-3.5-flash"
  }
}"""

    private fun getTodoTxtContent(): String = """🐾 MEOW TODO:
- Add a new API to client scripts
- Style the index page for production
- Commit final changes to Git repos
- Explore AI code optimization
"""
}
