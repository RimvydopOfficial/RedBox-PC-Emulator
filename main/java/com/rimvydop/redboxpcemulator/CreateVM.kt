package com.rimvydop.redboxpcemulator

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVMScreen(
    onBack: () -> Unit,
    onVMCreated: (VMModel) -> Unit
) {

    var vmName by remember {
        mutableStateOf("")
    }

    var vmCreated by remember {
        mutableStateOf(false)
    }

    var architecture by remember {
        mutableStateOf("x86_64")
    }

    var ram by remember {
        mutableStateOf("4096 MB")
    }

    var cpuCores by remember {
        mutableStateOf("4 cores")
    }

    var diskImage by remember {
        mutableStateOf("")
    }

    var diskImageName by remember {
        mutableStateOf("")
    }

    var isoImage by remember {
        mutableStateOf("")
    }

    var isoImageName by remember {
        mutableStateOf("")
    }

    var architectureMenu by remember {
        mutableStateOf(false)
    }

    var ramMenu by remember {
        mutableStateOf(false)
    }

    var cpuMenu by remember {
        mutableStateOf(false)
    }

    val context = LocalContext.current

    // Disk Image Picker
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
            } catch (e: SecurityException) {
                // Some file providers do not support persistent permissions
            }

            val cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )

            cursor?.use {

                if (it.moveToFirst()) {

                    val nameIndex =
                        it.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                    if (nameIndex >= 0) {
                        diskImageName = it.getString(nameIndex)
                    }
                }
            }
        }
    }

    // ISO Image Picker
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
            } catch (e: SecurityException) {
                // Some file providers do not support persistent permissions
            }

            val cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )

            cursor?.use {

                if (it.moveToFirst()) {

                    val nameIndex =
                        it.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                    if (nameIndex >= 0) {
                        isoImageName = it.getString(nameIndex)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {

            TopAppBar(
                title = {

                    Text(
                        text = "Create Virtual Machine",
                        fontWeight = FontWeight.Bold
                    )
                },

                navigationIcon = {

                    IconButton(
                        onClick = onBack
                    ) {

                        Text(
                            text = "←",
                            fontSize = 28.sp
                        )
                    }
                }
            )
        },

        containerColor = Color(0xFF080808)

    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(20.dp),

            verticalArrangement = Arrangement.Top
        ) {

            Text(
                text = "Basic Configuration",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Text(
                text = "Configure your virtual machine.",
                color = Color.Gray,
                fontSize = 14.sp
            )

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            // VM Name
            Card(
                modifier = Modifier.fillMaxWidth(),

                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF151515)
                )
            ) {

                Column(
                    modifier = Modifier.padding(18.dp)
                ) {

                    Text(
                        text = "VM Name",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    OutlinedTextField(
                        value = vmName,

                        onValueChange = {
                            vmName = it
                        },

                        modifier = Modifier.fillMaxWidth(),

                        placeholder = {
                            Text("Example: Windows 10")
                        },

                        singleLine = true
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            // Architecture
            Card(
                modifier = Modifier.fillMaxWidth(),

                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF151515)
                )
            ) {

                Column(
                    modifier = Modifier.padding(18.dp)
                ) {

                    Text(
                        text = "Architecture",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Button(
                        onClick = {
                            architectureMenu = true
                        },

                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Row(
                            modifier = Modifier.fillMaxWidth(),

                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            Text(architecture)

                            Text("▼")
                        }
                    }

                    DropdownMenu(
                        expanded = architectureMenu,

                        onDismissRequest = {
                            architectureMenu = false
                        }
                    ) {

                        DropdownMenuItem(
                            text = {
                                Text("x86_64")
                            },

                            onClick = {
                                architecture = "x86_64"
                                architectureMenu = false
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text("x86")
                            },

                            onClick = {
                                architecture = "x86"
                                architectureMenu = false
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text("ARM64")
                            },

                            onClick = {
                                architecture = "ARM64"
                                architectureMenu = false
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text("ARM")
                            },

                            onClick = {
                                architecture = "ARM"
                                architectureMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            // Memory
            Card(
                modifier = Modifier.fillMaxWidth(),

                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF151515)
                )
            ) {

                Column(
                    modifier = Modifier.padding(18.dp)
                ) {

                    Text(
                        text = "Memory",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Button(
                        onClick = {
                            ramMenu = true
                        },

                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Row(
                            modifier = Modifier.fillMaxWidth(),

                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            Text(ram)

                            Text("▼")
                        }
                    }

                    DropdownMenu(
                        expanded = ramMenu,

                        onDismissRequest = {
                            ramMenu = false
                        }
                    ) {

                        listOf(
                            "1024 MB",
                            "2048 MB",
                            "4096 MB",
                            "6144 MB",
                            "8192 MB"
                        ).forEach { option ->

                            DropdownMenuItem(
                                text = {
                                    Text(option)
                                },

                                onClick = {
                                    ram = option
                                    ramMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            // CPU Cores
            Card(
                modifier = Modifier.fillMaxWidth(),

                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF151515)
                )
            ) {

                Column(
                    modifier = Modifier.padding(18.dp)
                ) {

                    Text(
                        text = "CPU Cores",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Button(
                        onClick = {
                            cpuMenu = true
                        },

                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Row(
                            modifier = Modifier.fillMaxWidth(),

                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            Text(cpuCores)

                            Text("▼")
                        }
                    }

                    DropdownMenu(
                        expanded = cpuMenu,

                        onDismissRequest = {
                            cpuMenu = false
                        }
                    ) {

                        listOf(
                            "1 core",
                            "2 cores",
                            "4 cores",
                            "6 cores",
                            "8 cores"
                        ).forEach { option ->

                            DropdownMenuItem(
                                text = {
                                    Text(option)
                                },

                                onClick = {
                                    cpuCores = option
                                    cpuMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            // Disk Image
            Card(
                modifier = Modifier.fillMaxWidth(),

                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF151515)
                )
            ) {

                Column(
                    modifier = Modifier.padding(18.dp)
                ) {

                    Text(
                        text = "Disk Image",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Button(
                        onClick = {

                            diskPicker.launch(
                                arrayOf(
                                    "application/octet-stream",
                                    "application/x-qcow2",
                                    "*/*"
                                )
                            )
                        },

                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Text(
                            text = if (diskImage.isEmpty()) {
                                "Select Disk Image"
                            } else {
                                "Disk Image Selected"
                            }
                        )
                    }

                    if (diskImage.isNotEmpty()) {

                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )

                        Text(
                            text = diskImageName,
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            // ISO Image
            Card(
                modifier = Modifier.fillMaxWidth(),

                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF151515)
                )
            ) {

                Column(
                    modifier = Modifier.padding(18.dp)
                ) {

                    Text(
                        text = "ISO Image",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Button(
                        onClick = {

                            isoPicker.launch(
                                arrayOf(
                                    "application/x-iso9660-image",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },

                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Text(
                            text = if (isoImage.isEmpty()) {
                                "Select ISO Image"
                            } else {
                                "ISO Image Selected"
                            }
                        )
                    }

                    if (isoImage.isNotEmpty()) {

                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )

                        Text(
                            text = isoImageName,
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(24.dp)
            )

            // Create VM
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
                        isoImageName = isoImageName
                    )

                    onVMCreated(newVM)
                },

                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {

                Text(
                    text = "Create VM",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (vmCreated) {
                Spacer(
                    modifier = Modifier.height(12.dp)
                )

                Text(
                    text = "✓ VM Created Successfully",
                    color = Color.Green,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}