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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
        Screen.HEX_EDITOR -> HexEditorScreen(onBack = { currentScreen = Screen.DASHBOARD })
    }
}

@Composable
fun DashboardScreen(onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val hasStoragePermission = remember {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Title Card
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

        // Tool Option Cards
        ToolCard(
            title = "PAK Unpack",
            subtitle = "PAK -> files",
            icon = Icons.Default.ArrowDownward,
            onClick = { onNavigate(Screen.UNPACK) }
        )

        ToolCard(
            title = "PAK Repack",
            subtitle = "EDITTED -> PAK",
            icon = Icons.Default.ArrowUpward,
            onClick = { onNavigate(Screen.REPACK) }
        )

        ToolCard(
            title = "LUA Decompile",
            subtitle = "bytecode -> Lua source",
            icon = Icons.Default.Code,
            onClick = { onNavigate(Screen.LUA_DECOMPILE) }
        )

        ToolCard(
            title = "LUA Compile",
            subtitle = "Lua source -> bytecode",
            icon = Icons.Default.Terminal,
            onClick = { onNavigate(Screen.LUA_COMPILE) }
        )

        ToolCard(
            title = "Hex Editor (.uasset / .uexp)",
            subtitle = "Inspect & Replace byte values",
            icon = Icons.Default.Build,
            onClick = { onNavigate(Screen.HEX_EDITOR) }
        )

        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "TG @byrj6",
            color = Color.DarkGray,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
fun ToolCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(Color(0xFF283530), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
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

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("PAK UNPACK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(selected?.name ?: "No archives in /Original", color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp)).padding(10.dp)
        ) {
            LazyColumn(state = listState) {
                items(logs) { log -> Text(log, color = if (log.startsWith("[SUCCESS]") || log.startsWith("💾")) Color(0xFF00E676) else Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }

        Button(
            onClick = {
                selected?.let {
                    isBusy = true
                    scope.launch {
                        PakEngine.unpackArchive(it, callback)
                        isBusy = false
                    }
                }
            },
            enabled = !isBusy && selected != null,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) {
            Text("START UNPACK", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose PAK File") },
            text = {
                LazyColumn {
                    items(files) { f ->
                        Text(f.name, modifier = Modifier.fillMaxWidth().clickable { selected = f; showPicker = false }.padding(10.dp))
                    }
                }
            },
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

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("PAK REPACK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(selected ?: "No folder in /Unpack", color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp)).padding(10.dp)
        ) {
            LazyColumn(state = listState) {
                items(logs) { log -> Text(log, color = if (log.startsWith("📦") || log.startsWith("[FINISHED]")) Color(0xFF00E676) else Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }

        Button(
            onClick = {
                selected?.let {
                    isBusy = true
                    scope.launch {
                        PakEngine.replaceAndRepack(it, callback)
                        isBusy = false
                    }
                }
            },
            enabled = !isBusy && selected != null,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) {
            Text("REPLACE & REPACK TO .PAK", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose Target Unpack Folder") },
            text = {
                LazyColumn {
                    items(folders) { f ->
                        Text(f, modifier = Modifier.fillMaxWidth().clickable { selected = f; showPicker = false }.padding(10.dp))
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
fun LuaDecompileScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var luaFiles by remember {
        mutableStateOf(PakEngine.dirUnpack.walkTopDown().filter { it.extension.lowercase() in listOf("lua", "luac") || it.name.contains("lua", true) }.toList())
    }
    var selectedFile by remember { mutableStateOf(luaFiles.firstOrNull()) }
    var showPicker by remember { mutableStateOf(false) }
    var decompiledCode by remember { mutableStateOf("-- Select Lua File & Tap Decompile") }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("LUA DECOMPILE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Row(modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selectedFile?.name ?: "No Lua file found in Unpack/", color = Color(0xFF00E676))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }

        OutlinedTextField(
            value = decompiledCode,
            onValueChange = { decompiledCode = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    selectedFile?.let { file ->
                        scope.launch { decompiledCode = PakEngine.decompileLua(file) }
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
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1))
            ) { Text("SAVE TO EDITOR", fontWeight = FontWeight.Bold) }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose Lua Bytecode File") },
            text = {
                LazyColumn {
                    items(luaFiles) { f ->
                        Text(f.name, modifier = Modifier.fillMaxWidth().clickable { selectedFile = f; showPicker = false }.padding(10.dp))
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
fun LuaCompileScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var scriptName by remember { mutableStateOf("BRPlayerCharacterBase.lua") }
    var luaSource by remember {
        mutableStateOf("-- BRPlayerCharacterBase.lua Source\nlocal Base = {}\n\nfunction Base:InitCharacter()\n    print('Character Initialized')\nend\n\nreturn Base")
    }
    var status by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("LUA COMPILE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        OutlinedTextField(
            value = scriptName,
            onValueChange = { scriptName = it },
            label = { Text("File Name in /Editor") },
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
                    val saved = PakEngine.saveCompiledLua(scriptName, luaSource)
                    status = "Saved to: ${saved.absolutePath}"
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) {
            Text("COMPILE & WRITE TO EDITOR", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HexEditorScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var assetFiles by remember {
        mutableStateOf(PakEngine.dirUnpack.walkTopDown().filter { it.extension in listOf("uasset", "uexp") }.toList())
    }
    var selectedFile by remember { mutableStateOf(assetFiles.firstOrNull()) }
    var showPicker by remember { mutableStateOf(false) }

    var searchStr by remember { mutableStateOf("") }
    var replaceStr by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White) }
            Text("HEX & BYTE EDITOR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Row(modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selectedFile?.name ?: "No .uasset/.uexp in Unpack/", color = Color(0xFF00E676))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }

        OutlinedTextField(
            value = searchStr,
            onValueChange = { searchStr = it },
            label = { Text("Find Text / Hex String") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = replaceStr,
            onValueChange = { replaceStr = it },
            label = { Text("Replace With (Text/Hex)") },
            modifier = Modifier.fillMaxWidth()
        )

        if (status.isNotEmpty()) {
            Text(status, color = Color(0xFF00E676), fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                selectedFile?.let { file ->
                    scope.launch {
                        try {
                            var bytes = file.readBytes()
                            val sBytes = searchStr.toByteArray(Charsets.UTF_8)
                            val rBytes = replaceStr.toByteArray(Charsets.UTF_8)
                            val contentStr = String(bytes, Charsets.ISO_8859_1)
                            val findStr = String(sBytes, Charsets.ISO_8859_1)
                            val repStr = String(rBytes, Charsets.ISO_8859_1)

                            if (contentStr.contains(findStr)) {
                                val patched = contentStr.replace(findStr, repStr)
                                val dest = File(PakEngine.dirEditor, file.name)
                                dest.writeBytes(patched.toByteArray(Charsets.ISO_8859_1))
                                status = "Success: Saved edited binary to /Editor/${file.name}"
                            } else {
                                status = "Value not found in binary stream."
                            }
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) {
            Text("APPLY PATCH & SAVE TO EDITOR", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose Asset File") },
            text = {
                LazyColumn {
                    items(assetFiles) { f ->
                        Text(f.name, modifier = Modifier.fillMaxWidth().clickable { selectedFile = f; showPicker = false }.padding(10.dp))
                    }
                }
            },
            confirmButton = {}
        )
    }
}
