package com.example.agent

import com.example.data.MeowCodeRepository
import com.example.data.WorkspaceFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ToolExecutionResult(
    val toolName: String,
    val targetPath: String = "",
    val success: Boolean,
    val output: String,
    val requiresUserConfirmation: Boolean = false,
    val modifiedFilePath: String? = null
)

/**
 * Sandboxed Workspace Tool Executor for the Meow Code Autonomous Agent.
 * Synchronizes all changes between the Room database ([MeowCodeRepository]) and
 * the sandboxed physical workspace directory (`filesDir/workspace`) while preventing
 * path traversal (`..` or absolute paths outside the workspace).
 */
class WorkspaceToolExecutor(
    private val repository: MeowCodeRepository,
    private val workspaceRootDir: File,
    private val filesSnapshotProvider: () -> List<WorkspaceFile>,
    private val activeFileProvider: () -> WorkspaceFile?,
    private val activeCursorLineProvider: () -> Int
) {

    init {
        if (!workspaceRootDir.exists()) {
            workspaceRootDir.mkdirs()
        }
    }

    /**
     * Sanitizes and validates a relative workspace path, rejecting any path traversal attempt.
     */
    fun sanitizeRelativePath(rawPath: String): Result<String> {
        val trimmed = rawPath.trim().replace('\\', '/').trimStart('/')
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("File path cannot be empty."))
        }
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == ".." || it == "." }) {
            return Result.failure(
                SecurityException("Path traversal rejected: '$rawPath' attempts to access outside the project workspace.")
            )
        }
        val normalized = segments.joinToString("/")
        val targetFile = File(workspaceRootDir, normalized)
        val rootCanonical = workspaceRootDir.canonicalPath
        val targetCanonical = targetFile.canonicalPath
        if (!targetCanonical.startsWith(rootCanonical)) {
            return Result.failure(
                SecurityException("Path '$rawPath' resolves outside the workspace sandbox.")
            )
        }
        return Result.success(normalized)
    }

    suspend fun syncDatabaseToWorkspaceDisk() = withContext(Dispatchers.IO) {
        val files = filesSnapshotProvider()
        for (entity in files) {
            val cleanResult = sanitizeRelativePath(entity.path)
            if (cleanResult.isSuccess) {
                val diskFile = File(workspaceRootDir, cleanResult.getOrThrow())
                if (entity.isDirectory) {
                    diskFile.mkdirs()
                } else {
                    diskFile.parentFile?.mkdirs()
                    if (!diskFile.exists() || diskFile.readText() != entity.content) {
                        diskFile.writeText(entity.content)
                    }
                }
            }
        }
    }

    suspend fun executeTool(
        toolName: String,
        args: JSONObject,
        confirmedDestructive: Boolean = false
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        syncDatabaseToWorkspaceDisk()

        when (toolName.lowercase().trim()) {
            "list_files", "get_project_structure" -> listFilesTool()
            "read_file" -> readFileTool(args.optString("path", args.optString("file", "")))
            "write_file" -> writeFileTool(
                rawPath = args.optString("path", args.optString("file", "")),
                content = args.optString("content", "")
            )
            "create_file" -> createFileTool(
                rawPath = args.optString("path", args.optString("file", "")),
                content = args.optString("content", "")
            )
            "edit_file", "replace_in_file" -> replaceInFileTool(
                rawPath = args.optString("path", args.optString("file", "")),
                search = args.optString("search", args.optString("old_text", args.optString("target", ""))),
                replace = args.optString("replace", args.optString("new_text", args.optString("replacement", "")))
            )
            "insert_into_file" -> insertIntoFileTool(
                rawPath = args.optString("path", args.optString("file", "")),
                line = args.optInt("line", 1),
                content = args.optString("content", "")
            )
            "delete_file" -> deleteFileTool(
                rawPath = args.optString("path", args.optString("file", "")),
                confirmed = confirmedDestructive
            )
            "rename_file" -> renameFileTool(
                oldRawPath = args.optString("old_path", args.optString("path", "")),
                newRawPath = args.optString("new_path", "")
            )
            "search_files" -> searchFilesTool(
                query = args.optString("query", args.optString("pattern", ""))
            )
            "create_folder" -> createFolderTool(
                rawPath = args.optString("path", args.optString("folder", ""))
            )
            "get_active_editor_context" -> getActiveEditorContextTool()
            "apply_code_patch" -> applyCodePatchTool(
                rawPath = args.optString("path", args.optString("file", "")),
                patch = args.optString("patch", args.optString("content", ""))
            )
            else -> ToolExecutionResult(
                toolName = toolName,
                success = false,
                output = "Unknown tool '$toolName'. Supported tools: list_files, read_file, write_file, create_file, edit_file, replace_in_file, insert_into_file, delete_file, rename_file, search_files, create_folder, get_project_structure, get_active_editor_context, apply_code_patch."
            )
        }
    }

    private fun listFilesTool(): ToolExecutionResult {
        val files = filesSnapshotProvider().sortedBy { it.path }
        if (files.isEmpty()) {
            return ToolExecutionResult(
                toolName = "list_files",
                success = true,
                output = "Workspace is currently empty (0 files)."
            )
        }
        val summary = files.joinToString("\n") { f ->
            if (f.isDirectory) {
                "- [DIR] ${f.path}/"
            } else {
                val lines = f.content.count { it == '\n' } + 1
                val lang = detectLanguageCode(f.name)
                "- ${f.path} ($lang, $lines lines, ${f.content.length} chars)"
            }
        }
        return ToolExecutionResult(
            toolName = "list_files",
            success = true,
            output = "Project files (${files.size}):\n$summary"
        )
    }

    private fun readFileTool(rawPath: String): ToolExecutionResult {
        val pathResult = sanitizeRelativePath(rawPath)
        if (pathResult.isFailure) {
            return ToolExecutionResult("read_file", rawPath, false, pathResult.exceptionOrNull()?.message ?: "Invalid path")
        }
        val cleanPath = pathResult.getOrThrow()
        val match = filesSnapshotProvider().firstOrNull {
            !it.isDirectory && (it.path.equals(cleanPath, ignoreCase = true) || it.name.equals(cleanPath, ignoreCase = true))
        }
        if (match == null) {
            return ToolExecutionResult(
                toolName = "read_file",
                targetPath = cleanPath,
                success = false,
                output = "File '$cleanPath' not found in workspace."
            )
        }
        val numbered = match.content.lines().mapIndexed { idx, line ->
            "${idx + 1}: $line"
        }.joinToString("\n")
        return ToolExecutionResult(
            toolName = "read_file",
            targetPath = match.path,
            success = true,
            output = "File: ${match.path} (${detectLanguageCode(match.name)})\n$numbered"
        )
    }

    private suspend fun writeFileTool(rawPath: String, content: String): ToolExecutionResult {
        val pathResult = sanitizeRelativePath(rawPath)
        if (pathResult.isFailure) {
            return ToolExecutionResult("write_file", rawPath, false, pathResult.exceptionOrNull()?.message ?: "Invalid path")
        }
        val cleanPath = pathResult.getOrThrow()
        val fileName = cleanPath.substringAfterLast('/')
        val parentPath = cleanPath.substringBeforeLast('/', "")

        val existing = filesSnapshotProvider().firstOrNull {
            !it.isDirectory && it.path.equals(cleanPath, ignoreCase = true)
        }

        val savedPath = existing?.path ?: cleanPath
        val savedParent = existing?.parentPath ?: parentPath
        repository.saveFile(
            path = savedPath,
            name = fileName,
            parentPath = savedParent,
            isDirectory = false,
            content = content
        )

        val diskFile = File(workspaceRootDir, savedPath)
        diskFile.parentFile?.mkdirs()
        diskFile.writeText(content)

        // Post-write verification
        val verified = diskFile.exists() && diskFile.readText() == content
        val diagnostics = analyzeSyntaxDiagnostics(savedPath, content)
        val lineCount = content.count { it == '\n' } + 1

        return ToolExecutionResult(
            toolName = "write_file",
            targetPath = savedPath,
            success = verified,
            output = "Wrote $lineCount lines (${content.length} chars) to '$savedPath'. Verified on disk: $verified. Diagnostics: $diagnostics",
            modifiedFilePath = savedPath
        )
    }

    private suspend fun createFileTool(rawPath: String, content: String): ToolExecutionResult {
        return writeFileTool(rawPath, content).copy(toolName = "create_file")
    }

    private suspend fun replaceInFileTool(
        rawPath: String,
        search: String,
        replace: String
    ): ToolExecutionResult {
        val pathResult = sanitizeRelativePath(rawPath)
        if (pathResult.isFailure) {
            return ToolExecutionResult("replace_in_file", rawPath, false, pathResult.exceptionOrNull()?.message ?: "Invalid path")
        }
        val cleanPath = pathResult.getOrThrow()
        val existing = filesSnapshotProvider().firstOrNull {
            !it.isDirectory && (it.path.equals(cleanPath, ignoreCase = true) || it.name.equals(cleanPath, ignoreCase = true))
        } ?: return ToolExecutionResult(
            toolName = "replace_in_file",
            targetPath = cleanPath,
            success = false,
            output = "File '$cleanPath' not found."
        )

        if (search.isEmpty()) {
            return ToolExecutionResult(
                toolName = "replace_in_file",
                targetPath = existing.path,
                success = false,
                output = "Search string cannot be empty for replace_in_file."
            )
        }

        if (!existing.content.contains(search)) {
            return ToolExecutionResult(
                toolName = "replace_in_file",
                targetPath = existing.path,
                success = false,
                output = "Target snippet not found in '${existing.path}'. Read the file first to inspect exact lines."
            )
        }

        val updatedContent = existing.content.replaceFirst(search, replace)
        return writeFileTool(existing.path, updatedContent).copy(toolName = "replace_in_file")
    }

    private suspend fun insertIntoFileTool(
        rawPath: String,
        line: Int,
        content: String
    ): ToolExecutionResult {
        val pathResult = sanitizeRelativePath(rawPath)
        if (pathResult.isFailure) {
            return ToolExecutionResult("insert_into_file", rawPath, false, pathResult.exceptionOrNull()?.message ?: "Invalid path")
        }
        val cleanPath = pathResult.getOrThrow()
        val existing = filesSnapshotProvider().firstOrNull {
            !it.isDirectory && (it.path.equals(cleanPath, ignoreCase = true) || it.name.equals(cleanPath, ignoreCase = true))
        } ?: return ToolExecutionResult(
            toolName = "insert_into_file",
            targetPath = cleanPath,
            success = false,
            output = "File '$cleanPath' not found."
        )

        val lines = existing.content.lines().toMutableList()
        val insertIdx = (line - 1).coerceIn(0, lines.size)
        lines.add(insertIdx, content)
        val updatedContent = lines.joinToString("\n")
        return writeFileTool(existing.path, updatedContent).copy(toolName = "insert_into_file")
    }

    private suspend fun deleteFileTool(rawPath: String, confirmed: Boolean): ToolExecutionResult {
        val pathResult = sanitizeRelativePath(rawPath)
        if (pathResult.isFailure) {
            return ToolExecutionResult("delete_file", rawPath, false, pathResult.exceptionOrNull()?.message ?: "Invalid path")
        }
        val cleanPath = pathResult.getOrThrow()
        val existing = filesSnapshotProvider().firstOrNull {
            it.path.equals(cleanPath, ignoreCase = true) || it.name.equals(cleanPath, ignoreCase = true)
        } ?: return ToolExecutionResult(
            toolName = "delete_file",
            targetPath = cleanPath,
            success = false,
            output = "File '$cleanPath' does not exist."
        )

        if (!confirmed) {
            return ToolExecutionResult(
                toolName = "delete_file",
                targetPath = existing.path,
                success = false,
                output = "Destructive operation: Deleting '${existing.path}' requires user confirmation.",
                requiresUserConfirmation = true
            )
        }

        repository.deleteFile(existing.path, existing.isDirectory)
        val diskFile = File(workspaceRootDir, existing.path)
        if (diskFile.exists()) {
            diskFile.deleteRecursively()
        }
        return ToolExecutionResult(
            toolName = "delete_file",
            targetPath = existing.path,
            success = true,
            output = "Deleted '${existing.path}'."
        )
    }

    private suspend fun renameFileTool(oldRawPath: String, newRawPath: String): ToolExecutionResult {
        val oldPathRes = sanitizeRelativePath(oldRawPath)
        val newPathRes = sanitizeRelativePath(newRawPath)
        if (oldPathRes.isFailure || newPathRes.isFailure) {
            return ToolExecutionResult(
                toolName = "rename_file",
                targetPath = oldRawPath,
                success = false,
                output = "Invalid source or destination path for rename_file."
            )
        }
        val oldClean = oldPathRes.getOrThrow()
        val newClean = newPathRes.getOrThrow()
        val existing = filesSnapshotProvider().firstOrNull {
            it.path.equals(oldClean, ignoreCase = true) || it.name.equals(oldClean, ignoreCase = true)
        } ?: return ToolExecutionResult(
            toolName = "rename_file",
            targetPath = oldClean,
            success = false,
            output = "Source file '$oldClean' not found."
        )

        val newName = newClean.substringAfterLast('/')
        val newParent = newClean.substringBeforeLast('/', "")
        repository.deleteFile(existing.path, existing.isDirectory)
        repository.saveFile(newClean, newName, newParent, existing.isDirectory, existing.content)

        val oldDisk = File(workspaceRootDir, existing.path)
        val newDisk = File(workspaceRootDir, newClean)
        newDisk.parentFile?.mkdirs()
        if (oldDisk.exists()) oldDisk.delete()
        if (!existing.isDirectory) {
            newDisk.writeText(existing.content)
        } else {
            newDisk.mkdirs()
        }

        return ToolExecutionResult(
            toolName = "rename_file",
            targetPath = newClean,
            success = true,
            output = "Renamed '${existing.path}' to '$newClean'.",
            modifiedFilePath = newClean
        )
    }

    private fun searchFilesTool(query: String): ToolExecutionResult {
        if (query.isBlank()) {
            return ToolExecutionResult("search_files", "", false, "Search query cannot be empty.")
        }
        val matches = mutableListOf<String>()
        for (file in filesSnapshotProvider()) {
            if (file.isDirectory) continue
            file.content.lines().forEachIndexed { index, line ->
                if (line.contains(query, ignoreCase = true)) {
                    matches.add("${file.path}:${index + 1}: ${line.trim()}")
                }
            }
        }
        val output = if (matches.isEmpty()) {
            "No matches found for '$query'."
        } else {
            "Found ${matches.size} match(es) for '$query':\n" + matches.take(40).joinToString("\n")
        }
        return ToolExecutionResult("search_files", query, true, output)
    }

    private suspend fun createFolderTool(rawPath: String): ToolExecutionResult {
        val pathRes = sanitizeRelativePath(rawPath)
        if (pathRes.isFailure) {
            return ToolExecutionResult("create_folder", rawPath, false, pathRes.exceptionOrNull()?.message ?: "Invalid folder path")
        }
        val clean = pathRes.getOrThrow()
        val name = clean.substringAfterLast('/')
        val parent = clean.substringBeforeLast('/', "")
        repository.saveFile(clean, name, parent, true, "")
        val folder = File(workspaceRootDir, clean)
        val ok = folder.exists() || folder.mkdirs()
        return ToolExecutionResult(
            toolName = "create_folder",
            targetPath = clean,
            success = ok,
            output = if (ok) "Created folder '$clean' in workspace." else "Failed to create folder '$clean'."
        )
    }

    private fun getActiveEditorContextTool(): ToolExecutionResult {
        val active = activeFileProvider()
            ?: return ToolExecutionResult("get_active_editor_context", "", true, "No active file open in the editor.")
        val cursorLine = activeCursorLineProvider()
        return ToolExecutionResult(
            toolName = "get_active_editor_context",
            targetPath = active.path,
            success = true,
            output = "Active file: ${active.path} (${detectLanguageCode(active.name)}), Cursor at line $cursorLine.\nContent:\n${active.content}"
        )
    }

    private suspend fun applyCodePatchTool(rawPath: String, patch: String): ToolExecutionResult {
        val pathRes = sanitizeRelativePath(rawPath)
        if (pathRes.isFailure) {
            return ToolExecutionResult("apply_code_patch", rawPath, false, pathRes.exceptionOrNull()?.message ?: "Invalid path")
        }
        val cleanPath = pathRes.getOrThrow()
        val existing = filesSnapshotProvider().firstOrNull {
            !it.isDirectory && (it.path.equals(cleanPath, ignoreCase = true) || it.name.equals(cleanPath, ignoreCase = true))
        }

        // Support <<<SEARCH ... === ... >>>REPLACE blocks or full file write
        if (existing != null && patch.contains("<<<SEARCH") && patch.contains(">>>REPLACE")) {
            var updated = existing.content
            val blockRegex = Regex("""<<<SEARCH\n([\s\S]*?)\n===\n([\s\S]*?)\n>>>REPLACE""")
            val blocks = blockRegex.findAll(patch).toList()
            if (blocks.isEmpty()) {
                return ToolExecutionResult("apply_code_patch", cleanPath, false, "Malformed patch block.")
            }
            for (b in blocks) {
                val searchPart = b.groupValues[1]
                val replacePart = b.groupValues[2]
                if (!updated.contains(searchPart)) {
                    return ToolExecutionResult(
                        toolName = "apply_code_patch",
                        targetPath = existing.path,
                        success = false,
                        output = "Patch search block did not match '${existing.path}'."
                    )
                }
                updated = updated.replaceFirst(searchPart, replacePart)
            }
            return writeFileTool(existing.path, updated).copy(toolName = "apply_code_patch")
        }

        return writeFileTool(cleanPath, patch).copy(toolName = "apply_code_patch")
    }

    /**
     * Performs lightweight syntax & bracket balance checks after writing or editing a file.
     */
    fun analyzeSyntaxDiagnostics(filePath: String, content: String): String {
        val issues = mutableListOf<String>()
        var braces = 0
        var parens = 0
        var brackets = 0
        var inSingle = false
        var inDouble = false

        for (i in content.indices) {
            val c = content[i]
            val prev = if (i > 0) content[i - 1] else '\u0000'
            if (c == '\'' && prev != '\\' && !inDouble) inSingle = !inSingle
            else if (c == '"' && prev != '\\' && !inSingle) inDouble = !inDouble
            else if (!inSingle && !inDouble) {
                when (c) {
                    '{' -> braces++
                    '}' -> braces--
                    '(' -> parens++
                    ')' -> parens--
                    '[' -> brackets++
                    ']' -> brackets--
                }
            }
        }

        if (braces != 0) issues.add("Unbalanced curly braces '{}' (delta=$braces)")
        if (parens != 0) issues.add("Unbalanced parentheses '()' (delta=$parens)")
        if (brackets != 0) issues.add("Unbalanced square brackets '[]' (delta=$brackets)")

        if (filePath.endsWith(".py", ignoreCase = true)) {
            content.lines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("fun ") && trimmed.endsWith(":")) {
                    issues.add("Line ${idx + 1}: Python uses 'def' instead of Kotlin 'fun'.")
                }
            }
        }

        return if (issues.isEmpty()) "OK (No syntax or delimiter issues detected)" else issues.joinToString("; ")
    }

    private fun detectLanguageCode(fileName: String): String {
        return when {
            fileName.endsWith(".py", true) -> "python"
            fileName.endsWith(".html", true) || fileName.endsWith(".htm", true) -> "html"
            fileName.endsWith(".css", true) -> "css"
            fileName.endsWith(".js", true) || fileName.endsWith(".jsx", true) -> "javascript"
            fileName.endsWith(".ts", true) || fileName.endsWith(".tsx", true) -> "typescript"
            fileName.endsWith(".kt", true) || fileName.endsWith(".kts", true) -> "kotlin"
            fileName.endsWith(".java", true) -> "java"
            fileName.endsWith(".json", true) -> "json"
            fileName.endsWith(".xml", true) -> "xml"
            fileName.endsWith(".cpp", true) || fileName.endsWith(".c", true) || fileName.endsWith(".h", true) -> "cpp"
            fileName.endsWith(".php", true) -> "php"
            fileName.endsWith(".yml", true) || fileName.endsWith(".yaml", true) -> "yaml"
            fileName.endsWith(".md", true) -> "markdown"
            fileName.endsWith(".sh", true) -> "shell"
            else -> "plaintext"
        }
    }
}
