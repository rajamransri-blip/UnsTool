package com.upstool.paktool

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

enum class Screen { DASHBOARD, UNPACK, REPACK, LUA_DECOMPILE, LUA_COMPILE, HEX_EDITOR }

data class SearchOccurrence(val offset: Int, val originalBytes: ByteArray, val previewText: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PakEngine.initPython(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F0F0F)) {
                    MainApp()
                }
            }
        }
    }
}

@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }

    when (currentScreen) {
        Screen.DASHBOARD -> DashboardScreen(onNavigate = { currentScreen = it })
        Screen.UNPACK -> UnpackScreen(onBack = { currentScreen = Screen.DASHBOARD })
        Screen.REPACK -> RepackScreen(onBack = { currentScreen = Screen.DASHBOARD })
        Screen.LUA_DECOMPILE -> LuaDecompileScreen(onBack = { currentScreen = Screen.DASHBOARD })
        Screen.LUA_COMPILE -> LuaCompileScreen(onBack = { currentScreen = Screen.DASHBOARD })
        Screen.HEX_EDITOR -> SmartHexEditorScreen(onBack = { currentScreen = Screen.DASHBOARD })
    }
}

@Composable
fun DashboardScreen(onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val hasStoragePermission = remember {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("RJTOOL v1.0.59", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("TG @byrj6", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF00E676)))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (hasStoragePermission.value) "Storage access granted" else "Grant Storage Access",
                            color = if (hasStoragePermission.value) Color(0xFF00E676) else Color(0xFFFF5252),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                IconButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasStoragePermission.value) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                }) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF00E676))
                }
            }
        }

        ToolCard("PAK Unpack", "PAK -> files", Icons.Default.ArrowDownward) { onNavigate(Screen.UNPACK) }
        ToolCard("PAK Repack", "EDITTED -> PAK", Icons.Default.ArrowUpward) { onNavigate(Screen.REPACK) }
        ToolCard("LUA Decompile", "bytecode -> Lua source", Icons.Default.Code) { onNavigate(Screen.LUA_DECOMPILE) }
        ToolCard("LUA Compile", "Lua source -> bytecode", Icons.Default.Terminal) { onNavigate(Screen.LUA_COMPILE) }
        ToolCard("Hex Editor (.uasset / .uexp)", "Smart Offset Inspector & Patcher", Icons.Default.Build) { onNavigate(Screen.HEX_EDITOR) }

        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "TG @byrj6", color = Color.DarkGray, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun ToolCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(46.dp).background(Color(0xFF283530), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnpackScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var files by remember { mutableStateOf(PakEngine.dirOriginal.listFiles()?.filter { it.extension in listOf("pak", "obb") || it.name.contains("patch") } ?: emptyList()) }
    var selected by remember { mutableStateOf(files.firstOrNull()) }
    var showPicker by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf("[SYSTEM] Unpack Engine Ready.")) }
    var isBusy by remember { mutableStateOf(false) }

    val callback = remember { object : TerminalCallback { override fun onLog(line: String) { scope.launch { logs = logs + line } } } }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("PAK UNPACK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Row(modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selected?.name ?: "No archives in /Original", color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp)).padding(10.dp)) {
            LazyColumn(state = listState) {
                items(logs) { log -> Text(log, color = if (log.startsWith("[SUCCESS]") || log.startsWith("💾")) Color(0xFF00E676) else Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        Button(
            onClick = {
                selected?.let {
                    isBusy = true
                    scope.launch { PakEngine.unpackArchive(it, callback); isBusy = false }
                }
            },
            enabled = !isBusy && selected != null,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) { Text("START UNPACK", color = Color.Black, fontWeight = FontWeight.Bold) }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose Target Archive") },
            text = { LazyColumn { items(files) { f -> Text(f.name, modifier = Modifier.fillMaxWidth().clickable { selected = f; showPicker = false }.padding(10.dp)) } } },
            confirmButton = {}
        )
    }
}

@Composable
fun RepackScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var folders by remember { mutableStateOf(PakEngine.dirUnpack.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()) }
    var selected by remember { mutableStateOf(folders.firstOrNull()) }
    var showPicker by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf("[SYSTEM] Repack Engine Ready.")) }
    var isBusy by remember { mutableStateOf(false) }

    val callback = remember { object : TerminalCallback { override fun onLog(line: String) { scope.launch { logs = logs + line } } } }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("PAK REPACK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Row(modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selected ?: "No folder in /Unpack", color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp)).padding(10.dp)) {
            LazyColumn(state = listState) {
                items(logs) { log -> Text(log, color = if (log.startsWith("📦") || log.startsWith("[FINISHED]")) Color(0xFF00E676) else Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        Button(
            onClick = {
                selected?.let {
                    isBusy = true
                    scope.launch { PakEngine.replaceAndRepack(it, callback); isBusy = false }
                }
            },
            enabled = !isBusy && selected != null,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) { Text("REPLACE & REPACK TO .PAK", color = Color.Black, fontWeight = FontWeight.Bold) }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose Target Folder") },
            text = { LazyColumn { items(folders) { f -> Text(f, modifier = Modifier.fillMaxWidth().clickable { selected = f; showPicker = false }.padding(10.dp)) } } },
            confirmButton = {}
        )
    }
}

@Composable
fun LuaDecompileScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var luaFiles by remember { mutableStateOf(PakEngine.getAllLuaFiles()) }
    var selectedFile by remember { mutableStateOf(luaFiles.firstOrNull()) }
    var showPicker by remember { mutableStateOf(false) }
    var decompiledCode by remember { mutableStateOf("-- Select Lua File & Tap Decompile") }
    var status by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("LUA DECOMPILE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Row(modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selectedFile?.name ?: "No Lua in /Lua/Original or /Unpack", color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }

        OutlinedTextField(
            value = decompiledCode,
            onValueChange = { decompiledCode = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
        )

        if (status.isNotEmpty()) {
            Text(status, color = Color(0xFF00E676), fontSize = 12.sp)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    selectedFile?.let { file ->
                        scope.launch {
                            decompiledCode = PakEngine.decompileLua(file)
                            status = "Saved to: /sdcard/Upstool/Lua/Decompiled/${file.name}"
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
            ) { Text("DECOMPILE", color = Color.Black, fontWeight = FontWeight.Bold) }

            Button(
                onClick = {
                    selectedFile?.let { file ->
                        scope.launch {
                            PakEngine.saveCompiledLua(file.name, decompiledCode)
                            status = "Pushed to /Editor & /Lua/Compiled!"
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1))
            ) { Text("SAVE TO /EDITOR", fontWeight = FontWeight.Bold) }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose Lua File") },
            text = { LazyColumn { items(luaFiles) { f -> Text(f.name, modifier = Modifier.fillMaxWidth().clickable { selectedFile = f; showPicker = false }.padding(10.dp)) } } },
            confirmButton = {}
        )
    }
}

@Composable
fun LuaCompileScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var scriptName by remember { mutableStateOf("BRPlayerCharacterBase.lua") }
    var luaSource by remember {
        mutableStateOf("-- BRPlayerCharacterBase.lua Script\nlocal Base = {}\n\nfunction Base:InitCharacterBase()\n    print('Character Base Configured')\nend\n\nreturn Base")
    }
    var status by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("LUA COMPILE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        OutlinedTextField(
            value = scriptName,
            onValueChange = { scriptName = it },
            label = { Text("Save File Name") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = luaSource,
            onValueChange = { luaSource = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
        )

        if (status.isNotEmpty()) {
            Text(status, color = Color(0xFF00E676), fontSize = 12.sp)
        }

        Button(
            onClick = {
                scope.launch {
                    PakEngine.saveCompiledLua(scriptName, luaSource)
                    status = "Saved to /sdcard/Upstool/Lua/Compiled and copied to /Editor!"
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) { Text("SAVE TO /COMPILED & /EDITOR", color = Color.Black, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun SmartHexEditorScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var assetFiles by remember {
        mutableStateOf(PakEngine.dirUnpack.walkTopDown().filter { it.extension in listOf("uasset", "uexp") }.toList())
    }
    var selectedFile by remember { mutableStateOf(assetFiles.firstOrNull()) }
    var showPicker by remember { mutableStateOf(false) }

    var searchInput by remember { mutableStateOf("BP_PlayerPawn") }
    var replaceInput by remember { mutableStateOf("") }
    var occurrences by remember { mutableStateOf<List<SearchOccurrence>>(emptyList()) }
    var selectedOccurrence by remember { mutableStateOf<SearchOccurrence?>(null) }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(14.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("SMART HEX & BYTE EDITOR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        // File Picker Dropdown
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Row(modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selectedFile?.name ?: "No .uasset/.uexp in /Unpack", color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }

        // Find Box
        OutlinedTextField(
            value = searchInput,
            onValueChange = { searchInput = it },
            label = { Text("Find Value (String or Hex e.g. 42 50)") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = {
                    selectedFile?.let { file ->
                        scope.launch {
                            val bytes = file.readBytes()
                            val sBytes = if (searchInput.contains(" ")) {
                                searchInput.split(" ").mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                            } else searchInput.toByteArray(Charsets.UTF_8)

                            val matches = mutableListOf<SearchOccurrence>()
                            if (sBytes.isNotEmpty()) {
                                for (i in 0..(bytes.size - sBytes.size)) {
                                    var matched = true
                                    for (j in sBytes.indices) {
                                        if (bytes[i + j] != sBytes[j]) { matched = false; break }
                                    }
                                    if (matched) {
                                        val previewLen = minOf(16, bytes.size - i)
                                        val preview = String(bytes.sliceArray(i until i + previewLen), Charsets.ISO_8859_1)
                                        matches.add(SearchOccurrence(i, sBytes, preview))
                                        if (matches.size >= 25) break
                                    }
                                }
                            }
                            occurrences = matches
                            status = if (matches.isNotEmpty()) "Found ${matches.size} occurrences in binary." else "No matches found."
                        }
                    }
                }) { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF00E676)) }
            }
        )

        // Matches Box
        if (occurrences.isNotEmpty()) {
            Text("FOUND OCCURRENCES (TAP TO INSPECT/EDIT):", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
            ) {
                LazyColumn(modifier = Modifier.padding(6.dp)) {
                    items(occurrences) { occ ->
                        val hexOffset = "0x" + occ.offset.toString(16).uppercase().padStart(6, '0')
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedOccurrence = occ
                                    replaceInput = searchInput
                                }
                                .background(if (selectedOccurrence?.offset == occ.offset) Color(0xFF283530) else Color.Transparent)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(hexOffset, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(occ.previewText.replace("\n", " "), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Replace Field
        OutlinedTextField(
            value = replaceInput,
            onValueChange = { replaceInput = it },
            label = { Text("Replace Value (Safe padded)") },
            modifier = Modifier.fillMaxWidth()
        )

        if (status.isNotEmpty()) {
            Text(status, color = if (status.startsWith("Success")) Color(0xFF00E676) else Color(0xFFFFB74D), fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Action Buttons
        Button(
            onClick = {
                selectedFile?.let { file ->
                    scope.launch {
                        try {
                            var bytes = file.readBytes()
                            val sBytes = if (searchInput.contains(" ")) {
                                searchInput.split(" ").mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                            } else searchInput.toByteArray(Charsets.UTF_8)

                            var rBytes = if (replaceInput.contains(" ")) {
                                replaceInput.split(" ").mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                            } else replaceInput.toByteArray(Charsets.UTF_8)

                            // Auto-pad with 0x00 to preserve UE4 offsets
                            if (rBytes.size < sBytes.size) {
                                rBytes = rBytes + ByteArray(sBytes.size - rBytes.size) { 0x00 }
                            } else if (rBytes.size > sBytes.size) {
                                rBytes = rBytes.sliceArray(0 until sBytes.size)
                            }

                            val targetOffsets = if (selectedOccurrence != null) {
                                listOf(selectedOccurrence!!.offset)
                            } else occurrences.map { it.offset }

                            for (off in targetOffsets) {
                                for (j in rBytes.indices) {
                                    bytes[off + j] = rBytes[j]
                                }
                            }

                            val dest = File(PakEngine.dirEditor, file.name)
                            dest.writeBytes(bytes)
                            status = "Success: Patched & saved to /Editor/${file.name}"
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                        }
                    }
                }
            },
            enabled = selectedFile != null && occurrences.isNotEmpty() && replaceInput.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) { Text("APPLY PATCH & SAVE TO /EDITOR", color = Color.Black, fontWeight = FontWeight.Bold) }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose Asset File") },
            text = { LazyColumn { items(assetFiles) { f -> Text(f.name, modifier = Modifier.fillMaxWidth().clickable { selectedFile = f; showPicker = false; occurrences = emptyList() }.padding(10.dp)) } } },
            confirmButton = {}
        )
    }
}
