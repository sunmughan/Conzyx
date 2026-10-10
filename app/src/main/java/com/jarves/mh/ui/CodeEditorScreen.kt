package com.jarves.mh.ui

import com.jarves.mh.runtime.SemanticCodeEngine
import java.io.File

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.max

private data class EditorSymbol(val name: String, val line: Int, val offset: Int, val kind: String = "DECLARATION")
private data class BracketPair(val cursor: Int, val match: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    filePath: String,
    content: String?,
    loading: Boolean,
    openTabs: List<String> = listOf(filePath),
    onSelectTab: (String) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    onCloseOtherTabs: (String) -> Unit = {},
    onCloseAllTabs: () -> Unit = {},
    onClose: () -> Unit,
    onSave: suspend (String) -> Boolean,
    workspaceDir: File? = null,
    onNavigateToFileLine: ((String, Int) -> Unit)? = null,
) {
    val source = content.orEmpty()
    val readOnly = loading || source.contains("[File truncated — too large to display fully]")
    var value by remember(filePath) { mutableStateOf(TextFieldValue(source)) }
    var savedText by remember(filePath) { mutableStateOf(source) }
    var searchOpen by rememberSaveable(filePath) { mutableStateOf(false) }
    var menuOpen by rememberSaveable(filePath) { mutableStateOf(false) }
    var wordWrap by rememberSaveable(filePath) { mutableStateOf(true) }
    var outlineOpen by rememberSaveable(filePath) { mutableStateOf(false) }
    var goToLineOpen by rememberSaveable(filePath) { mutableStateOf(false) }
    var globalSearchOpen by rememberSaveable(filePath) { mutableStateOf(false) }
    var commandPaletteOpen by rememberSaveable(filePath) { mutableStateOf(false) }
    var find by rememberSaveable(filePath) { mutableStateOf("") }
    var replace by rememberSaveable(filePath) { mutableStateOf("") }
    var lineInput by rememberSaveable(filePath) { mutableStateOf("") }
    var searchInSelection by rememberSaveable(filePath) { mutableStateOf(false) }
    var selectionRangeAtSearch by remember(filePath) { mutableStateOf<TextRange?>(null) }
    var bracketPair by remember(filePath) { mutableStateOf<BracketPair?>(null) }
    var saving by rememberSaveable(filePath) { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable(filePath) { mutableStateOf(false) }
    var splitScreenOpen by rememberSaveable(filePath) { mutableStateOf(false) }
    var secondaryFilePath by rememberSaveable(filePath) {
        mutableStateOf(openTabs.firstOrNull { it != filePath } ?: filePath)
    }
    var secondaryValue by remember(secondaryFilePath) {
        val initialSecText = if (secondaryFilePath == filePath) {
            source
        } else {
            try {
                val f = File(secondaryFilePath)
                if (f.exists() && f.isFile) f.readText() else ""
            } catch (_: Exception) { "" }
        }
        mutableStateOf(TextFieldValue(initialSecText))
    }
    val secondaryVertical = rememberScrollState()
    val secondaryHorizontal = rememberScrollState()
    val secondaryLines = remember(secondaryValue.text) { secondaryValue.text.split('\n') }
    val secondaryLineNumbers = remember(secondaryLines.size) { (1..max(1, secondaryLines.size)).joinToString("\n") }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(source, filePath) {
        value = TextFieldValue(source)
        savedText = source
        bracketPair = null
        selectionRangeAtSearch = null
    }

    val dirty = value.text != savedText
    val lines = remember(value.text) { value.text.split('\n') }
    val lineNumbers = remember(lines.size) { (1..max(1, lines.size)).joinToString("\n") }
    val symbols = remember(value.text, filePath) { extractEditorSymbols(value.text, filePath) }

    fun goToLine(line: Int) {
        val target = line.coerceIn(1, max(1, lines.size))
        val offset = value.text.split('\n')
            .take(target - 1)
            .sumOf { it.length + 1 }
            .coerceIn(0, value.text.length)
        value = value.copy(selection = TextRange(offset))
        scope.launch { vertical.animateScrollTo(((target - 1) * 20 * 3).coerceAtLeast(0)) }
    }

    fun findMatch(from: Int, forward: Boolean): Int {
        if (find.isBlank()) return -1
        val text = value.text
        if (text.isEmpty()) return -1

        val range = if (searchInSelection && selectionRangeAtSearch != null) {
            selectionRangeAtSearch!!
        } else {
            TextRange(0, text.length)
        }
        val minIdx = range.min
        val maxIdx = range.max

        return if (forward) {
            val start = from.coerceIn(minIdx, maxIdx)
            val found = text.indexOf(find, startIndex = start, ignoreCase = true)
            if (found in minIdx..(maxIdx - find.length)) {
                found
            } else {
                val wrapped = text.indexOf(find, startIndex = minIdx, ignoreCase = true)
                if (wrapped in minIdx..(maxIdx - find.length)) wrapped else -1
            }
        } else {
            val start = (from - 1).coerceIn(minIdx, maxIdx)
            val found = text.lastIndexOf(find, startIndex = start, ignoreCase = true)
            if (found in minIdx..(maxIdx - find.length)) {
                found
            } else {
                val wrapped = text.lastIndexOf(find, startIndex = maxIdx - 1, ignoreCase = true)
                if (wrapped in minIdx..(maxIdx - find.length)) wrapped else -1
            }
        }
    }

    fun selectMatch(index: Int) {
        if (index < 0 || find.isBlank()) return
        value = value.copy(selection = TextRange(index, (index + find.length).coerceAtMost(value.text.length)))
    }

    fun findNext() = selectMatch(findMatch(value.selection.end, forward = true))
    fun findPrevious() = selectMatch(findMatch(value.selection.start, forward = false))

    fun replaceCurrent() {
        if (find.isBlank() || readOnly) return
        val start = value.selection.start
        val end = value.selection.end
        val selected = value.text.substring(start, end)
        if (!selected.equals(find, ignoreCase = true)) {
            findNext()
            return
        }
        val replaced = value.text.removeRange(start, end).let { before ->
            before.substring(0, start) + replace + before.substring(start)
        }
        val cursor = (start + replace.length).coerceAtMost(replaced.length)
        value = TextFieldValue(replaced, TextRange(cursor))
        bracketPair = findBracketPair(replaced, cursor)
    }

    fun replaceAll() {
        if (find.isBlank() || readOnly) return
        val replaced = value.text.replace(find, replace, ignoreCase = true)
        if (replaced != value.text) {
            val cursor = value.selection.start.coerceAtMost(replaced.length)
            value = TextFieldValue(replaced, TextRange(cursor))
            bracketPair = findBracketPair(replaced, cursor)
        }
    }


    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save your changes to " + filePath.substringAfterLast('/') + " before closing?") },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        scope.launch {
                            saving = true
                            val saved = onSave(value.text)
                            saving = false
                            if (saved) {
                                savedText = value.text
                                showDiscardDialog = false
                                onClose()
                            }
                        }
                    },
                ) { Text("Save & close") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    enabled = !saving,
                    onClick = {
                        showDiscardDialog = false
                        onClose()
                    },
                ) { Text("Discard") }
            },
        )
    }

    if (outlineOpen) {
        ModalBottomSheet(onDismissRequest = { outlineOpen = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .padding(horizontal = 18.dp),
            ) {
                Text("Symbol outline", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    symbols.size.toString() + " declarations in " + filePath,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (symbols.isEmpty()) {
                    Text(
                        "No common class/function declarations detected.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        items(symbols.size, key = { symbols[it].offset }) { index ->
                            val symbol = symbols[index]
                            TextButton(
                                onClick = {
                                    goToLine(symbol.line)
                                    outlineOpen = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(symbol.name)
                                        Text(
                                            "Line " + symbol.line,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            symbol.kind.lowercase(),
                                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
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

    if (goToLineOpen) {
        AlertDialog(
            onDismissRequest = { goToLineOpen = false },
            title = { Text("Go to line") },
            text = {
                OutlinedTextField(
                    value = lineInput,
                    onValueChange = { lineInput = it.filter(Char::isDigit).take(7) },
                    singleLine = true,
                    label = { Text("Line number") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        lineInput.toIntOrNull()?.let(::goToLine)
                        goToLineOpen = false
                    },
                    enabled = lineInput.toIntOrNull()?.let { it > 0 } == true,
                ) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { goToLineOpen = false }) { Text("Cancel") } },
        )
    }

    if (commandPaletteOpen) {
        CommandPalette(
            hasActiveEditor = true,
            onDismiss = { commandPaletteOpen = false },
            onActionSelected = { actionId ->
                when (actionId) {
                    "save_file" -> {
                        val textToSave = value.text
                        scope.launch {
                            saving = true
                            if (onSave(textToSave)) savedText = textToSave
                            saving = false
                        }
                    }
                    "find_in_file" -> searchOpen = true
                    "global_search" -> globalSearchOpen = true
                    "goto_line" -> goToLineOpen = true
                    "symbol_outline" -> outlineOpen = true
                    "toggle_word_wrap" -> wordWrap = !wordWrap
                    "close_tab" -> onCloseTab(filePath)
                    "close_other_tabs" -> onCloseOtherTabs(filePath)
                    "close_all_tabs" -> onCloseAllTabs()
                }
            },
        )
    }

    if (globalSearchOpen && workspaceDir != null) {
        GlobalSearchDialog(
            workspaceDir = workspaceDir,
            initialQuery = find,
            onDismiss = { globalSearchOpen = false },
            onNavigateToMatch = { path, line ->
                globalSearchOpen = false
                if (path == filePath) {
                    goToLine(line)
                } else {
                    onNavigateToFileLine?.invoke(path, line)
                }
            },
        )
    }

    // The parent owns navigation; the editor owns dirty-state confirmation.
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(filePath.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                        Text(filePath, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (dirty && !readOnly) showDiscardDialog = true else onClose() },
                        enabled = !saving,
                    ) { Icon(Icons.Default.Close, "Close editor") }
                },
                actions = {
                    if (dirty && !readOnly) {
                        IconButton(
                            enabled = !saving,
                            onClick = {
                                val textToSave = value.text
                                scope.launch {
                                    saving = true
                                    if (onSave(textToSave)) savedText = textToSave
                                    saving = false
                                }
                            },
                        ) {
                            if (saving) {
                                CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Save, "Save file", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Default.Search, "Find and replace")
                    }
                    IconButton(onClick = { splitScreenOpen = !splitScreenOpen }) {
                        Icon(
                            Icons.Default.VerticalSplit,
                            contentDescription = "Toggle split editor",
                            tint = if (splitScreenOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { menuOpen = !menuOpen }) {
                        Icon(Icons.Default.MoreVert, "Editor actions")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Command palette") },
                            onClick = {
                                menuOpen = false
                                commandPaletteOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (splitScreenOpen) "Close split editor" else "Split editor (Portrait stacked)") },
                            onClick = {
                                splitScreenOpen = !splitScreenOpen
                                menuOpen = false
                            },
                        )
                        if (workspaceDir != null) {
                            DropdownMenuItem(
                                text = { Text("Project search & replace") },
                                onClick = {
                                    menuOpen = false
                                    globalSearchOpen = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (wordWrap) "Word wrap: On" else "Word wrap: Off") },
                            onClick = { wordWrap = !wordWrap; menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Symbol outline") },
                            onClick = {
                                menuOpen = false
                                outlineOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Go to line") },
                            onClick = {
                                menuOpen = false
                                goToLineOpen = true
                            },
                        )
                        if (openTabs.size > 1) {
                            DropdownMenuItem(
                                text = { Text("Close other tabs") },
                                onClick = {
                                    menuOpen = false
                                    onCloseOtherTabs(filePath)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Close all tabs") },
                                onClick = {
                                    menuOpen = false
                                    onCloseAllTabs()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (openTabs.size > 1) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        openTabs.forEach { tabPath ->
                            val isSelected = tabPath == filePath
                            val fileName = tabPath.substringAfterLast('/')
                            Surface(
                                onClick = { onSelectTab(tabPath) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                                modifier = Modifier.height(32.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = fileName,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (isSelected && dirty) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                                        )
                                    }
                                    IconButton(
                                        onClick = { onCloseTab(tabPath) },
                                        modifier = Modifier.size(20.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Close tab",
                                            modifier = Modifier.size(13.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (searchOpen) {
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(
                        Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = find,
                                onValueChange = { find = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(if (searchInSelection) "Find in selection" else "Find") },
                            )
                            IconButton(
                                onClick = {
                                    searchInSelection = !searchInSelection
                                    if (searchInSelection) {
                                        selectionRangeAtSearch = if (value.selection.length > 0) value.selection else null
                                    } else {
                                        selectionRangeAtSearch = null
                                    }
                                }
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (searchInSelection) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, if (searchInSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Text(
                                        "Sel",
                                        fontSize = 11.sp,
                                        fontWeight = if (searchInSelection) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                        color = if (searchInSelection) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = ::findPrevious, enabled = find.isNotBlank()) {
                                Icon(Icons.Default.KeyboardArrowUp, "Previous match")
                            }
                            IconButton(onClick = ::findNext, enabled = find.isNotBlank()) {
                                Icon(Icons.Default.KeyboardArrowDown, "Next match")
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = replace,
                                onValueChange = { replace = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("Replace with") },
                            )
                            TextButton(onClick = ::replaceCurrent, enabled = find.isNotBlank() && !readOnly) { Text("Replace") }
                            TextButton(onClick = ::replaceAll, enabled = find.isNotBlank() && !readOnly) { Text("All") }
                        }
                    }
                }
            }

            if (readOnly && !loading) {
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "Read-only preview. This file is too large to edit safely.",
                        Modifier.padding(11.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!splitScreenOpen) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        EditorPane(
                            value = value,
                            onValueChange = {
                                if (!readOnly) {
                                    value = it
                                    bracketPair = findBracketPair(it.text, it.selection.start)
                                }
                            },
                            lineNumbers = lineNumbers,
                            vertical = vertical,
                            horizontal = horizontal,
                            wordWrap = wordWrap,
                            readOnly = readOnly,
                            bracketPair = bracketPair,
                        )
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    // Pane 1: Top pane
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        } else {
                            EditorPane(
                                value = value,
                                onValueChange = {
                                    if (!readOnly) {
                                        value = it
                                        bracketPair = findBracketPair(it.text, it.selection.start)
                                    }
                                },
                                lineNumbers = lineNumbers,
                                vertical = vertical,
                                horizontal = horizontal,
                                wordWrap = wordWrap,
                                readOnly = readOnly,
                                bracketPair = bracketPair,
                            )
                        }
                    }

                    // Divider bar (Portrait 1 column stacked separator)
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Default.VerticalSplit,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Pane 2: " + secondaryFilePath.substringAfterLast('/'),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (openTabs.size > 1) {
                                    var switchMenuOpen by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(
                                            onClick = { switchMenuOpen = true },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text("Switch tab", fontSize = 11.sp)
                                        }
                                        DropdownMenu(
                                            expanded = switchMenuOpen,
                                            onDismissRequest = { switchMenuOpen = false },
                                        ) {
                                            openTabs.forEach { tab ->
                                                DropdownMenuItem(
                                                    text = { Text(tab.substringAfterLast('/'), fontSize = 12.sp) },
                                                    onClick = {
                                                        switchMenuOpen = false
                                                        secondaryFilePath = tab
                                                        try {
                                                            val f = File(tab)
                                                            if (f.exists() && f.isFile) {
                                                                secondaryValue = TextFieldValue(f.readText())
                                                            }
                                                        } catch (_: Exception) {}
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { splitScreenOpen = false },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close Split",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // Pane 2: Bottom pane
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        EditorPane(
                            value = secondaryValue,
                            onValueChange = { secondaryValue = it },
                            lineNumbers = secondaryLineNumbers,
                            vertical = secondaryVertical,
                            horizontal = secondaryHorizontal,
                            wordWrap = wordWrap,
                            readOnly = false,
                            bracketPair = null,
                        )
                    }
                }
            }

            if (!readOnly) {
                CodingToolbar(
                    value = value,
                    onValueChange = {
                        value = it
                        bracketPair = findBracketPair(it.text, it.selection.start)
                    },
                )
            }

            Text(
                (if (splitScreenOpen) "[Split] " else "") + statusText(value, dirty, bracketPair) + (if (splitScreenOpen) " | Pane 2: " + secondaryFilePath.substringAfterLast('/') else ""),
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorPane(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    lineNumbers: String,
    vertical: androidx.compose.foundation.ScrollState,
    horizontal: androidx.compose.foundation.ScrollState,
    wordWrap: Boolean,
    readOnly: Boolean,
    bracketPair: BracketPair?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                .verticalScroll(vertical, enabled = false),
        ) {
            Text(
                lineNumbers,
                Modifier.padding(top = 8.dp, end = 6.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (wordWrap) Modifier.verticalScroll(vertical)
                    else Modifier.horizontalScroll(horizontal).verticalScroll(vertical),
                ),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wordWrap) Modifier else Modifier.widthIn(min = 720.dp))
                    .padding(start = 12.dp, top = 8.dp, end = 24.dp, bottom = 24.dp),
                enabled = !readOnly,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = CodeSyntaxVisualTransformation(
                    keywordColor = MaterialTheme.colorScheme.primary,
                    stringColor = MaterialTheme.colorScheme.tertiary,
                    commentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    numberColor = MaterialTheme.colorScheme.secondary,
                    bracketPair = bracketPair,
                ),
            )
        }
    }
}

private fun statusText(value: TextFieldValue, dirty: Boolean, bracketPair: BracketPair?): String {
    val line = value.text.take(value.selection.start.coerceIn(0, value.text.length)).count { it == '\n' } + 1
    return buildString {
        append("Ln ")
        append(line)
        append("  ·  ")
        append(value.text.length)
        append(" chars")
        if (dirty) append("  ·  Unsaved")
        if (bracketPair != null) {
            append("  ·  Match line ")
            append(value.text.take(bracketPair.match).count { it == '\n' } + 1)
        }
    }
}

private fun extractEditorSymbols(text: String, filePath: String): List<EditorSymbol> {
    val semantic = SemanticCodeEngine.parseFile(filePath, text)
    if (semantic.symbols.isNotEmpty()) {
        return semantic.symbols.map {
            EditorSymbol(it.name, it.line, it.characterOffset, it.kind.name)
        }
    }
    val patterns = listOf(
        Regex("^\\s*(?:data\\s+|enum\\s+)?(?:class|interface|object)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
        Regex("^\\s*(?:suspend\\s+)?fun\\s+([A-Za-z_][A-Za-z0-9_]*)"),
        Regex("^\\s*(?:def|async\\s+def)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
        Regex("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_][A-Za-z0-9_]*)"),
    )
    val result = mutableListOf<EditorSymbol>()
    var offset = 0
    text.split('\n').forEachIndexed { index, line ->
        patterns.asSequence()
            .mapNotNull { it.find(line)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            ?.let { result += EditorSymbol(it, index + 1, offset, "DECLARATION") }
        offset += line.length + 1
    }
    return result
}

private fun findBracketPair(text: String, cursor: Int): BracketPair? {
    val candidate = sequenceOf(cursor, cursor - 1)
        .filter { it in text.indices }
        .firstOrNull { text[it] in "()[]{}" } ?: return null
    val opening = text[candidate] in "([{"
    val current = text[candidate]
    val counterpart = when (current) {
        '(' -> ')'
        ')' -> '('
        '[' -> ']'
        ']' -> '['
        '{' -> '}'
        '}' -> '{'
        else -> return null
    }
    var depth = 0
    if (opening) {
        for (i in candidate until text.length) {
            if (text[i] == current) depth++
            if (text[i] == counterpart) {
                depth--
                if (depth == 0) return BracketPair(candidate, i)
            }
        }
    } else {
        for (i in candidate downTo 0) {
            if (text[i] == current) depth++
            if (text[i] == counterpart) {
                depth--
                if (depth == 0) return BracketPair(candidate, i)
            }
        }
    }
    return null
}

private class CodeSyntaxVisualTransformation(
    private val keywordColor: Color,
    private val stringColor: Color,
    private val commentColor: Color,
    private val numberColor: Color,
    private val bracketPair: BracketPair?,
) : VisualTransformation {
    private val keywords = setOf(
        "class", "interface", "object", "fun", "data", "enum", "sealed", "public", "private",
        "protected", "internal", "override", "suspend", "return", "if", "else", "when", "for",
        "while", "do", "try", "catch", "finally", "throw", "val", "var", "const", "let",
        "function", "async", "await", "import", "from", "export", "package", "extends", "implements",
        "new", "this", "super", "true", "false", "null", "def", "lambda",
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val builder = AnnotatedString.Builder()
        var global = 0
        source.split('\n').forEachIndexed { lineIndex, line ->
            var i = 0
            while (i < line.length) {
                val absolute = global + i
                val ch = line[i]
                if (ch == '#' || (ch == '/' && i + 1 < line.length && line[i + 1] == '/')) {
                    builder.pushStyle(SpanStyle(color = commentColor))
                    builder.append(line.substring(i))
                    builder.pop()
                    break
                }
                if (ch == '"' || ch == '\'') {
                    val start = i
                    val quote = ch
                    i++
                    while (i < line.length) {
                        if (line[i] == '\\') {
                            i = (i + 2).coerceAtMost(line.length)
                        } else if (line[i] == quote) {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                    builder.pushStyle(SpanStyle(color = stringColor))
                    builder.append(line.substring(start, i))
                    builder.pop()
                    continue
                }
                if (ch.isDigit()) {
                    val start = i
                    while (i < line.length && (line[i].isDigit() || line[i] == '.' || line[i] in "abcdefABCDEFxX")) i++
                    builder.pushStyle(SpanStyle(color = numberColor))
                    builder.append(line.substring(start, i))
                    builder.pop()
                    continue
                }
                if (ch.isLetter() || ch == '_') {
                    val start = i
                    while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                    val word = line.substring(start, i)
                    if (word in keywords) {
                        builder.pushStyle(SpanStyle(color = keywordColor))
                    }
                    builder.append(word)
                    if (word in keywords) builder.pop()
                    continue
                }
                if (bracketPair != null && (absolute == bracketPair.cursor || absolute == bracketPair.match)) {
                    builder.pushStyle(
                        SpanStyle(
                            background = Color(0x664F72FF),
                            color = Color.Unspecified,
                        ),
                    )
                    builder.append(ch)
                    builder.pop()
                } else {
                    builder.append(ch)
                }
                i++
            }
            if (lineIndex < source.count { it == '\n' }) builder.append('\n')
            global += line.length + 1
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
