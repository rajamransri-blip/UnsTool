package com.upstool.paktool

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0D0D)) {
                    UpstoolScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpstoolScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var editorFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var unpackedFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    var repackedFiles by remember { mutableStateOf<List<String>>(emptyList()) }

    var isBusy by remember { mutableStateOf(false) }
    var statusLog by remember { mutableStateOf("Ready") }
    var currentAction by remember { mutableStateOf("") }

    var showUnpackDialog by remember { mutableStateOf(false) }
    var showRepackDialog by remember { mutableStateOf(false) }

    fun refresh() {
        val orig = PakEngine.dirOriginal.listFiles()
            ?.filter { it.isFile && (it.extension in listOf("pak", "obb") || it.name.contains("patch")) }
            ?.toList() ?: emptyList()

        val edit = PakEngine.dirEditor.listFiles()?.map { it.name } ?: emptyList()
        val unp = PakEngine.dirUnpack.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        val rep = PakEngine.dirRepack.listFiles()?.map { it.name } ?: emptyList()

        originalFiles = orig
        editorFiles = edit
        unpackedFolders = unp
        repackedFiles = rep
    }

    LaunchedEffect(Unit) { refresh() }

    val hasStoragePermission = remember {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UPSTOOL PAK ENGINE", fontWeight = FontWeight.Black) },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !isBusy) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF00E5FF))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF141414))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasStoragePermission.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1212))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Storage Permission Needed", color = Color.White, modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                        ) { Text("Fix") }
                    }
                }
            }

            AnimatedVisibility(visible = isBusy) {
                Column {
                    Text(currentAction, color = Color(0xFF00E5FF), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF00E5FF)
                    )
                }
            }

            FolderStat("Original Pak/Obb", originalFiles.size, "/sdcard/Upstool/Original", Color(0xFF80D8FF))
            FolderStat("Editor Assets", editorFiles.size, "/sdcard/Upstool/Editor", Color(0xFFFFD180))
            FolderStat("Unpacked Hierarchy", unpackedFolders.size, "/sdcard/Upstool/Unpack", Color(0xFFB388FF))
            FolderStat("Repacked Pak", repackedFiles.size, "/sdcard/Upstool/Repack", Color(0xFFA7FFEB))

            Spacer(modifier = Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { showUnpackDialog = true },
                    enabled = !isBusy && originalFiles.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1))
                ) { Text("UNPACK (CHOOSE)", fontWeight = FontWeight.Bold) }

                Button(
                    onClick = { showRepackDialog = true },
                    enabled = !isBusy && unpackedFolders.isNotEmpty() && editorFiles.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
                ) { Text("REPACK (CHOOSE)", fontWeight = FontWeight.Bold) }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF262626), RoundedCornerShape(8.dp))
                    .background(Color(0xFF161616))
                    .padding(12.dp)
            ) {
                Text("> $statusLog", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFEEEEEE))
            }
        }
    }

    if (showUnpackDialog) {
        AlertDialog(
            onDismissRequest = { showUnpackDialog = false },
            title = { Text("Select Archive to Unpack") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(originalFiles) { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                showUnpackDialog = false
                                isBusy = true
                                currentAction = "Unpacking with Zlib: ${file.name}"
                                scope.launch {
                                    val ok = PakEngine.unpackArchive(file)
                                    refresh()
                                    isBusy = false
                                    statusLog = if (ok) "Unpack successful: ${file.name}" else "Unpack failed"
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = null, tint = Color(0xFF00E5FF))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(file.name, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showUnpackDialog = false }) { Text("Cancel") } }
        )
    }

    if (showRepackDialog) {
        AlertDialog(
            onDismissRequest = { showRepackDialog = false },
            title = { Text("Select Folder to Repack") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(unpackedFolders) { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                showRepackDialog = false
                                isBusy = true
                                currentAction = "Injecting Editor files into: $folder"
                                scope.launch {
                                    val ok = PakEngine.replaceAndRepack(folder)
                                    refresh()
                                    isBusy = false
                                    statusLog = if (ok) "Repack completed: $folder.pak" else "Repack failed"
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF69F0AE))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(folder, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showRepackDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun FolderStat(title: String, count: Int, path: String, accent: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181818))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(path, color = Color.Gray, fontSize = 11.sp)
            }
            Text("$count items", color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}
