package com.rimvydop.redboxpcemulator

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("redboxpcemulator")

            try {
                System.loadLibrary("qemu-system-x86_64")
                Log.d(
                    "RedBoxQEMU",
                    "Android loaded libqemu-system-x86_64.so"
                )
            } catch (error: UnsatisfiedLinkError) {
                Log.e(
                    "RedBoxQEMU",
                    "Android could not load libqemu-system-x86_64.so",
                    error
                )
            }
        }
    }

    private external fun stringFromJNI(): String

    private external fun nativeQemuStatus(): String

    private external fun nativeQemuStart(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nativeMessage = stringFromJNI()
        Log.d("RedBoxNative", nativeMessage)

        val qemuStatus = nativeQemuStatus()
        Log.d("RedBoxQEMU", qemuStatus)

        setContent {
            RedBoxApp(
                qemuStatus = qemuStatus,
                onStartQemu = {
                    val result = nativeQemuStart()

                    Log.d(
                        "RedBoxQEMU",
                        "QEMU start result: $result"
                    )

                    result
                }
            )
        }
    }
}

@Composable
fun RedBoxApp(
    qemuStatus: String,
    onStartQemu: () -> String
) {

    var showCreateVM by remember { mutableStateOf(false) }
    var showVMDetails by remember { mutableStateOf(false) }

    var createdVM by remember { mutableStateOf<VMModel?>(null) }

    var isVMRunning by remember { mutableStateOf(false) }

    var qemuRuntimeStatus by remember {
        mutableStateOf("")
    }

    if (showCreateVM) {

        CreateVMScreen(
            onBack = {
                showCreateVM = false
            },
            onVMCreated = { vm ->
                createdVM = vm
                isVMRunning = false
                qemuRuntimeStatus = ""
                showCreateVM = false
            }
        )

    } else if (showVMDetails && createdVM != null) {

        VMDetailsScreen(
            vm = createdVM!!,
            isRunning = isVMRunning,
            qemuRuntimeStatus = qemuRuntimeStatus,
            onStartVM = {

                val result = onStartQemu()

                qemuRuntimeStatus = result

                if (
                    result ==
                    "QEMU Engine Initialized Successfully" ||
                    result ==
                    "QEMU Already Initialized"
                ) {
                    isVMRunning = true
                }
            },
            onStopVM = {

                isVMRunning = false

                qemuRuntimeStatus =
                    "VM stopped in UI"
            },
            onBack = {
                showVMDetails = false
            }
        )

    } else {

        RedBoxHome(
            onCreateVM = {
                showCreateVM = true
            },
            onVMClick = {
                showVMDetails = true
            },
            createdVM = createdVM,
            isRunning = isVMRunning,
            qemuStatus = qemuStatus
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedBoxHome(
    onCreateVM: () -> Unit,
    onVMClick: () -> Unit,
    createdVM: VMModel?,
    isRunning: Boolean,
    qemuStatus: String
) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "RedBox PC Emulator",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF111111),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF080808)
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Text(
                text = "Welcome",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Run virtual machines on your Android device.",
                color = Color.Gray,
                fontSize = 15.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            if (createdVM == null) {

                Text(
                    text = "No virtual machines",
                    color = Color.Gray,
                    fontSize = 16.sp
                )

            } else {

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onVMClick()
                        },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF151515)
                    )
                ) {

                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {

                                Text(
                                    text = createdVM.name,
                                    color = Color.White,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(
                                    modifier =
                                        Modifier.height(6.dp)
                                )

                                Text(
                                    text =
                                        createdVM.architecture,
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                            }

                            Spacer(
                                modifier =
                                    Modifier.width(12.dp)
                            )

                            Text(
                                text = if (isRunning) {
                                    "🟢 Running"
                                } else {
                                    "⚫ Stopped"
                                },
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight =
                                    FontWeight.SemiBold
                            )
                        }

                        Spacer(
                            modifier =
                                Modifier.height(14.dp)
                        )

                        Text(
                            text =
                                "${createdVM.ram} MB RAM  •  " +
                                        "${createdVM.cpuCores} CPU cores",
                            color = Color.LightGray,
                            fontSize = 14.sp
                        )

                        Spacer(
                            modifier =
                                Modifier.height(10.dp)
                        )

                        if (
                            createdVM.diskImageName
                                .isNotEmpty()
                        ) {

                            Text(
                                text =
                                    "💿 " +
                                            createdVM.diskImageName,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }

                        if (
                            createdVM.isoImageName
                                .isNotEmpty()
                        ) {

                            Spacer(
                                modifier =
                                    Modifier.height(6.dp)
                            )

                            Text(
                                text =
                                    "📀 " +
                                            createdVM.isoImageName,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(
                            modifier =
                                Modifier.height(14.dp)
                        )

                        Text(
                            text =
                                "Tap to view VM details",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            Button(
                onClick = onCreateVM,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {

                Text(
                    text =
                        "+  Create Virtual Machine",
                    fontSize = 16.sp,
                    fontWeight =
                        FontWeight.SemiBold
                )
            }

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            Text(
                text = "Engine",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight =
                    FontWeight.SemiBold
            )

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )

            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(18.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color(0xFF151515)
                    )
            ) {

                Column(
                    modifier =
                        Modifier.padding(18.dp)
                ) {

                    Text(
                        text = "QEMU",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        modifier =
                            Modifier.height(5.dp)
                    )

                    Text(
                        text =
                            "Virtual machine engine",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )

                    Spacer(
                        modifier =
                            Modifier.height(12.dp)
                    )

                    Text(
                        text = qemuStatus,
                        color =
                            if (
                                qemuStatus ==
                                "QEMU Engine Loaded Successfully"
                            ) {
                                Color.White
                            } else {
                                Color.LightGray
                            },
                        fontSize = 14.sp,
                        fontWeight =
                            FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VMDetailsScreen(
    vm: VMModel,
    isRunning: Boolean,
    qemuRuntimeStatus: String,
    onStartVM: () -> Unit,
    onStopVM: () -> Unit,
    onBack: () -> Unit
) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "VM Details",
                        fontWeight =
                            FontWeight.Bold
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor =
                            Color(0xFF111111),
                        titleContentColor =
                            Color.White
                    )
            )
        },
        containerColor =
            Color(0xFF080808)
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(
                    rememberScrollState()
                )
        ) {

            Text(
                text = vm.name,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )

            Text(
                text = vm.architecture,
                color = Color.Gray,
                fontSize = 15.sp
            )

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            if (isRunning) {

                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    shape =
                        RoundedCornerShape(18.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                Color(0xFF151515)
                        )
                ) {

                    Column(
                        modifier =
                            Modifier.padding(18.dp)
                    ) {

                        Text(
                            text =
                                "🟢  VM Running",
                            color =
                                Color.White,
                            fontSize = 18.sp,
                            fontWeight =
                                FontWeight.Bold
                        )

                        Spacer(
                            modifier =
                                Modifier.height(6.dp)
                        )

                        Text(
                            text =
                                "QEMU has been initialized for this VM.",
                            color =
                                Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(24.dp)
                )
            }

            if (
                qemuRuntimeStatus
                    .isNotEmpty()
            ) {

                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    shape =
                        RoundedCornerShape(18.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                Color(0xFF151515)
                        )
                ) {

                    Column(
                        modifier =
                            Modifier.padding(18.dp)
                    ) {

                        Text(
                            text =
                                "QEMU Status",
                            color =
                                Color.Gray,
                            fontSize = 13.sp
                        )

                        Spacer(
                            modifier =
                                Modifier.height(6.dp)
                        )

                        Text(
                            text =
                                qemuRuntimeStatus,
                            color =
                                Color.White,
                            fontSize = 15.sp,
                            fontWeight =
                                FontWeight.SemiBold
                        )
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(24.dp)
                )
            }

            Text(
                text = "System",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight =
                    FontWeight.SemiBold
            )

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )

            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(18.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color(0xFF151515)
                    )
            ) {

                Column(
                    modifier =
                        Modifier.padding(18.dp)
                ) {

                    Text(
                        text =
                            "Architecture",
                        color =
                            Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )

                    Text(
                        text =
                            vm.architecture,
                        color =
                            Color.White,
                        fontSize = 16.sp
                    )

                    Spacer(
                        modifier =
                            Modifier.height(16.dp)
                    )

                    Text(
                        text =
                            "Memory",
                        color =
                            Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )

                    Text(
                        text =
                            "${vm.ram} MB RAM",
                        color =
                            Color.White,
                        fontSize = 16.sp
                    )

                    Spacer(
                        modifier =
                            Modifier.height(16.dp)
                    )

                    Text(
                        text =
                            "CPU",
                        color =
                            Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )

                    Text(
                        text =
                            "${vm.cpuCores} CPU cores",
                        color =
                            Color.White,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            Text(
                text = "Storage",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight =
                    FontWeight.SemiBold
            )

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )

            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                shape =
                    RoundedCornerShape(18.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color(0xFF151515)
                    )
            ) {

                Column(
                    modifier =
                        Modifier.padding(18.dp)
                ) {

                    if (
                        vm.diskImageName
                            .isNotEmpty()
                    ) {

                        Text(
                            text =
                                "Disk Image",
                            color =
                                Color.Gray,
                            fontSize = 13.sp
                        )

                        Spacer(
                            modifier =
                                Modifier.height(5.dp)
                        )

                        Text(
                            text =
                                "💿  ${vm.diskImageName}",
                            color =
                                Color.White,
                            fontSize = 15.sp
                        )
                    }

                    if (
                        vm.diskImageName
                            .isNotEmpty() &&
                        vm.isoImageName
                            .isNotEmpty()
                    ) {

                        Spacer(
                            modifier =
                                Modifier.height(16.dp)
                        )
                    }

                    if (
                        vm.isoImageName
                            .isNotEmpty()
                    ) {

                        Text(
                            text =
                                "ISO Image",
                            color =
                                Color.Gray,
                            fontSize = 13.sp
                        )

                        Spacer(
                            modifier =
                                Modifier.height(5.dp)
                        )

                        Text(
                            text =
                                "📀  ${vm.isoImageName}",
                            color =
                                Color.White,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            if (!isRunning) {

                Button(
                    onClick = onStartVM,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape =
                        RoundedCornerShape(16.dp)
                ) {

                    Text(
                        text =
                            "▶  Start VM",
                        fontSize = 16.sp,
                        fontWeight =
                            FontWeight.SemiBold
                    )
                }

            } else {

                Button(
                    onClick = onStopVM,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape =
                        RoundedCornerShape(16.dp)
                ) {

                    Text(
                        text =
                            "⏹  Stop VM",
                        fontSize = 16.sp,
                        fontWeight =
                            FontWeight.SemiBold
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape =
                    RoundedCornerShape(16.dp)
            ) {

                Text(
                    text =
                        "←  Back",
                    fontSize = 16.sp,
                    fontWeight =
                        FontWeight.SemiBold
                )
            }
        }
    }
}