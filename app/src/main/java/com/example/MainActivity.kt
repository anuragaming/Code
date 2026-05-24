package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.*
import com.example.ui.ChatMessage
import com.example.ui.MeowCodeViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent
import android.net.Uri

class MainActivity : ComponentActivity() {
    private val viewModel: MeowCodeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeowCodeApp(viewModel)
        }
    }
}

// --- Dynamic Theme Config ---
data class IdeTheme(
    val bg: Color,
    val cardBg: Color,
    val headerBg: Color,
    val sidePanelBg: Color,
    val editorBg: Color,
    val terminalBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSecondary: Color
)

val ClassicSlateTheme = IdeTheme(
    bg = Color(0xFF1E1E2E),
    cardBg = Color(0xFF24283B),
    headerBg = Color(0xFF16161E),
    sidePanelBg = Color(0xFF181825),
    editorBg = Color(0xFF1E1E2E),
    terminalBg = Color(0xFF0F0F14),
    textPrimary = Color(0xFFC0CAF5),
    textSecondary = Color(0xFF9ECE6A),
    accent = Color(0xFF38BDF8),
    accentSecondary = Color(0xFF7DD3FC)
)

val DraculaTheme = IdeTheme(
    bg = Color(0xFF282A36),
    cardBg = Color(0xFF44475A),
    headerBg = Color(0xFF191A21),
    sidePanelBg = Color(0xFF1D1F27),
    editorBg = Color(0xFF282A36),
    terminalBg = Color(0xFF14151A),
    textPrimary = Color(0xFFF8F8F2),
    textSecondary = Color(0xFF50FA7B),
    accent = Color(0xFFBD93F9),
    accentSecondary = Color(0xFFFF79C6)
)

