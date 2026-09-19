package com.rimvydop.redboxpcemulator

import android.content.Intent
import android.provider.OpenableColumns
import android.system.Os
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVMScreen(
    onBack: () -> Unit,
    onVMCreated: (VMModel) -> Unit
) {
    var vmName by remember { mutableStateOf("") }
    var architecture by remember { mutableStateOf("x86_64") }
    var ram by remember { mutableStateOf("4096 MB") }
    var cpuCores by remember { mutableStateOf("4 cores") }

    // Performance tuning
    var performancePreset by remember { mutableStateOf("Balanced") }
    var cpuModel by remember { mutableStateOf("Default") }
    var cpuFlags by remember { mutableStateOf("") }
    var tcgCache by remember { mutableStateOf("256 MB") }
    var multiThreadedTcg by remember { mutableStateOf(true) }
    var machineType by remember { mutableStateOf("pc") }
    var diskInterface by remember { mutableStateOf("AHCI") }
    var displayAdapter by remember { mutableStateOf("Standard VGA") }

    // RedBox blank disk creator.
    var showCreateDiskDialog by remember { mutableStateOf(false) }
    var showCpuOptions by remember { mutableStateOf(false) }
    var showRamOptions by remember { mutableStateOf(false) }
    var showMachineOptions by remember { mutableStateOf(false) }
    var newDiskName by remember { mutableStateOf("RedBoxDisk.img") }
    var newDiskSizeGb by remember { mutableStateOf(32) }

    // Network
    var networkEnabled by remember { mutableStateOf(true) }
    var networkAdapter by remember { mutableStateOf("Realtek RTL8139") }
    var networkMode by remember { mutableStateOf("User (NAT)") }
    var qemuParams by remember { mutableStateOf("") }
    var biosDate by remember { mutableStateOf("Default") }
    var soundCard by remember { mutableStateOf("Intel HDA") }
    var audioBackend by remember { mutableStateOf("Default") }

    var diskImage by remember { mutableStateOf("") }
    var diskImageName by remember { mutableStateOf("") }
    var isoImage by remember { mutableStateOf("") }
    var isoImageName by remember { mutableStateOf("") }
    var driverIsoImage by remember { mutableStateOf("") }
    var driverIsoImageName by remember { mutableStateOf("") }
    var sharedDiskImage by remember { mutableStateOf("") }
    var sharedDiskImageName by remember { mutableStateOf("") }
    var sharedFolderEnabled by remember { mutableStateOf(false) }
    var sharedFolderLastFileName by remember { mutableStateOf("") }

    var architectureMenu by remember { mutableStateOf(false) }
    var ramMenu by remember { mutableStateOf(false) }
    var cpuMenu by remember { mutableStateOf(false) }
    var performancePresetMenu by remember { mutableStateOf(false) }
    var cpuModelMenu by remember { mutableStateOf(false) }
    var tcgCacheMenu by remember { mutableStateOf(false) }
    var machineTypeMenu by remember { mutableStateOf(false) }
    var diskInterfaceMenu by remember { mutableStateOf(false) }
    var displayAdapterMenu by remember { mutableStateOf(false) }
    var networkAdapterMenu by remember { mutableStateOf(false) }
    var networkModeMenu by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()

    val createDiskLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                val finalName = newDiskName
                    .trim()
                    .ifBlank { "RedBoxDisk.img" }
                    .let { if (it.endsWith(".img", ignoreCase = true)) it else "$it.img" }

                val sizeBytes = newDiskSizeGb.toLong() * 1024L * 1024L * 1024L

                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    Os.ftruncate(pfd.fileDescriptor, sizeBytes)
                } ?: throw IllegalStateException("Could not open the new disk file")

                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Some document providers do not expose persistable permissions.
                }

                diskImage = uri.toString()
                diskImageName = finalName

                Toast.makeText(
                    context,
                    "Created $finalName (${newDiskSizeGb} GB)",
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    "Could not create disk: ${error.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val diskPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            diskImage = uri.toString()

            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers do not support persistable permissions.
            }

            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex =
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        diskImageName = cursor.getString(nameIndex)
                    }
                }
            }
        }
    }

    val isoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isoImage = uri.toString()

            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers do not support persistable permissions.
            }

            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex =
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        isoImageName = cursor.getString(nameIndex)
                    }
                }
            }
        }
    }

    val driverIsoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            driverIsoImage = uri.toString()

            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers do not support persistable permissions.
            }

            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex =
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        driverIsoImageName = cursor.getString(nameIndex)
                    }
                }
            }
        }
    }

    val sharedDiskPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            sharedDiskImage = uri.toString()

            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
            }

            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        sharedDiskImageName = cursor.getString(nameIndex)
                    }
                }
            }
        }
    }

    fun importFileToSharedFolder(uri: android.net.Uri) {
        try {
            var displayName = uri.lastPathSegment ?: "shared_file"

            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        displayName = cursor.getString(index) ?: displayName
                    }
                }
            }

            val safeName = File(displayName).name.ifBlank { "shared_file" }
            val sharedFolder = File(context.filesDir, "redbox_shared")
            sharedFolder.mkdirs()

            val destination = File(sharedFolder, safeName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Could not open selected file")

            sharedFolderEnabled = true
            sharedFolderLastFileName = safeName

            // Part 2 uses either a selected second disk OR the VVFAT shared folder.
            sharedDiskImage = ""
            sharedDiskImageName = ""

            Toast.makeText(
                context,
                "Added $safeName to RedBox Shared Folder",
                Toast.LENGTH_SHORT
            ).show()
        } catch (error: Exception) {
            Toast.makeText(
                context,
                "Could not add file: ${error.message ?: "unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val sharedFolderFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importFileToSharedFolder(uri)
        }
    }

    val red = Color(0xFFFF3047)
    val background = Color(0xFF08090C)
    val surface = Color(0xFF111318)
    val surfaceVariant = Color(0xFF181B22)
    val secondaryText = Color(0xFF9EA3AD)

    if (showCpuOptions) {
        AlertDialog(
            onDismissRequest = { showCpuOptions = false },
            title = { Text("CPU Options", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("CPU Cores", fontWeight = FontWeight.SemiBold)
                    listOf(
                        listOf("1 core", "2 cores", "4 cores"),
                        listOf("6 cores", "8 cores")
                    ).forEach { options ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { option ->
                                FilterChip(
                                    selected = cpuCores == option,
                                    onClick = { cpuCores = option },
                                    label = { Text(option) }
                                )
                            }
                        }
                    }

                    Text("Performance Preset", fontWeight = FontWeight.SemiBold)
                    listOf("Compatibility", "Balanced", "Performance", "Custom").forEach { option ->
                        FilterChip(
                            selected = performancePreset == option,
                            onClick = {
                                performancePreset = option
                                when (option) {
                                    "Compatibility" -> {
                                        cpuModel = "Default"
                                        tcgCache = "128 MB"
                                        multiThreadedTcg = false
                                        machineType = "pc"
                                    }
                                    "Balanced" -> {
                                        cpuModel = "Default"
                                        tcgCache = "256 MB"
                                        multiThreadedTcg = true
                                        machineType = "pc"
                                    }
                                    "Performance" -> {
                                        cpuModel = "qemu64"
                                        cpuCores = "4 cores"
                                        tcgCache = "512 MB"
                                        multiThreadedTcg = true
                                        machineType = "pc"
                                        diskInterface = "IDE"
                                        displayAdapter = "Standard VGA"
                                    }
                                }
                            },
                            label = { Text(option) }
                        )
                    }

                    Text("CPU Model", fontWeight = FontWeight.SemiBold)
                    listOf(
                        listOf("Default", "qemu64", "core2duo"),
                        listOf("Nehalem", "SandyBridge", "Haswell"),
                        listOf("max")
                    ).forEach { options ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { option ->
                                FilterChip(
                                    selected = cpuModel == option,
                                    onClick = {
                                        cpuModel = option
                                        performancePreset = "Custom"
                                    },
                                    label = { Text(option) }
                                )
                            }
                        }
                    }

                    Text("CPU Flags", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Optional CPU features. If CPU Model is Default, RedBox uses qemu64 when flags are enabled.",
                        color = secondaryText,
                        fontSize = 12.sp
                    )
                    val selectedCpuFlags =
                        cpuFlags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    listOf(
                        "SSE3" to "sse3",
                        "SSSE3" to "ssse3",
                        "SSE4.1" to "sse4.1",
                        "SSE4.2" to "sse4.2",
                        "POPCNT" to "popcnt",
                        "AES" to "aes",
                        "AVX" to "avx"
                    ).forEach { (label, flag) ->
                        FilterChip(
                            selected = flag in selectedCpuFlags,
                            onClick = {
                                val updated = selectedCpuFlags.toMutableSet()
                                if (flag in updated) updated.remove(flag) else updated.add(flag)
                                cpuFlags = updated.joinToString(",")
                                performancePreset = "Custom"
                            },
                            label = { Text(label) }
                        )
                    }

                    Text("TCG Cache", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("128 MB", "256 MB", "512 MB").forEach { option ->
                            FilterChip(
                                selected = tcgCache == option,
                                onClick = {
                                    tcgCache = option
                                    performancePreset = "Custom"
                                },
                                label = { Text(option) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Multi-threaded TCG", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (multiThreadedTcg) "Enabled" else "Disabled",
                                color = secondaryText,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = multiThreadedTcg,
                            onCheckedChange = {
                                multiThreadedTcg = it
                                performancePreset = "Custom"
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCpuOptions = false }) { Text("Done") }
            }
        )
    }

    if (showRamOptions) {
        AlertDialog(
            onDismissRequest = { showRamOptions = false },
            title = { Text("RAM Options", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Choose how much memory the virtual machine can use.",
                        color = secondaryText,
                        fontSize = 12.sp
                    )
                    listOf(
                        listOf("128 MB", "256 MB", "384 MB"),
                        listOf("512 MB", "640 MB", "768 MB"),
                        listOf("1024 MB", "2048 MB"),
                        listOf("4096 MB", "6144 MB", "8192 MB")
                    ).forEach { options ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { option ->
                                FilterChip(
                                    selected = ram == option,
                                    onClick = { ram = option },
                                    label = { Text(option) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRamOptions = false }) { Text("Done") }
            }
        )
    }

    if (showMachineOptions) {
        AlertDialog(
            onDismissRequest = { showMachineOptions = false },
            title = { Text("Machine Options", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Choose the emulated PC chipset used by this virtual machine.",
                        color = secondaryText,
                        fontSize = 12.sp
                    )
                    FilterChip(
                        selected = machineType == "pc",
                        onClick = {
                            machineType = "pc"
                            performancePreset = "Custom"
                        },
                        label = { Text("PC (i440FX)") }
                    )
                    FilterChip(
                        selected = machineType == "q35",
                        onClick = {
                            machineType = "q35"
                            if (diskInterface == "IDE") diskInterface = "AHCI"
                            performancePreset = "Custom"
                        },
                        label = { Text("Q35") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMachineOptions = false }) { Text("Done") }
            }
        )
    }

    if (showCreateDiskDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDiskDialog = false },
            title = { Text("Create New Disk") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newDiskName,
                        onValueChange = { newDiskName = it },
                        label = { Text("Disk name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Disk size",
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(16, 32).forEach { size ->
                            OutlinedButton(
                                onClick = { newDiskSizeGb = size },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (newDiskSizeGb == size) "✓ $size GB" else "$size GB")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(64, 128).forEach { size ->
                            OutlinedButton(
                                onClick = { newDiskSizeGb = size },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (newDiskSizeGb == size) "✓ $size GB" else "$size GB")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "The disk is sparse, so it starts mostly empty and grows as Windows writes data.",
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val safeName = newDiskName
                            .trim()
                            .ifBlank { "RedBoxDisk.img" }
                            .let { if (it.endsWith(".img", ignoreCase = true)) it else "$it.img" }

                        newDiskName = safeName
                        showCreateDiskDialog = false
                        createDiskLauncher.launch(safeName)
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateDiskDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        containerColor = background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Create Virtual Machine",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            text = "‹",
                            color = Color.White,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = background,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .verticalScroll(scrollState)
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "General",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Name and architecture for this virtual machine.",
                color = secondaryText,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(22.dp))

            SectionCard(
                title = "VM Name",
                subtitle = "Give your virtual machine a name.",
                surfaceColor = surface
            ) {
                OutlinedTextField(
                    value = vmName,
                    onValueChange = { vmName = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White
                    ),
                    placeholder = {
                        Text(
                            text = "Example: Windows 10",
                            color = Color(0xFF666B75)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            SectionCard(
                title = "Architecture",
                subtitle = "Choose the CPU architecture for this VM.",
                surfaceColor = surface
            ) {
                BoxSelector(
                    value = architecture,
                    expanded = architectureMenu,
                    onClick = { architectureMenu = true },
                    surfaceColor = surfaceVariant,
                    accent = red
                )

                DropdownMenu(
                    expanded = architectureMenu,
                    onDismissRequest = { architectureMenu = false }
                ) {
                    listOf("x86_64", "x86", "ARM64", "ARM").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                architecture = option
                                architectureMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "System Configuration",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Configure CPU, RAM and machine type.",
                color = secondaryText,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCpuOptions = true },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CPU", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "$cpuCores · $cpuModel · $performancePreset",
                            color = secondaryText,
                            fontSize = 12.sp
                        )
                    }
                    Text("›", color = red, fontSize = 30.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRamOptions = true },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("RAM", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(ram, color = secondaryText, fontSize = 12.sp)
                    }
                    Text("›", color = red, fontSize = 30.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showMachineOptions = true },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Machine", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (machineType == "pc") "PC (i440FX)" else "Q35",
                            color = secondaryText,
                            fontSize = 12.sp
                        )
                    }
                    Text("›", color = red, fontSize = 30.sp)
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Storage",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Virtual disk interface and attached media.",
                color = secondaryText,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            SectionCard(
                title = "Disk Interface",
                subtitle = "IDE/AHCI are compatible choices. VirtIO Block is the modern high-performance option.",
                surfaceColor = surface
            ) {
                BoxSelector(
                    value = diskInterface,
                    expanded = diskInterfaceMenu,
                    onClick = { diskInterfaceMenu = true },
                    surfaceColor = surfaceVariant,
                    accent = red
                )

                DropdownMenu(
                    expanded = diskInterfaceMenu,
                    onDismissRequest = { diskInterfaceMenu = false }
                ) {
                    listOf("AHCI", "IDE", "VirtIO Block").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                diskInterface = option

                                // Q35 does not provide the legacy IDE layout we use.
                                // Selecting IDE automatically keeps the VM on PC/i440FX.
                                if (diskInterface == "IDE") {
                                    machineType = "pc"
                                    performancePreset = "Custom"
                                }

                                diskInterfaceMenu = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = when (diskInterface) {
                        "IDE" -> "IDE uses PC (i440FX) machine mode for maximum compatibility."
                        "VirtIO Block" -> "VirtIO Block reduces legacy disk emulation overhead, but Windows needs a VirtIO storage driver before its system disk can boot with it."
                        else -> "AHCI provides SATA-style compatibility for supported guests."
                    },
                    color = secondaryText,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            FileSection(
                title = "Disk Image",
                description = "Select an existing disk image, or create a new blank RedBox disk.",
                fileName = diskImageName,
                selected = diskImage.isNotEmpty(),
                buttonText = if (diskImage.isEmpty()) {
                    "Select Disk Image"
                } else {
                    "Change Disk Image"
                },
                surfaceColor = surface,
                accent = red,
                onSelect = {
                    diskPicker.launch(
                        arrayOf(
                            "application/octet-stream",
                            "application/x-qcow2",
                            "*/*"
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { showCreateDiskDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Create New Disk")
            }

            Text(
                text = "Creates a blank sparse RAW .img disk. It works with IDE, AHCI and VirtIO Block.",
                color = secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 7.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            FileSection(
                title = "ISO Image",
                description = "Optional boot/install media.",
                fileName = isoImageName,
                selected = isoImage.isNotEmpty(),
                buttonText = if (isoImage.isEmpty()) {
                    "Select ISO Image"
                } else {
                    "Change ISO Image"
                },
                surfaceColor = surface,
                accent = red,
                onSelect = {
                    isoPicker.launch(
                        arrayOf(
                            "application/x-iso9660-image",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            FileSection(
                title = "CD-ROM 2 / Driver ISO",
                description = "Optional second ISO, useful for VirtIO drivers during Windows Setup.",
                fileName = driverIsoImageName,
                selected = driverIsoImage.isNotEmpty(),
                buttonText = if (driverIsoImage.isEmpty()) {
                    "Select Driver ISO"
                } else {
                    "Change Driver ISO"
                },
                surfaceColor = surface,
                accent = red,
                onSelect = {
                    driverIsoPicker.launch(
                        arrayOf(
                            "application/x-iso9660-image",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            FileSection(
                title = "Shared Hard Drive",
                description = "Optional second virtual disk for moving files into the guest.",
                fileName = sharedDiskImageName,
                selected = sharedDiskImage.isNotEmpty(),
                buttonText = if (sharedDiskImage.isEmpty()) {
                    "Select Shared Drive"
                } else {
                    "Change Shared Drive"
                },
                surfaceColor = surface,
                accent = red,
                onSelect = {
                    sharedDiskPicker.launch(
                        arrayOf(
                            "application/octet-stream",
                            "application/x-qcow2",
                            "*/*"
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            SectionCard(
                title = "Shared Folder",
                subtitle = "Import Android files into a Windows-readable QEMU VVFAT drive.",
                surfaceColor = surface
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (sharedFolderEnabled) "Enabled" else "Disabled",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (sharedFolderLastFileName.isNotEmpty()) {
                                "Last added: $sharedFolderLastFileName"
                            } else {
                                "Add an MP3, EXE, image, document, or other file."
                            },
                            color = secondaryText,
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = sharedFolderEnabled,
                        onCheckedChange = {
                            sharedFolderEnabled = it
                            if (it) {
                                sharedDiskImage = ""
                                sharedDiskImageName = ""
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        sharedFolderFilePicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = red,
                        contentColor = Color.White
                    )
                ) {
                    Text("Add File to Shared Folder")
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Display",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Choose the emulated graphics adapter.",
                color = secondaryText,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            SectionCard(
                title = "Display Adapter",
                subtitle = when (displayAdapter) {
                    "Bochs Display" -> "Modern software framebuffer. Worth testing for better desktop responsiveness."
                    "VirtIO VGA" -> "Paravirtualized graphics. Guest driver support may be required."
                    "Cirrus VGA" -> "Legacy graphics adapter for older operating systems."
                    else -> "Best compatibility. Recommended as the safe default."
                },
                surfaceColor = surface
            ) {
                BoxSelector(
                    value = displayAdapter,
                    expanded = displayAdapterMenu,
                    onClick = { displayAdapterMenu = true },
                    surfaceColor = surfaceVariant,
                    accent = red
                )

                DropdownMenu(
                    expanded = displayAdapterMenu,
                    onDismissRequest = { displayAdapterMenu = false }
                ) {
                    listOf(
                        "Standard VGA",
                        "Bochs Display",
                        "VirtIO VGA",
                        "Cirrus VGA"
                    ).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                displayAdapter = option
                                displayAdapterMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Network",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Configure guest network access.",
                color = secondaryText,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            SectionCard(
                title = "Network",
                subtitle = "Enable or disable the virtual network adapter.",
                surfaceColor = surface
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (networkEnabled) "Enabled" else "Disabled",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (networkEnabled) {
                                "Guest networking will be available."
                            } else {
                                "No virtual network adapter will be attached."
                            },
                            color = secondaryText,
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = networkEnabled,
                        onCheckedChange = { networkEnabled = it }
                    )
                }
            }

            if (networkEnabled) {
                Spacer(modifier = Modifier.height(14.dp))

                SectionCard(
                    title = "Network Adapter",
                    subtitle = when (networkAdapter) {
                        "Realtek RTL8139" -> "Legacy adapter for older operating systems."
                        "AMD PCnet" -> "Classic AMD PCnet adapter for older Windows and legacy guests."
                        else -> "Recommended for Windows 10 and modern guests."
                    },
                    surfaceColor = surface
                ) {
                    BoxSelector(
                        value = networkAdapter,
                        expanded = networkAdapterMenu,
                        onClick = { networkAdapterMenu = true },
                        surfaceColor = surfaceVariant,
                        accent = red
                    )

                    DropdownMenu(
                        expanded = networkAdapterMenu,
                        onDismissRequest = { networkAdapterMenu = false }
                    ) {
                        listOf("Intel E1000", "Intel E1000E", "Realtek RTL8139", "AMD PCnet").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    networkAdapter = option
                                    networkAdapterMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                SectionCard(
                    title = "Network Mode",
                    subtitle = "User (NAT) shares the Android device's connection without root.",
                    surfaceColor = surface
                ) {
                    BoxSelector(
                        value = networkMode,
                        expanded = networkModeMenu,
                        onClick = { networkModeMenu = true },
                        surfaceColor = surfaceVariant,
                        accent = red
                    )

                    DropdownMenu(
                        expanded = networkModeMenu,
                        onDismissRequest = { networkModeMenu = false }
                    ) {
                        listOf("User (NAT)").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    networkMode = option
                                    networkModeMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Audio",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Choose the emulated sound card for this virtual machine.",
                color = secondaryText,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            SectionCard(
                title = "Sound Card",
                subtitle = when (soundCard) {
                    "AC97" -> "Legacy AC97 audio for older Windows and Linux guests."
                    "Sound Blaster 16" -> "Classic ISA Sound Blaster 16 for legacy operating systems."
                    else -> "Intel HD Audio using RedBox's existing SDL audio backend."
                },
                surfaceColor = surface
            ) {
                listOf("Intel HDA", "AC97", "Sound Blaster 16").forEach { option ->
                    FilterChip(
                        selected = soundCard == option,
                        onClick = { soundCard = option },
                        label = { Text(option) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            SectionCard(
                title = "Audio Backend",
                subtitle = if (audioBackend == "AAudio") {
                    "Low-latency Android AAudio output. Requires Android 8.0 or newer."
                } else {
                    "Default SDL Android audio output. Recommended for maximum compatibility."
                },
                surfaceColor = surface
            ) {
                listOf("Default", "AAudio").forEach { option ->
                    FilterChip(
                        selected = audioBackend == option,
                        onClick = { audioBackend = option },
                        label = { Text(option) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Advanced",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Optional settings for advanced QEMU users.",
                color = secondaryText,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            SectionCard(
                title = "BIOS / Guest Date",
                subtitle = "Set the guest RTC date for Windows beta builds. Default uses QEMU's normal current date.",
                surfaceColor = surface
            ) {
                listOf("Default", "2001-07-01", "2003-10-01", "2005-04-01").forEach { option ->
                    FilterChip(
                        selected = biosDate == option,
                        onClick = { biosDate = option },
                        label = {
                            Text(
                                when (option) {
                                    "Default" -> "Default"
                                    "2001-07-01" -> "2001 (Whistler era)"
                                    "2003-10-01" -> "2003 (Longhorn era)"
                                    else -> "2005 (Longhorn era)"
                                }
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = if (biosDate == "Default") "" else biosDate,
                    onValueChange = { biosDate = it.trim().ifBlank { "Default" } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Custom date") },
                    placeholder = { Text("YYYY-MM-DD", color = secondaryText) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Example: 2005-04-01. This changes the guest RTC date.",
                    color = secondaryText,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            SectionCard(
                title = "QEMU Parameters",
                subtitle = "Optional extra QEMU command-line parameters. Leave empty for normal RedBox settings.",
                surfaceColor = surface
            ) {
                OutlinedTextField(
                    value = qemuParams,
                    onValueChange = { qemuParams = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "-rtc base=localtime",
                            color = secondaryText
                        )
                    },
                    minLines = 2,
                    maxLines = 5,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Example: -rtc base=localtime. Invalid or conflicting parameters can stop a VM from starting.",
                    color = secondaryText,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val newVM = VMModel(
                        name = vmName,
                        architecture = architecture,
                        ram = ram,
                        cpuCores = cpuCores,
                        diskImage = diskImage,
                        diskImageName = diskImageName,
                        isoImage = isoImage,
                        isoImageName = isoImageName,
                        driverIsoImage = driverIsoImage,
                        driverIsoImageName = driverIsoImageName,
                        sharedDiskImage = sharedDiskImage,
                        sharedDiskImageName = sharedDiskImageName,
                        sharedFolderEnabled = sharedFolderEnabled,
                        sharedFolderLastFileName = sharedFolderLastFileName,
                        performancePreset = performancePreset,
                        cpuModel = cpuModel,
                        cpuFlags = cpuFlags,
                        tcgCache = tcgCache,
                        multiThreadedTcg = multiThreadedTcg,
                        machineType = machineType,
                        diskInterface = diskInterface,
                        displayAdapter = displayAdapter,
                        networkEnabled = networkEnabled,
                        networkAdapter = networkAdapter,
                        networkMode = networkMode,
                        qemuParams = qemuParams.trim(),
                        biosDate = biosDate.trim().ifBlank { "Default" },
                        soundCard = soundCard,
                        audioBackend = audioBackend
                    )
                    onVMCreated(newVM)
                },
                enabled = vmName.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = red,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF3A2024),
                    disabledContentColor = Color(0xFF777177)
                )
            ) {
                Text(
                    text = "Create Virtual Machine",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "You can change VM settings later.",
                modifier = Modifier.fillMaxWidth(),
                color = secondaryText,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    surfaceColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subtitle,
                color = Color(0xFF969BA5),
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            content()
        }
    }
}

@Composable
private fun BoxSelector(
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
    surfaceColor: Color,
    accent: Color
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = value,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = if (expanded) "▲" else "▼",
                color = accent,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun FileSection(
    title: String,
    description: String,
    fileName: String,
    selected: Boolean,
    buttonText: String,
    surfaceColor: Color,
    accent: Color,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1B1E26)
                ) {
                    BoxedFileIcon(accent = accent)
                }

                Spacer(modifier = Modifier.padding(horizontal = 6.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = description,
                        color = Color(0xFF969BA5),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = buttonText,
                    fontWeight = FontWeight.Bold
                )
            }

            if (selected && fileName.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1A1D24)
                ) {
                    Text(
                        text = "✓  $fileName",
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFFB9BEC8),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxedFileIcon(accent: Color) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "▣",
            color = accent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
