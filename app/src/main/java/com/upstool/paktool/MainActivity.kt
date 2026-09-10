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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PakEngine.initPython(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
                    TerminalUnpackerScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalUnpackerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var originalFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var unpackedFolders by remember { mutableStateOf<List<String>>(emptyList()) }

    var terminalLogs by remember { mutableStateOf<List<String>>(listOf("[SYSTEM] Upstool Core Ready. Stream Splitter Active.")) }
    var isBusy by remember { mutableStateOf(false) }

    var showOriginalDropdown by remember { mutableStateOf(false) }
    var showRepackDropdown by remember { mutableStateOf(false) }

    fun addLog(msg: String) {
        terminalLogs = terminalLogs + msg
    }

    fun refreshFiles() {
        val originals = PakEngine.dirOriginal.listFiles()
            ?.filter { it.isFile && (it.extension in listOf("pak", "obb") || it.name.contains("patch")) }
            ?.toList() ?: emptyList()
        originalFiles = originals
        if (selectedFile == null && originals.isNotEmpty()) {
            selectedFile = originals.first()
        }
        val unp = PakEngine.dirUnpack.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        unpackedFolders = unp
    }

    LaunchedEffect(Unit) { refreshFiles() }
    LaunchedEffect(terminalLogs.size) {
        if (terminalLogs.isNotEmpty()) {
            listState.animateScrollToItem(terminalLogs.size - 1)
        }
    }

    val hasStoragePermission = remember {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else true
        }
    }

    val callback = remember {
        object : TerminalCallback {
            override fun onLog(line: String) {
                scope.launch { addLog(line) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UPSTOOL PAK ENGINE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) },
                actions = {
                    IconButton(onClick = { refreshFiles(); addLog("[STATUS] Storage scanned.") }, enabled = !isBusy) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF00E5FF))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF141414))
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!hasStoragePermission.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1212))) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("All Files Access Required", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                        ) { Text("Allow", fontSize = 11.sp) }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF181818)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("SELECT TARGET ARCHIVE (Original Folder):", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF222222), RoundedCornerShape(6.dp))
                            .clickable { showOriginalDropdown = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedFile?.name ?: if (originalFiles.isEmpty()) "No .pak/.obb in /Original" else "Choose File",
                            color = if (selectedFile != null) Color(0xFF00E5FF) else Color.LightGray,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF000000), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(terminalLogs) { log ->
                        val logColor = when {
                            log.startsWith("[ERROR]") || log.startsWith("[REPACK ERROR]") -> Color(0xFFFF5252)
                            log.startsWith("[SUCCESS]") || log.startsWith("[FINISHED]") -> Color(0xFF00E676)
                            log.startsWith("[WARN]") || log.startsWith("[UE4]") -> Color(0xFFFFB74D)
                            log.startsWith("💾") -> Color(0xFF00E5FF)
                            log.startsWith("📦") || log.startsWith("🔄") -> Color(0xFF80D8FF)
                            else -> Color(0xFFE0E0E0)
                        }
                        Text(
                            text = log,
                            color = logColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        selectedFile?.let { target ->
                            isBusy = true
                            addLog("[START] Extracting archive: ${target.name}")
                            scope.launch {
                                val ok = PakEngine.unpackArchive(target, callback)
                                refreshFiles()
                                isBusy = false
                                addLog(if (ok) "[COMPLETE] Files saved to /sdcard/Upstool/Unpack/${target.nameWithoutExtension}" else "[FAILED] Extraction failed.")
                            }
                        }
                    },
                    enabled = !isBusy && selectedFile != null,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1))
                ) {
                    Text("UNPACK TO TREE", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { showRepackDropdown = true },
                    enabled = !isBusy && unpackedFolders.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
                ) {
                    Text("REPACK CHOSEN", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showOriginalDropdown) {
        AlertDialog(
            onDismissRequest = { showOriginalDropdown = false },
            title = { Text("Select Archive to Unpack") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(originalFiles) { file ->
                        Text(
                            text = file.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedFile = file
                                    showOriginalDropdown = false
                                    addLog("[SELECTED] ${file.name}")
                                }
                                .padding(vertical = 12.dp),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showOriginalDropdown = false }) { Text("Cancel") } }
        )
    }

    if (showRepackDropdown) {
        AlertDialog(
            onDismissRequest = { showRepackDropdown = false },
            title = { Text("Select Folder to Replace & Repack") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(unpackedFolders) { folderName ->
                        Text(
                            text = folderName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showRepackDropdown = false
                                    isBusy = true
                                    addLog("[START] Repacking: $folderName")
                                    scope.launch {
                                        val ok = PakEngine.replaceAndRepack(folderName, callback)
                                        refreshFiles()
                                        isBusy = false
                                        addLog(if (ok) "[COMPLETE] Repacked -> /sdcard/Upstool/Repack/$folderName.pak" else "[FAILED] Repack failed.")
                                    }
                                }
                                .padding(vertical = 12.dp),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showRepackDropdown = false }) { Text("Cancel") } }
        )
    }
}