val NeonSynthwaveTheme = IdeTheme(
    bg = Color(0xFF1F0D24),
    cardBg = Color(0xFF33143B),
    headerBg = Color(0xFF150818),
    sidePanelBg = Color(0xFF190A1E),
    editorBg = Color(0xFF1F0D24),
    terminalBg = Color(0xFF0D030F),
    textPrimary = Color(0xFFE2E8F0),
    textSecondary = Color(0xFF34D399),
    accent = Color(0xFFEC4899),
    accentSecondary = Color(0xFFF43F5E)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeowCodeApp(viewModel: MeowCodeViewModel) {
    val workspaceFiles by viewModel.workspaceFiles.collectAsStateWithLifecycle()
    val gitRepos by viewModel.gitRepos.collectAsStateWithLifecycle()
    val remoteServers by viewModel.remoteServers.collectAsStateWithLifecycle()
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()

    val selectedPath by viewModel.selectedPath.collectAsStateWithLifecycle()
    val openTabs by viewModel.openTabs.collectAsStateWithLifecycle()
    val editorContent by viewModel.currentEditorContent.collectAsStateWithLifecycle()
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val replaceText by viewModel.searchReplaceText.collectAsStateWithLifecycle()

    val aiChatHistory by viewModel.aiChatHistory.collectAsStateWithLifecycle()
    val aiIsGenerating by viewModel.aiIsGenerating.collectAsStateWithLifecycle()
    val aiModel by viewModel.aiModel.collectAsStateWithLifecycle()
    val aiUrl by viewModel.aiApiUrl.collectAsStateWithLifecycle()
    val aiKey by viewModel.aiApiKey.collectAsStateWithLifecycle()

    val terminalTabs by viewModel.terminalTabs.collectAsStateWithLifecycle()
    val activeTerminalIdx by viewModel.activeTerminalIdx.collectAsStateWithLifecycle()
    val terminalLogs by viewModel.terminalLogs.collectAsStateWithLifecycle()

    val cpuUsage by viewModel.cpuUsage.collectAsStateWithLifecycle()
    val ramUsage by viewModel.ramUsage.collectAsStateWithLifecycle()

    // Resolve Theme dynamically based on installed extensions list
    val currentTheme = remember(extensions) {
        when {
            extensions.any { it.id == "theme.dracula" && it.isInstalled } -> DraculaTheme
            extensions.any { it.id == "theme.synthwave" && it.isInstalled } -> NeonSynthwaveTheme
            else -> ClassicSlateTheme
        }
    }

    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var dialogFileName by remember { mutableStateOf("") }

    var showGitCloneDialog by remember { mutableStateOf(false) }
    var gitRepoName by remember { mutableStateOf("") }
    var gitRepoUrl by remember { mutableStateOf("") }

    var showSshDialog by remember { mutableStateOf(false) }
    var vpsName by remember { mutableStateOf("") }
    var vpsIp by remember { mutableStateOf("") }
    var vpsUser by remember { mutableStateOf("") }
    var vpsPort by remember { mutableStateOf("22") }

    var isSidebarOpen by remember { mutableStateOf(true) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = currentTheme.bg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // --- TOP HEADER BAR ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(currentTheme.headerBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { isSidebarOpen = !isSidebarOpen },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isSidebarOpen) Icons.Default.Close else Icons.Default.Menu,
                            contentDescription = "Toggle Sidebar",
                            tint = currentTheme.accent
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "🐾 Meow Code",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = currentTheme.textPrimary
                    )
                }

                // Live status monitor
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // CPU Metrics box
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(currentTheme.cardBg.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (cpuUsage < 18) Color.Green else Color.Yellow)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "CPU: $cpuUsage%",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = currentTheme.textPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(currentTheme.cardBg.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color.Cyan)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "RAM: $ramUsage%",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = currentTheme.textPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // Run action if file is selected
                    if (selectedPath != null) {
                        IconButton(
                            onClick = { viewModel.runActiveEditorCode() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(currentTheme.accent)
                                .testTag("run_active_code_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Run active file",
                                tint = currentTheme.headerBg,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // --- MAIN DIVISION (Sidebar + Editor Panel) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // COLLAPSIBLE SIDEBAR PANEL
                AnimatedVisibility(
                    visible = isSidebarOpen,
                    enter = slideInHorizontally { -it } + fadeIn(),
                    exit = slideOutHorizontally { -it } + fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(280.dp)
                            .background(currentTheme.sidePanelBg)
                            .drawBehind {
                                drawLine(
                                    color = Color(0x20FFFFFF),
                                    start = Offset(size.width, 0f),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }
                    ) {
                        // Narrow icon column (VS Code style tool activity rail)
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(56.dp)
                                .background(currentTheme.headerBg)
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            val iconsList = listOf(
                                Triple("explorer", Icons.Default.Folder, "Explorer"),
                                Triple("search", Icons.Default.Search, "Global Search"),
                                Triple("git", Icons.Default.Source, "Git Control"),
                                Triple("extensions", Icons.Default.Extension, "Extensions"),
                                Triple("ai", Icons.Default.Bolt, "AI Coder Assistant"),
                                Triple("web", Icons.Default.Language, "Localhost Web"),
                                Triple("vps", Icons.Default.Terminal, "VPS SSH Profiles"),
                                Triple("settings", Icons.Default.Settings, "Core Settings")
                            )

                            iconsList.forEach { (panel, icon, desc) ->
                                val isSelected = activePanel == panel
                                IconButton(
                                    onClick = { viewModel.selectPanel(panel) },
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) currentTheme.bg else Color.Transparent)
                                        .testTag("rail_tab_$panel")
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = desc,
                                        tint = if (isSelected) currentTheme.accent else currentTheme.textPrimary.copy(alpha = 0.5f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        // Broad sidebar item drawer list
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = activePanel.uppercase(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = currentTheme.accent,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            when (activePanel) {
                                "explorer" -> ExplorerPanel(
                                    files = workspaceFiles,
                                    selectedPath = selectedPath,
                                    theme = currentTheme,
                                    onCreateFile = { showCreateFileDialog = true },
                                    onCreateFolder = { showCreateFolderDialog = true },
                                    onSelectFile = { viewModel.selectFile(it) },
                                    onDeleteFile = { path, isDir -> viewModel.deleteFile(path, isDir) }
                                )
                                "search" -> SearchPanel(
                                    query = searchQuery,
                                    replace = replaceText,
                                    theme = currentTheme,
                                    onQueryChange = { viewModel.updateSearchQuery(it) },
                                    onReplaceChange = { viewModel.updateReplaceText(it) },
                                    onExecute = { viewModel.executeGlobalReplace() }
                                )
                                "git" -> GitPanel(
                                    repos = gitRepos,
                                    theme = currentTheme,
                                    onCloneClick = { showGitCloneDialog = true },
                                    onResolve = { viewModel.mergeConflictResolver(it) },
                                    onDeleteRepo = { viewModel.deleteGitRepo(it) }
                                )
                                "extensions" -> ExtensionsPanel(
                                    extensions = extensions,
                                    theme = currentTheme,
                                    onToggle = { viewModel.toggleExtensionInstallation(it) }
                                )
                                "ai" -> AiAssistantPanel(
                                    history = aiChatHistory,
                                    isGenerating = aiIsGenerating,
                                    model = aiModel,
                                    theme = currentTheme,
                                    onSend = { viewModel.sendAiPrompt(it) },
                                    onApplyCode = { viewModel.applyAiCodeToEditor(it) }
                                )
                                "web" -> WebPanel(
                                    viewModel = viewModel,
                                    theme = currentTheme
                                )
                                "vps" -> VPSPanel(
                                    servers = remoteServers,
                                    theme = currentTheme,
                                    onAddServer = { showSshDialog = true },
                                    onRemove = { viewModel.removeServer(it) }
                                )
                                "settings" -> SettingsPanel(
                                    url = aiUrl,
                                    key = aiKey,
                                    model = aiModel,
                                    theme = currentTheme,
                                    onUpdateSettings = { url, key, m -> viewModel.updateAiSettings(url, key, m) }
                                )
                            }
                        }
                    }
                }

                // RIGHT EDITOR AREA (Tabs + Source Text box + collapsible terminal logs)
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                ) {
                    // TAB BAR ROW
                    if (openTabs.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .background(currentTheme.headerBg)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            openTabs.forEach { tabPath ->
                                val isSelected = selectedPath == tabPath
                                val tabName = tabPath.substringAfterLast('/')
                                Row(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .background(if (isSelected) currentTheme.bg else currentTheme.headerBg)
                                        .clickable { viewModel.selectFile(tabPath) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .drawBehind {
                                            if (isSelected) {
                                                drawLine(
                                                    color = currentTheme.accent,
                                                    start = Offset(0f, size.height - 2.dp.toPx()),
                                                    end = Offset(size.width, size.height - 2.dp.toPx()),
                                                    strokeWidth = 2.dp.toPx()
                                                )
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = getFileIcon(tabName),
                                        contentDescription = null,
                                        tint = getFileIconTint(tabName, currentTheme),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tabName,
                                        fontSize = 12.sp,
                                        color = if (isSelected) currentTheme.textPrimary else currentTheme.textPrimary.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { viewModel.closeTab(tabPath) },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close Tab",
                                            tint = currentTheme.textPrimary.copy(alpha = 0.4f),
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ACTIVE TEXT EDITOR OR EMPTY STATE CONTAINER
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.2f)
                            .background(currentTheme.editorBg)
                    ) {
                        if (selectedPath != null) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Editor shortcut action row
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(currentTheme.headerBg.copy(alpha = 0.4f))
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "📍 $selectedPath",
                                        fontSize = 11.sp,
                                        color = currentTheme.accent,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.applyUndo() },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Undo,
                                                contentDescription = "Undo",
                                                tint = currentTheme.textPrimary.copy(alpha = 0.8f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.applyRedo() },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Redo,
                                                contentDescription = "Redo",
                                                tint = currentTheme.textPrimary.copy(alpha = 0.8f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    // Simulated Line Numbers column
                                    val linesCount = editorContent.split("\n").size.coerceAtLeast(1)
                                    Column(
                                        modifier = Modifier
                                            .width(28.dp)
                                            .fillMaxHeight()
                                            .background(currentTheme.headerBg.copy(alpha = 0.15f))
                                            .padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        for (i in 1..linesCount) {
                                            Text(
                                                text = i.toString(),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = currentTheme.textPrimary.copy(alpha = 0.25f)
                                            )
                                        }
                                    }

                                    // Real Interactive Text Field Editor space
                                    BasicTextField(
                                        value = editorContent,
                                        onValueChange = { viewModel.updateEditorContent(it) },
                                        textStyle = TextStyle(
                                            color = currentTheme.textPrimary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp
                                        ),
                                        cursorBrush = SolidColor(currentTheme.accent),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp)
                                            .testTag("code_editor_field")
                                    )
                                }
                            }
                        } else {
                            // Workspace Empty Welcome State
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "🐾 MEOW CODE IDE v1.0.0",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = currentTheme.accent
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "VS Code-inspired responsive app. Tap files in the sidebar explorer tab to begin meow-coding, run Python servers or scripts directly in the terminal, utilize Git repos, and deploy custom API code helper streams.",
                                    fontSize = 13.sp,
                                    color = currentTheme.textPrimary.copy(alpha = 0.6f),
                                    lineHeight = 18.sp,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = { viewModel.selectPanel("explorer") },
                                    colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent)
                                ) {
                                    Text("Open File Explorer 📁", color = currentTheme.headerBg)
                                }
                            }
                        }
                    }

                    // --- COLLAPSIBLE INTEGRATED TERMINAL AND RESOURCE MONITOR ---
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.8f)
                            .background(currentTheme.terminalBg)
                    ) {
                        // Terminal Status and Tabs Selection Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(currentTheme.headerBg)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f)
                            ) {
                                terminalTabs.forEachIndexed { idx, title ->
                                    val isSelected = activeTerminalIdx == idx
                                    Text(
                                        text = title,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (isSelected) currentTheme.accent else currentTheme.textPrimary.copy(alpha = 0.4f),
                                        modifier = Modifier
                                            .clickable { viewModel.switchTerminalTab(idx) }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                            .drawBehind {
                                                if (isSelected) {
                                                    drawLine(
                                                        color = currentTheme.accent,
                                                        start = Offset(0f, size.height - 2.dp.toPx()),
                                                        end = Offset(size.width, size.height - 2.dp.toPx()),
                                                        strokeWidth = 2.dp.toPx()
                                                    )
                                                }
                                            }
                                    )
                                }
                            }
                        }

                        // Terminal output scroll view and input controller
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            val activeLogs = terminalLogs[activeTerminalIdx] ?: emptyList()
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(activeLogs) { logLine ->
                                    Text(
                                        text = logLine,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.2.sp,
                                        color = getTerminalLogColor(logLine, currentTheme),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // Command input prompt row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val prompt = when (activeTerminalIdx) {
                                    0 -> "meow@root:~$"
                                    1 -> ">>>"
                                    else -> ">"
                                }
                                Text(
                                    text = "$prompt ",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = currentTheme.accent
                                )

                                var termInput by remember { mutableStateOf("") }
                                val kb = LocalSoftwareKeyboardController.current

                                BasicTextField(
                                    value = termInput,
                                    onValueChange = { termInput = it },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = {
                                        viewModel.submitTerminalCommand(termInput)
                                        termInput = ""
                                        kb?.hide()
                                    }),
                                    textStyle = TextStyle(
                                        color = currentTheme.textPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    ),
                                    cursorBrush = SolidColor(currentTheme.accent),
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(vertical = 6.dp)
                                        .testTag("terminal_input_field")
                                )

                                if (termInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            viewModel.submitTerminalCommand(termInput)
                                            termInput = ""
                                            kb?.hide()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Send,
                                            contentDescription = "Send command",
                                            tint = currentTheme.accent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- POPUP DIALOGS ---

    // 1. Create File Dialog
    if (showCreateFileDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false; dialogFileName = "" },
            title = { Text("Create New File 📄") },
            text = {
                Column {
                    Text("Enter filename with extension (e.g. main.py, script.js, style.css):", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogFileName,
                        onValueChange = { dialogFileName = it },
                        placeholder = { Text("index.html") },
                        singleLine = true,
                        modifier = Modifier.testTag("new_file_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dialogFileName.isNotEmpty()) {
                            viewModel.createFile(dialogFileName, false)
                            dialogFileName = ""
                            showCreateFileDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false; dialogFileName = "" }) { Text("Cancel") }
            }
        )
    }

    // 2. Create Folder Dialog
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false; dialogFileName = "" },
            title = { Text("Create New Folder 📁") },
            text = {
                Column {
                    Text("Enter folder name:", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogFileName,
                        onValueChange = { dialogFileName = it },
                        placeholder = { Text("assets") },
                        singleLine = true,
                        modifier = Modifier.testTag("new_folder_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dialogFileName.isNotEmpty()) {
                            viewModel.createFile(dialogFileName, true)
                            dialogFileName = ""
                            showCreateFolderDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false; dialogFileName = "" }) { Text("Cancel") }
            }
        )
    }

    // 3. Git Clone Dialog
    if (showGitCloneDialog) {
        AlertDialog(
            onDismissRequest = { showGitCloneDialog = false; gitRepoName = ""; gitRepoUrl = "" },
            title = { Text("Clone Git Repository 🐙") },
            text = {
                Column {
                    Text("Connect directly to GitHub, GitLab or Bitbucket repositories:", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = gitRepoName,
                        onValueChange = { gitRepoName = it },
                        label = { Text("Repository Name") },
                        placeholder = { Text("meow-backend") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = gitRepoUrl,
                        onValueChange = { gitRepoUrl = it },
                        label = { Text("Clone URL") },
                        placeholder = { Text("https://github.com/coder/meow-backend.git") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (gitRepoName.isNotEmpty() && gitRepoUrl.isNotEmpty()) {
                            viewModel.submitGitRepositoryClone(gitRepoName, gitRepoUrl)
                            gitRepoName = ""
                            gitRepoUrl = ""
                            showGitCloneDialog = false
                        }
                    }
                ) { Text("Clone Repo") }
            },
            dismissButton = {
                TextButton(onClick = { showGitCloneDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 4. SSH Add VPS Dialog
    if (showSshDialog) {
        AlertDialog(
            onDismissRequest = { showSshDialog = false },
            title = { Text("Remote Server (VPS) via SSH 🖥️") },
            text = {
                Column {
                    Text("Connect VPS, Linux Server, or Raspberry Pi to work on files remotely:", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vpsName,
                        onValueChange = { vpsName = it },
                        label = { Text("Host Alias") },
                        placeholder = { Text("Production Cloud") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = vpsIp,
                        onValueChange = { vpsIp = it },
                        label = { Text("IP Address / Domain") },
                        placeholder = { Text("192.168.1.100") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = vpsUser,
                        onValueChange = { vpsUser = it },
                        label = { Text("SSH Username") },
                        placeholder = { Text("root") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = vpsPort,
                        onValueChange = { vpsPort = it },
                        label = { Text("Port") },
                        placeholder = { Text("22") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (vpsName.isNotEmpty() && vpsIp.isNotEmpty() && vpsUser.isNotEmpty()) {
                            viewModel.submitVpsServerCreation(vpsName, vpsIp, vpsUser, vpsPort)
                            vpsName = ""
                            vpsIp = ""
                            vpsUser = ""
                            showSshDialog = false
                        }
                    }
                ) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showSshDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// --- EXPLORER PANEL COMPOSE ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerPanel(
    files: List<WorkspaceFile>,
    selectedPath: String?,
    theme: IdeTheme,
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    onSelectFile: (String) -> Unit,
    onDeleteFile: (String, Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "WORKSPACE FILES",
                fontSize = 10.sp,
                color = theme.textPrimary.copy(alpha = 0.5f)
            )
            Row {
                IconButton(onClick = onCreateFile, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Add, "Add File", tint = theme.accent, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onCreateFolder, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.CreateNewFolder, "Add Folder", tint = theme.accent, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(files) { file ->
                val isSelected = selectedPath == file.path
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) theme.cardBg else Color.Transparent)
                        .combinedClickable(
                            onClick = { if (!file.isDirectory) onSelectFile(file.path) },
                            onLongClick = { onDeleteFile(file.path, file.isDirectory) }
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pfxWidth = if (file.parentPath.isNotEmpty()) 12.dp else 0.dp
                    Spacer(modifier = Modifier.width(pfxWidth))

                    Icon(
                        imageVector = if (file.isDirectory) Icons.Default.Folder else getFileIcon(file.name),
                        contentDescription = null,
                        tint = if (file.isDirectory) Color(0xFFFFD166) else getFileIconTint(file.name, theme),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = file.name,
                        fontSize = 12.5.sp,
                        color = if (isSelected) theme.accent else theme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Text(
            "* Tip: long-click files to delete them.",
            fontSize = 9.sp,
            color = theme.textPrimary.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// --- GLOBAL SEARCH PANEL ---
@Composable
fun SearchPanel(
    query: String,
    replace: String,
    theme: IdeTheme,
    onQueryChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onExecute: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search text") },
            placeholder = { Text("Meow") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = theme.accent,
                focusedLabelColor = theme.accent
            ),
            modifier = Modifier.fillMaxWidth().testTag("search_query_input")
        )

        OutlinedTextField(
            value = replace,
            onValueChange = onReplaceChange,
            label = { Text("Replace text") },
            placeholder = { Text("Purr") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = theme.accent,
                focusedLabelColor = theme.accent
            ),
            modifier = Modifier.fillMaxWidth().testTag("search_replace_input")
        )

        Button(
            onClick = onExecute,
            modifier = Modifier.fillMaxWidth().testTag("execute_replace_button"),
            colors = ButtonDefaults.buttonColors(containerColor = theme.accent)
        ) {
            Text("Replace Workspace Wide 🔍", color = theme.headerBg)
        }
    }
}

// --- REPOSITORY SOURCE CONTROL PANEL ---
@Composable
fun GitPanel(
    repos: List<GitRepo>,
    theme: IdeTheme,
    onCloneClick: () -> Unit,
    onResolve: (Int) -> Unit,
    onDeleteRepo: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = onCloneClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = theme.accent)
        ) {
            Text("Clone Git Repository 🐙", color = theme.headerBg)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "ACTIVE REPOSITORIES",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = theme.textPrimary.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 6.dp)
        )

        if (repos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No active Git checkouts.",
                    fontSize = 11.5.sp,
                    color = theme.textPrimary.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(repos) { repo ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = theme.cardBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    repo.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.accent
                                )
                                Text(
                                    "✨ Branch: ${repo.currentBranch}",
                                    fontSize = 11.sp,
                                    color = theme.textSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                repo.url,
                                fontSize = 10.sp,
                                color = theme.textPrimary.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(
                                    onClick = { onDeleteRepo(repo.id) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                                    modifier = Modifier.height(30.dp)
                                ) { Text("Remove", fontSize = 11.sp) }

                                if (repo.remoteStatus == "Conflict") {
                                    Button(
                                        onClick = { onResolve(repo.id) },
                                        colors = ButtonDefaults.buttonColors(containerColor = theme.accent),
                                        modifier = Modifier.height(28.dp)
                                    ) { Text("Resolve Conflict", fontSize = 10.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- EXTENSION INTEGRATION COMPOSE PANEL ---
@Composable
fun ExtensionsPanel(
    extensions: List<ExtensionItem>,
    theme: IdeTheme,
    onToggle: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(extensions) { ext ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (ext.isInstalled) theme.accent.copy(alpha = 0.12f) else theme.cardBg
                ),
                border = BorderStroke(
                    1.dp,
                    if (ext.isInstalled) theme.accent.copy(alpha = 0.5f) else Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            ext.name,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(theme.headerBg)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                ext.category,
                                fontSize = 9.sp,
                                color = theme.accent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        ext.description,
                        fontSize = 11.sp,
                        color = theme.textPrimary.copy(alpha = 0.61f),
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Author: ${ext.author} • ⭐ ${ext.rating}",
                            fontSize = 10.sp,
                            color = theme.textPrimary.copy(alpha = 0.4f)
                        )
                        Button(
                            onClick = { onToggle(ext.id) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (ext.isInstalled) Color.Gray else theme.accent
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = if (ext.isInstalled) "Uninstall" else "Install",
                                fontSize = 10.5.sp,
                                color = if (ext.isInstalled) Color.White else theme.headerBg
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- AI PROGRAMMING CHAT ASSISTANT PANEL ---
@Composable
fun AiAssistantPanel(
    history: List<ChatMessage>,
    isGenerating: String,
    model: String,
    theme: IdeTheme,
    onSend: (String) -> Unit,
    onApplyCode: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.headerBg)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                "🤖 Assistant Engine: $model",
                fontSize = 10.sp,
                color = theme.accent,
                maxLines = 1
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(history) { msg ->
                val isMe = msg.sender == "user"
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isMe) theme.accent.copy(alpha = 0.2f) else theme.cardBg)
                            .padding(8.dp)
                            .widthIn(max = 200.dp)
                    ) {
                        Column {
                            SelectionContainer {
                                Text(
                                    text = msg.content,
                                    fontSize = 11.5.sp,
                                    color = if (isMe) theme.accentSecondary else theme.textPrimary,
                                    lineHeight = 15.sp
                                )
                            }
                            // Quick snippet extract injector actions if response has code
                            if (!isMe && (msg.content.contains("```") || msg.content.contains("def") || msg.content.contains("function"))) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "🐾 Apply snippet directly",
                                    fontSize = 10.sp,
                                    color = theme.textSecondary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            val parsedCode = extractMarkdownCodeSnippet(msg.content)
                                            onApplyCode(parsedCode)
                                        }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isGenerating.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = theme.accent)
                Spacer(modifier = Modifier.width(6.dp))
                Text(isGenerating, fontSize = 10.sp, color = theme.accent, fontFamily = FontFamily.Monospace)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var inputPrompt by remember { mutableStateOf("") }
            BasicTextField(
                value = inputPrompt,
                onValueChange = { inputPrompt = it },
                textStyle = TextStyle(color = theme.textPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(theme.accent),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(BorderStroke(1.dp, theme.cardBg), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("ai_prompt_input_field")
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = {
                    if (inputPrompt.isNotEmpty()) {
                        onSend(inputPrompt)
                        inputPrompt = ""
                    }
                },
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(theme.accent)
            ) {
                Icon(Icons.Default.Send, "Send prompt", tint = theme.headerBg, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// --- VPS SH CONSOLE PANEL ---
@Composable
fun VPSPanel(
    servers: List<RemoteServer>,
    theme: IdeTheme,
    onAddServer: () -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = onAddServer,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = theme.accent)
        ) {
            Text("Add VPS Tunnel Profile 🖥️", color = theme.headerBg)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "TUNNELED HOSTS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = theme.textPrimary.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 6.dp)
        )

        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No server profiles configured.",
                    fontSize = 11.sp,
                    color = theme.textPrimary.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers) { srv ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = theme.cardBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    srv.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.accent
                                )
                                Text(
                                    srv.status,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "${srv.username}@${srv.ipAddress}:${srv.port}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textPrimary.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = { onRemove(srv.id) },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("Disconnect & Remove", fontSize = 10.5.sp) }
                        }
                    }
                }
            }
        }
    }
}

// --- CORE IDE SETTINGS PANEL ---
@Composable
fun SettingsPanel(
    url: String,
    key: String,
    model: String,
    theme: IdeTheme,
    onUpdateSettings: (String, String, String) -> Unit
) {
    var editedUrl by remember(url) { mutableStateOf(url) }
    var editedKey by remember(key) { mutableStateOf(key) }
    var editedModel by remember(model) { mutableStateOf(model) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "CUSTOM AI API INTEGRATION",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = theme.accent,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        OutlinedTextField(
            value = editedUrl,
            onValueChange = { editedUrl = it },
            label = { Text("Base Chat API URL") },
            placeholder = { Text("Leave empty for Google Gemini") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = theme.accent,
                focusedLabelColor = theme.accent
            ),
            modifier = Modifier.fillMaxWidth().testTag("custom_api_url_input")
        )

        OutlinedTextField(
            value = editedKey,
            onValueChange = { editedKey = it },
            label = { Text("API Secret Key") },
            placeholder = { Text("Leave empty to use Build Secrets") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = theme.accent,
                focusedLabelColor = theme.accent
            ),
            modifier = Modifier.fillMaxWidth().testTag("custom_api_key_input")
        )

        OutlinedTextField(
            value = editedModel,
            onValueChange = { editedModel = it },
            label = { Text("API Model ID") },
            placeholder = { Text("gemini-3.5-flash") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = theme.accent,
                focusedLabelColor = theme.accent
            ),
            modifier = Modifier.fillMaxWidth().testTag("custom_api_model_input")
        )

        Button(
            onClick = { onUpdateSettings(editedUrl, editedKey, editedModel) },
            modifier = Modifier.fillMaxWidth().testTag("save_settings_button"),
            colors = ButtonDefaults.buttonColors(containerColor = theme.accent)
        ) {
            Text("Save API Credentials 🔐", color = theme.headerBg)
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp), color = theme.textPrimary.copy(alpha = 0.15f))

        Text(
            "DEVELOPMENT ENVIRONMENT",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = theme.accent
        )
        Text("Indent Mode: 4 Spaces", fontSize = 11.sp, color = theme.textPrimary.copy(alpha = 0.7f))
        Text("Terminal Host: Embedded Termux Sandbox", fontSize = 11.sp, color = theme.textPrimary.copy(alpha = 0.7f))
        Text("Language Support: Triaged Syntax v1.4", fontSize = 11.sp, color = theme.textPrimary.copy(alpha = 0.7f))
    }
}

// --- UTIL HELPER FUNCTIONS ---

fun getFileIcon(name: String) = when {
    name.endsWith(".py") -> Icons.Default.Code
    name.endsWith(".js") -> Icons.Default.Javascript
    name.endsWith(".html") -> Icons.Default.Html
    name.endsWith(".css") -> Icons.Default.Css
    name.endsWith(".json") -> Icons.Default.DataObject
    else -> Icons.Default.Description
}

fun getFileIconTint(name: String, theme: IdeTheme) = when {
    name.endsWith(".py") -> Color(0xFF38BDF8)
    name.endsWith(".js") -> Color(0xFFFACC15)
    name.endsWith(".html") -> Color(0xFFFB923C)
    name.endsWith(".css") -> Color(0xFF60A5FA)
    name.endsWith(".json") -> Color(0xFFF472B6)
    else -> theme.textPrimary.copy(alpha = 0.8f)
}

fun getTerminalLogColor(line: String, theme: IdeTheme) = when {
    line.startsWith("meow@") -> theme.accent
    line.startsWith("Err:") || line.lowercase().contains("syntaxerror") -> Color.Red
    line.startsWith(">>>") || line.startsWith(">") -> theme.accentSecondary
    line.startsWith("[Python") || line.startsWith("[NodeJS") -> theme.textSecondary
    else -> theme.textPrimary
}

fun extractMarkdownCodeSnippet(md: String): String {
    if (!md.contains("```")) return md
    return try {
        val splitIndices = mutableListOf<Int>()
        var searchPos = 0
        while (true) {
            val idx = md.indexOf("```", searchPos)
            if (idx == -1) break
            splitIndices.add(idx)
            searchPos = idx + 3
        }
        if (splitIndices.size >= 2) {
            val start = splitIndices[0] + 3
            val end = splitIndices[1]
            val chunk = md.substring(start, end).trim()
            // Strip any leading language qualifier line, e.g. "python\n" or "javascript\n"
            val nlIdx = chunk.indexOf('\n')
            if (nlIdx != -1 && nlIdx < 12) {
                chunk.substring(nlIdx + 1)
            } else {
                chunk
            }
        } else {
            md
        }
    } catch (e: Exception) {
        md
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

// --- WEBPANEL FOR LOCALHOST WEB SERVER PREVIEW ---
@Composable
fun WebPanel(
    viewModel: MeowCodeViewModel,
    theme: IdeTheme
) {
    val isWebRunning by viewModel.isWebRunning.collectAsStateWithLifecycle()
    val serverPort by viewModel.serverPort.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webViewUrl by remember { mutableStateOf("http://localhost:8080/index.html") }
    var refreshKey by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Server Control Card
        Card(
            colors = CardDefaults.cardColors(containerColor = theme.cardBg),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Localhost Server",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )

                    // Small indicator light
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isWebRunning) Color.Green else Color.Gray)
                        )
                        Text(
                            text = if (isWebRunning) "Running" else "Stopped",
                            fontSize = 11.sp,
                            color = if (isWebRunning) Color.Green else theme.textPrimary.copy(alpha = 0.5f)
                        )
                    }
                }

                Text(
                    text = "Hosts index.html, style.css, script.js locally to build responsive websites right inside Meow Code.",
                    fontSize = 11.sp,
                    color = theme.textPrimary.copy(alpha = 0.7f),
                    lineHeight = 14.sp
                )

                Button(
                    onClick = { viewModel.toggleWebLocalhostServer() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isWebRunning) Color(0xFFEF4444) else theme.accent
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (isWebRunning) "🛑 Stop Localhost Server" else "⚡ Start Localhost:8080",
                        color = if (isWebRunning) Color.White else theme.headerBg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (isWebRunning) {
            // Action Panel
            Card(
                colors = CardDefaults.cardColors(containerColor = theme.cardBg),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Quick Actions",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("http://localhost:8080/index.html")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback or notification
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = theme.accent),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("🌐 Open in Default Browser", fontSize = 12.sp, color = theme.headerBg)
                    }

                    Button(
                        onClick = { refreshKey++ },
                        colors = ButtonDefaults.buttonColors(containerColor = theme.cardBg.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, theme.textPrimary.copy(alpha = 0.3f))
                    ) {
                        Text("🔄 Reload Web Preview", fontSize = 12.sp, color = theme.textPrimary)
                    }
                }
            }

            // Interactive Live Web Preview Frame inside SidePanel
            Text(
                "LIVE PREVIEW",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = theme.textPrimary.copy(alpha = 0.5f),
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .border(1.dp, theme.textPrimary.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    key(refreshKey) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewClient = WebViewClient()
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    loadUrl(webViewUrl)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        } else {
            // Disabled State helper info
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "😴 Server is offline",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary.copy(alpha = 0.5f)
                    )
                    Text(
                        "Start the localhost server above to preview your pages in real-time.",
                        fontSize = 11.sp,
                        color = theme.textPrimary.copy(alpha = 0.4f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

