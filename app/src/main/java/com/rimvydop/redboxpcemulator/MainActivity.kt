package com.rimvydop.redboxpcemulator

import android.view.KeyEvent

import android.net.Uri
import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.res.Configuration
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.libsdl.app.SDLActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : SDLActivity() {

    @Volatile
    private var redBoxVmScreenVisible = false

    @Volatile
    private var qemuServiceBound = false

    private var qemuServiceMessenger: Messenger? = null

    /*
     * Part 2L.1: keep the Compose start callback until the isolated VM
     * session actually ends, so isVMRunning is released after Stop.
     */
    private var qemuSessionEndedCallback: ((String) -> Unit)? = null

    private val qemuReplyHandler =
        object : Handler(Looper.getMainLooper()) {

            override fun handleMessage(message: Message) {
                val status =
                    message.data.getString(
                        QemuIpc.KEY_STATUS
                    ) ?: ""

                Log.d(
                    "RedBoxQemuIPC",
                    "Reply from :qemu process: what=${message.what}, status=$status"
                )

                when (message.what) {
                    QemuIpc.MSG_VM_STARTING,
                    QemuIpc.MSG_VM_RUNNING,
                    QemuIpc.MSG_VM_STOPPING -> {
                        // The isolated QEMU process is still active.
                    }

                    QemuIpc.MSG_VM_STOPPED -> {
                        qemuProcessActive.set(false)

                        val callback = qemuSessionEndedCallback
                        qemuSessionEndedCallback = null
                        callback?.invoke(status)

                        Log.d(
                            "RedBoxQemuIPC",
                            "Isolated QEMU VM stopped; UI state + process guard released"
                        )
                    }

                    QemuIpc.MSG_VM_ERROR -> {
                        qemuProcessActive.set(false)

                        val callback = qemuSessionEndedCallback
                        qemuSessionEndedCallback = null
                        callback?.invoke(status)

                        Log.e(
                            "RedBoxQemuIPC",
                            "Isolated QEMU VM reported an error: $status"
                        )
                    }

                    else -> {
                        super.handleMessage(message)
                    }
                }
            }
        }

    private val qemuReplyMessenger =
        Messenger(qemuReplyHandler)

    private val qemuServiceConnection =
        object : ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?
            ) {
                qemuServiceMessenger =
                    if (service != null) {
                        Messenger(service)
                    } else {
                        null
                    }

                qemuServiceBound =
                    qemuServiceMessenger != null

                Log.d(
                    "RedBoxQemuIPC",
                    "Connected to :qemu service. UI PID=${android.os.Process.myPid()}"
                )


            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {
                qemuServiceMessenger = null
                qemuServiceBound = false
                qemuProcessActive.set(false)

                // Part 2L.1 fallback if the old :qemu PID dies before its
                // MSG_VM_STOPPED Binder reply reaches the UI process.
                val callback = qemuSessionEndedCallback
                qemuSessionEndedCallback = null
                callback?.invoke("QEMU VM stopped")

                Log.d(
                    "RedBoxQemuIPC",
                    ":qemu service disconnected; UI state + process guard released"
                )

                // The old :qemu PID was intentionally killed by Part 2L.
                // Ask Android for a fresh isolated service process now.
                mainHandler.postDelayed(
                    {
                        if (!isFinishing && !isDestroyed && !qemuServiceBound) {
                            Log.d(
                                "RedBoxQemuIPC",
                                "Part 2L.2: rebinding fresh :qemu service after process death"
                            )
                            bindFreshQemuService()
                        }
                    },
                    250L
                )
            }
        }

    /*
     * RedBox Part 2L.2:
     * Explicitly recreate/rebind the isolated :qemu service after Part 2L
     * deliberately terminates the old QEMU process.
     */
    private fun bindFreshQemuService() {
        if (qemuServiceBound) {
            return
        }

        val qemuServiceIntent =
            Intent(
                this,
                QemuService::class.java
            )

        val bindStarted =
            bindService(
                qemuServiceIntent,
                qemuServiceConnection,
                Context.BIND_AUTO_CREATE
            )

        Log.d(
            "RedBoxQemuIPC",
            "Part 2L.2 bindService(:qemu) returned $bindStarted. UI PID=${android.os.Process.myPid()}"
        )
    }

    fun setRedBoxVmScreenVisible(visible: Boolean) {
        redBoxVmScreenVisible = visible
    }

    /*
     * STEP 12D Part 5E:
     * Tell SDLActivity exactly which native libraries RedBox uses.
     *
     * SDLActivity.onCreate() will now run SDL.setupJNI(), SDL.initialize(),
     * and SDL.setContext(this) before RedBox starts QEMU.
     */
    override fun getLibraries(): Array<String> {
        return arrayOf(
            "SDL2",
            "compat-SDL2-ext",
            "redboxpcemulator",
            "qemu-system-x86_64"
        )
    }

    private val limboFileDescriptors =
        mutableMapOf<Int, ParcelFileDescriptor>()

    companion object {
        /*
         * STEP 13A.1:
         * QEMU is process-level native state. Android rotation must never
         * start a second qemu_init() while the current VM is still alive.
         */
        private val qemuProcessActive =
            AtomicBoolean(false)

        init {
            System.loadLibrary("SDL2")
            Log.d(
                "RedBoxQEMU",
                "Android loaded libSDL2.so"
            )

            System.loadLibrary("compat-SDL2-ext")
            Log.d(
                "RedBoxQEMU",
                "Android loaded libcompat-SDL2-ext.so"
            )

            System.loadLibrary("redboxpcemulator")
            Log.d(
                "RedBoxQEMU",
                "Android loaded libredboxpcemulator.so"
            )

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

        /*
         * Called from Limbo's SDL compatibility library when QEMU changes
         * the guest display resolution. JNI expects this exact STATIC method:
         * MainActivity.onVMResolutionChanged(int width, int height).
         */
        @JvmStatic
        fun onVMResolutionChanged(width: Int, height: Int) {
            Log.d(
                "RedBoxDisplay",
                "VM resolution changed by SDL/QEMU: ${width}x${height}"
            )
        }
    }

    private external fun stringFromJNI(): String
    private external fun nativeQemuStatus(): String
    private external fun nativeQemuStop(): Boolean
    private external fun nativeQemuStart(
        ramMb: Int,
        cpuCores: Int,
        diskUri: String,
        diskImageName: String,
        isoUri: String,
        driverIsoUri: String,
        sharedDiskUri: String,
        sharedDiskImageName: String,
        sharedFolderEnabled: Boolean,
        cpuModel: String,
        cpuFlags: String,
        tcgCacheMb: Int,
        multiThreadedTcg: Boolean,
        machineType: String,
        diskInterface: String,
        displayAdapter: String,
        networkEnabled: Boolean,
        networkAdapter: String,
        networkMode: String,
        qemuParams: String,
        biosDate: String,
        soundCard: String
    ): String

    /*
     * STEP 13C:
     * Direct QEMU mouse button injection.
     *
     * button:
     *   0 = left
     *   1 = middle
     *   2 = right
     *
     * down:
     *   true  = press
     *   false = release
     */
    private external fun nativeQemuMouseButton(
        button: Int,
        down: Boolean
    ): Boolean

    private external fun nativeQemuMouseMove(
        deltaX: Int,
        deltaY: Int
    ): Boolean

    fun moveRedBoxMouse(
        deltaX: Float,
        deltaY: Float
    ) {
        if (!qemuProcessActive.get()) {
            return
        }

        // STEP 13C.4: make the virtual touchpad feel quicker.
        // 1.5x means the guest cursor travels farther than the finger.
        val touchpadSensitivity = 1.5f

        val dx = (deltaX * touchpadSensitivity).toInt()
        val dy = (deltaY * touchpadSensitivity).toInt()

        if (dx == 0 && dy == 0) {
            return
        }

        val serviceMessenger = qemuServiceMessenger
        if (!qemuServiceBound || serviceMessenger == null) {
            return
        }

        try {
            val message =
                Message.obtain(
                    null,
                    QemuIpc.MSG_MOUSE_MOVE
                ).apply {
                    data = Bundle().apply {
                        putInt(QemuIpc.KEY_MOUSE_DX, dx)
                        putInt(QemuIpc.KEY_MOUSE_DY, dy)
                    }
                }

            serviceMessenger.send(message)
        } catch (error: RemoteException) {
            Log.e(
                "RedBoxQemuIPC",
                "Failed to send mouse movement to :qemu process",
                error
            )
        }
    }

    private external fun nativeSetDisplaySurface(
        surface: android.view.Surface?
    )

    /*
     * RedBox Part 2M.2:
     * Send the Android Surface AND its current geometry to the isolated
     * :qemu process. This lets SDL receive a real resize event when Compose
     * changes the VM display between normal and fullscreen layouts.
     */
    fun setQemuDisplaySurface(
        surface: android.view.Surface?,
        width: Int = 0,
        height: Int = 0,
        pixelFormat: Int = 0,
        refreshRate: Float = 60f
    ) {
        val serviceMessenger = qemuServiceMessenger

        if (!qemuServiceBound || serviceMessenger == null) {
            Log.w(
                "RedBoxQemuIPC",
                "Cannot send display Surface: :qemu service is not connected"
            )
            return
        }

        try {
            val message =
                if (surface != null && surface.isValid) {
                    Message.obtain(
                        null,
                        QemuIpc.MSG_SET_SURFACE
                    ).apply {
                        data = Bundle().apply {
                            putParcelable(
                                QemuIpc.KEY_SURFACE,
                                surface
                            )
                            putInt(QemuIpc.KEY_SURFACE_WIDTH, width)
                            putInt(QemuIpc.KEY_SURFACE_HEIGHT, height)
                            putInt(QemuIpc.KEY_SURFACE_FORMAT, pixelFormat)
                            putFloat(QemuIpc.KEY_SURFACE_REFRESH_RATE, refreshRate)
                        }
                        replyTo = qemuReplyMessenger
                    }
                } else {
                    Message.obtain(
                        null,
                        QemuIpc.MSG_CLEAR_SURFACE
                    ).apply {
                        replyTo = qemuReplyMessenger
                    }
                }

            serviceMessenger.send(message)

            Log.d(
                "RedBoxQemuIPC",
                if (surface != null && surface.isValid) {
                    "Display Surface sent to :qemu process: ${width}x${height}, format=$pixelFormat, refresh=${refreshRate}Hz"
                } else {
                    "Display Surface clear sent to :qemu process"
                }
            )
        } catch (error: Throwable) {
            Log.e(
                "RedBoxQemuIPC",
                "Failed to send display Surface to :qemu process",
                error
            )
        }
    }

    /*
     * Limbo JNI compatibility bridge.
     */
    fun get_fd(path: String): Int {
        return synchronized(limboFileDescriptors) {
            try {
                if (path.isEmpty()) {
                    return@synchronized -1
                }

                val decodedPath =
                    if (path.startsWith("/content//")) {
                        path
                            .replace("/content//", "content://")
                            .replace("^^^", "%")
                    } else {
                        path
                    }

                val parcelFileDescriptor =
                    if (decodedPath.startsWith("content://")) {
                        val uri = Uri.parse(decodedPath)

                        val mode =
                            if (decodedPath.lowercase().endsWith(".iso")) {
                                "r"
                            } else {
                                "rw"
                            }

                        contentResolver.openFileDescriptor(uri, mode)
                            ?: return@synchronized -1
                    } else {
                        val file = File(decodedPath)

                        if (!file.exists()) {
                            file.parentFile?.mkdirs()
                            file.createNewFile()
                        }

                        val mode =
                            if (decodedPath.lowercase().endsWith(".iso")) {
                                ParcelFileDescriptor.MODE_READ_ONLY
                            } else {
                                ParcelFileDescriptor.MODE_READ_WRITE
                            }

                        ParcelFileDescriptor.open(file, mode)
                    }

                val fd = parcelFileDescriptor.fd
                limboFileDescriptors[fd] = parcelFileDescriptor

                Log.d(
                    "RedBoxQEMU",
                    "Limbo get_fd(): $decodedPath -> FD $fd"
                )

                fd
            } catch (error: Exception) {
                Log.e(
                    "RedBoxQEMU",
                    "Limbo get_fd() failed for: $path",
                    error
                )
                -1
            }
        }
    }

    fun close_fd(fd: Int): Int {
        return synchronized(limboFileDescriptors) {
            try {
                val parcelFileDescriptor =
                    limboFileDescriptors.remove(fd)

                if (parcelFileDescriptor != null) {
                    try {
                        parcelFileDescriptor.fileDescriptor.sync()
                    } catch (_: IOException) {
                    }

                    parcelFileDescriptor.close()

                    Log.d(
                        "RedBoxQEMU",
                        "Limbo close_fd(): FD $fd closed"
                    )

                    0
                } else {
                    val fallback =
                        ParcelFileDescriptor.fromFd(fd)

                    try {
                        fallback.fileDescriptor.sync()
                    } catch (_: IOException) {
                    }

                    fallback.close()

                    Log.d(
                        "RedBoxQEMU",
                        "Limbo close_fd(): fallback FD $fd handled"
                    )

                    0
                }
            } catch (error: Exception) {
                Log.e(
                    "RedBoxQEMU",
                    "Limbo close_fd() failed for FD $fd",
                    error
                )
                -1
            }
        }
    }

    /*
     * Extract all QEMU pc-bios assets into getFilesDir().
     * QEMU is started with -L pointing to this directory.
     */
    fun prepareQemuFirmware(): Boolean {
        return try {
            val root = filesDir
            val assetRoot = "pc-bios"

            fun copyAssetTree(
                assetPath: String,
                relativePath: String
            ) {
                val children =
                    assets.list(assetPath) ?: emptyArray()

                if (children.isEmpty()) {
                    val destination =
                        if (relativePath.isEmpty()) {
                            root
                        } else {
                            File(root, relativePath)
                        }

                    destination.parentFile?.mkdirs()

                    assets.open(assetPath).use { input ->
                        destination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    Log.d(
                        "RedBoxQEMU",
                        "Extracted firmware: ${destination.absolutePath}"
                    )
                    return
                }

                for (child in children) {
                    val childAssetPath =
                        "$assetPath/$child"

                    val childRelativePath =
                        if (relativePath.isEmpty()) {
                            child
                        } else {
                            "$relativePath/$child"
                        }

                    copyAssetTree(
                        childAssetPath,
                        childRelativePath
                    )
                }
            }

            copyAssetTree(assetRoot, "")

            val biosFile =
                File(root, "bios-256k.bin")

            if (!biosFile.exists() ||
                biosFile.length() == 0L
            ) {
                Log.e(
                    "RedBoxQEMU",
                    "QEMU BIOS extraction failed"
                )
                false
            } else {
                Log.i(
                    "RedBoxQEMU",
                    "QEMU firmware ready: ${biosFile.absolutePath} (${biosFile.length()} bytes)"
                )
                true
            }
        } catch (error: Exception) {
            Log.e(
                "RedBoxQEMU",
                "Failed to extract QEMU firmware",
                error
            )
            false
        }
    }

    /*
     * Save the VM so it survives app restarts.
     */
    private fun saveVM(vm: VMModel) {
        getSharedPreferences("redbox_vm_storage", MODE_PRIVATE)
            .edit()
            .putString("name", vm.name)
            .putString("architecture", vm.architecture)
            .putString("ram", vm.ram)
            .putString("cpuCores", vm.cpuCores)
            .putString("diskImage", vm.diskImage)
            .putString("diskImageName", vm.diskImageName)
            .putString("isoImage", vm.isoImage)
            .putString("isoImageName", vm.isoImageName)
            .putString("driverIsoImage", vm.driverIsoImage)
            .putString("driverIsoImageName", vm.driverIsoImageName)
            .putString("sharedDiskImage", vm.sharedDiskImage)
            .putString("sharedDiskImageName", vm.sharedDiskImageName)
            .putBoolean("sharedFolderEnabled", vm.sharedFolderEnabled)
            .putString("sharedFolderLastFileName", vm.sharedFolderLastFileName)
            .putString("performancePreset", vm.performancePreset)
            .putString("cpuModel", vm.cpuModel)
            .putString("cpuFlags", vm.cpuFlags)
            .putString("tcgCache", vm.tcgCache)
            .putBoolean("multiThreadedTcg", vm.multiThreadedTcg)
            .putString("machineType", vm.machineType)
            .putString("diskInterface", vm.diskInterface)
            .putString("displayAdapter", vm.displayAdapter)
            .putBoolean("networkEnabled", vm.networkEnabled)
            .putString("networkAdapter", vm.networkAdapter)
            .putString("networkMode", vm.networkMode)
            .putString("qemuParams", vm.qemuParams)
            .putString("biosDate", vm.biosDate)
            .putString("soundCard", vm.soundCard)
            .apply()

        Log.d("RedBoxStorage", "VM saved: ${vm.name}")
    }

    /*
     * Restore the saved VM when RedBox starts.
     */
    private fun loadSavedVM(): VMModel? {
        val preferences =
            getSharedPreferences("redbox_vm_storage", MODE_PRIVATE)

        val name =
            preferences.getString("name", null)
                ?: return null

        return VMModel(
            name = name,
            architecture =
                preferences.getString("architecture", "x86_64")
                    ?: "x86_64",
            ram =
                preferences.getString("ram", "1024 MB")
                    ?: "1024 MB",
            cpuCores =
                preferences.getString("cpuCores", "1")
                    ?: "1",
            diskImage =
                preferences.getString("diskImage", "")
                    ?: "",
            diskImageName =
                preferences.getString("diskImageName", "")
                    ?: "",
            isoImage =
                preferences.getString("isoImage", "")
                    ?: "",
            isoImageName =
                preferences.getString("isoImageName", "")
                    ?: "",
            driverIsoImage =
                preferences.getString("driverIsoImage", "")
                    ?: "",
            driverIsoImageName =
                preferences.getString("driverIsoImageName", "")
                    ?: "",
            sharedDiskImage =
                preferences.getString("sharedDiskImage", "")
                    ?: "",
            sharedDiskImageName =
                preferences.getString("sharedDiskImageName", "")
                    ?: "",
            sharedFolderEnabled =
                preferences.getBoolean("sharedFolderEnabled", false),
            sharedFolderLastFileName =
                preferences.getString("sharedFolderLastFileName", "")
                    ?: "",
            performancePreset =
                preferences.getString("performancePreset", "Balanced")
                    ?: "Balanced",
            cpuModel =
                preferences.getString("cpuModel", "Default")
                    ?: "Default",
            cpuFlags =
                preferences.getString("cpuFlags", "")
                    ?: "",
            tcgCache =
                preferences.getString("tcgCache", "256 MB")
                    ?: "256 MB",
            multiThreadedTcg =
                preferences.getBoolean("multiThreadedTcg", true),
            machineType =
                preferences.getString("machineType", "pc")
                    ?: "pc",
            diskInterface =
                (preferences.getString("diskInterface", "AHCI") ?: "AHCI").let {
                    if (it == "VirtIO") "VirtIO Block" else it
                },
            displayAdapter =
                preferences.getString("displayAdapter", "Standard VGA")
                    ?: "Standard VGA",
            networkEnabled =
                preferences.getBoolean("networkEnabled", true),
            networkAdapter =
                preferences.getString("networkAdapter", "Realtek RTL8139")
                    ?: "Realtek RTL8139",
            networkMode =
                preferences.getString("networkMode", "User (NAT)")
                    ?: "User (NAT)",
            qemuParams =
                preferences.getString("qemuParams", "")
                    ?: "",
            biosDate =
                preferences.getString("biosDate", "Default")
                    ?: "Default",
            soundCard =
                preferences.getString("soundCard", "Intel HDA")
                    ?: "Intel HDA",
            audioBackend = "Default"
        ).also {
            Log.d("RedBoxStorage", "VM restored: ${it.name}")
        }
    }

    /*
     * Keep SDLActivity's SDL thread alive.
     *
     * Limbo overrides runSDLMain() and blocks that thread while QEMU is
     * running. RedBox starts QEMU on its own executor, so we only need to
     * prevent SDLMain from returning immediately (which would make
     * SDLActivity call finish() and close the app).
     */
    private val sdlKeepAliveLatch = CountDownLatch(1)

    @Synchronized
    override fun runSDLMain() {
        Log.d("RedBoxSDL", "SDL main thread entered; keeping SDLActivity alive")

        try {
            sdlKeepAliveLatch.await()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.d("RedBoxSDL", "SDL keep-alive thread interrupted")
        }

        Log.d("RedBoxSDL", "SDL main thread released")
    }

    private val qemuExecutor =
        Executors.newSingleThreadExecutor()

    private val mainHandler =
        Handler(Looper.getMainLooper())

    /*
     * STEP 13C:
     * Physical volume buttons become guest mouse buttons only while the
     * RedBox VM screen is visible.
     *
     * Volume Down = left mouse button
     * Volume Up   = right mouse button
     *
     * This is forwarded through Messenger to QemuService, so input reaches
     * the QEMU instance running inside the isolated :qemu process.
     */
    private fun sendRedBoxMouseButton(
        button: Int,
        down: Boolean
    ) {
        val serviceMessenger = qemuServiceMessenger

        if (!qemuProcessActive.get() ||
            !qemuServiceBound ||
            serviceMessenger == null
        ) {
            return
        }

        try {
            val message =
                Message.obtain(
                    null,
                    QemuIpc.MSG_MOUSE_BUTTON
                ).apply {
                    data = Bundle().apply {
                        putInt(
                            QemuIpc.KEY_MOUSE_BUTTON,
                            button
                        )
                        putBoolean(
                            QemuIpc.KEY_MOUSE_BUTTON_DOWN,
                            down
                        )
                    }
                }

            serviceMessenger.send(message)
        } catch (error: RemoteException) {
            Log.e(
                "RedBoxQemuIPC",
                "Failed to send mouse button to :qemu process",
                error
            )
        }
    }

    private fun sendRedBoxKeyboardKey(
        keyCode: Int,
        down: Boolean
    ) {
        val serviceMessenger = qemuServiceMessenger

        if (!qemuProcessActive.get() ||
            !qemuServiceBound ||
            serviceMessenger == null
        ) {
            return
        }

        try {
            val message =
                Message.obtain(
                    null,
                    QemuIpc.MSG_KEYBOARD_KEY
                ).apply {
                    data = Bundle().apply {
                        putInt(QemuIpc.KEY_KEYBOARD_KEY_CODE, keyCode)
                        putBoolean(QemuIpc.KEY_KEYBOARD_KEY_DOWN, down)
                    }
                }

            serviceMessenger.send(message)
        } catch (error: RemoteException) {
            Log.e(
                "RedBoxQemuIPC",
                "Failed to send keyboard key to :qemu process",
                error
            )
        }
    }

    private fun isRedBoxKeyboardKey(keyCode: Int): Boolean {
        return keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
            keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ||
            keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_FORWARD_DEL,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_CAPS_LOCK,
                KeyEvent.KEYCODE_INSERT,
                KeyEvent.KEYCODE_MOVE_HOME,
                KeyEvent.KEYCODE_MOVE_END,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_COMMA,
                KeyEvent.KEYCODE_PERIOD,
                KeyEvent.KEYCODE_GRAVE,
                KeyEvent.KEYCODE_MINUS,
                KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_LEFT_BRACKET,
                KeyEvent.KEYCODE_RIGHT_BRACKET,
                KeyEvent.KEYCODE_BACKSLASH,
                KeyEvent.KEYCODE_SEMICOLON,
                KeyEvent.KEYCODE_APOSTROPHE,
                KeyEvent.KEYCODE_SLASH
            )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (redBoxVmScreenVisible && qemuProcessActive.get()) {
            val qemuButton = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> 0
                KeyEvent.KEYCODE_VOLUME_UP -> 2
                else -> -1
            }

            if (qemuButton != -1) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            sendRedBoxMouseButton(
                                qemuButton,
                                true
                            )
                        }
                    }

                    KeyEvent.ACTION_UP -> {
                        sendRedBoxMouseButton(
                            qemuButton,
                            false
                        )
                    }
                }

                // Consume the volume key so Android volume does not change.
                return true
            }

            if (isRedBoxKeyboardKey(event.keyCode)) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        sendRedBoxKeyboardKey(
                            event.keyCode,
                            true
                        )
                    }

                    KeyEvent.ACTION_UP -> {
                        sendRedBoxKeyboardKey(
                            event.keyCode,
                            false
                        )
                    }
                }

                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bindFreshQemuService()

        val nativeMessage = stringFromJNI()
        Log.d("RedBoxNative", nativeMessage)

        val qemuStatus = nativeQemuStatus()
        Log.d("RedBoxQEMU", qemuStatus)

        val savedVM = loadSavedVM()

        setContent {
            RedBoxApp(
                qemuStatus = qemuStatus,
                initialVM = savedVM,
                onVMSaved = { vm ->
                    saveVM(vm)
                },
                onStopQemu = {
                    val serviceMessenger = qemuServiceMessenger

                    if (!qemuServiceBound || serviceMessenger == null) {
                        Log.e("RedBoxQemuIPC", "Cannot stop VM: :qemu service is not connected")
                        false
                    } else {
                        try {
                            val message = Message.obtain(null, QemuIpc.MSG_STOP_VM)
                            message.replyTo = qemuReplyMessenger
                            serviceMessenger.send(message)
                            Log.d("RedBoxQemuIPC", "Sent MSG_STOP_VM to :qemu process")
                            true
                        } catch (error: RemoteException) {
                            Log.e("RedBoxQemuIPC", "Failed to send MSG_STOP_VM", error)
                            false
                        }
                    }
                },
                onStartQemu = startQemu@ { vm, onResult ->
                    val serviceMessenger = qemuServiceMessenger

                    if (!qemuServiceBound || serviceMessenger == null) {
                        Log.e("RedBoxQemuIPC", "Cannot start VM: :qemu service is not connected")
                        mainHandler.post { onResult("QEMU service is not connected") }
                        return@startQemu
                    }

                    if (!qemuProcessActive.compareAndSet(false, true)) {
                        Log.w("RedBoxQemuIPC", "QEMU start ignored because a VM is already active")
                        mainHandler.post { onResult("QEMU VM is already running") }
                        return@startQemu
                    }

                    qemuSessionEndedCallback = onResult

                    try {
                        val ramMb = vm.ram.filter { it.isDigit() }.toIntOrNull() ?: 1024
                        val cpuCores = vm.cpuCores.filter { it.isDigit() }.toIntOrNull() ?: 1
                        val tcgCacheMb = vm.tcgCache.substringBefore(" ").toIntOrNull() ?: 256

                        if (vm.sharedFolderEnabled) {
                            File(filesDir, "redbox_shared").mkdirs()
                        }

                        val data = Bundle().apply {
                            putInt(QemuIpc.KEY_RAM_MB, ramMb)
                            putInt(QemuIpc.KEY_CPU_CORES, cpuCores)
                            putString("disk_uri", vm.diskImage)
                            putString(QemuIpc.KEY_DISK_IMAGE_NAME, vm.diskImageName)
                            putString("iso_uri", vm.isoImage)
                            putString("driver_iso_uri", vm.driverIsoImage)
                            putString("shared_disk_uri", vm.sharedDiskImage)
                            putString(QemuIpc.KEY_SHARED_DISK_IMAGE_NAME, vm.sharedDiskImageName)
                            putBoolean(QemuIpc.KEY_SHARED_FOLDER_ENABLED, vm.sharedFolderEnabled)
                            putString(QemuIpc.KEY_CPU_MODEL, vm.cpuModel)
                            putString(QemuIpc.KEY_CPU_FLAGS, vm.cpuFlags)
                            putInt(QemuIpc.KEY_TCG_CACHE_MB, tcgCacheMb)
                            putBoolean(QemuIpc.KEY_MULTI_THREADED_TCG, vm.multiThreadedTcg)
                            putString(QemuIpc.KEY_MACHINE_TYPE, vm.machineType)
                            putString(QemuIpc.KEY_DISK_INTERFACE, vm.diskInterface)
                            putString(QemuIpc.KEY_DISPLAY_ADAPTER, vm.displayAdapter)
                            putBoolean(QemuIpc.KEY_NETWORK_ENABLED, vm.networkEnabled)
                            putString(QemuIpc.KEY_NETWORK_ADAPTER, vm.networkAdapter)
                            putString(QemuIpc.KEY_NETWORK_MODE, vm.networkMode)
                            putString(QemuIpc.KEY_QEMU_PARAMS, vm.qemuParams)
                            putString(QemuIpc.KEY_BIOS_DATE, vm.biosDate)
                            putString(QemuIpc.KEY_SOUND_CARD, vm.soundCard)
                        }

                        val message = Message.obtain(null, QemuIpc.MSG_START_VM)
                        message.data = data
                        message.replyTo = qemuReplyMessenger

                        Log.d(
                            "RedBoxQemuIPC",
                            "Sending VM to :qemu: RAM=${ramMb}MB, CPU=$cpuCores, model=${vm.cpuModel}, disk=${vm.diskInterface}, display=${vm.displayAdapter}"
                        )

                        serviceMessenger.send(message)

                        Log.d(
                            "RedBoxQemuIPC",
                            "MSG_START_VM sent. UI PID=${android.os.Process.myPid()}"
                        )
                    } catch (error: Throwable) {
                        qemuProcessActive.set(false)
                        qemuSessionEndedCallback = null
                        Log.e("RedBoxQemuIPC", "Failed to send VM start request", error)
                        mainHandler.post { onResult("QEMU start failed: ${error.message}") }
                    }
                }
            )
        }
    }

    override fun onConfigurationChanged(
        newConfig: Configuration
    ) {
        super.onConfigurationChanged(newConfig)

        val orientationName =
            when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE ->
                    "landscape"

                Configuration.ORIENTATION_PORTRAIT ->
                    "portrait"

                else ->
                    "undefined"
            }

        Log.d(
            "RedBoxRotation",
            "Configuration changed in-place: $orientationName"
        )
    }

    override fun onDestroy() {
        /*
         * Normal rotation is handled in-place by AndroidManifest configChanges,
         * so this path should not run for rotation. This extra check protects
         * the native VM if Android ever recreates us for another configuration.
         */
        if (isChangingConfigurations) {
            Log.d(
                "RedBoxRotation",
                "Activity changing configuration; keeping QEMU/SDL worker alive"
            )

            super.onDestroy()
            return
        }

        if (qemuServiceBound) {
            try {
                unbindService(
                    qemuServiceConnection
                )
            } catch (error: IllegalArgumentException) {
                Log.w(
                    "RedBoxQemuIPC",
                    "QEMU service was already unbound",
                    error
                )
            }

            qemuServiceBound = false
            qemuServiceMessenger = null
        }

        sdlKeepAliveLatch.countDown()
        qemuExecutor.shutdownNow()
        super.onDestroy()
    }
}

@Composable
fun RedBoxApp(
    qemuStatus: String,
    initialVM: VMModel?,
    onVMSaved: (VMModel) -> Unit,
    onStopQemu: () -> Boolean,
    onStartQemu: ((VMModel, (String) -> Unit) -> Unit)
) {
    var showCreateVM by rememberSaveable { mutableStateOf(false) }
    var showEditVM by rememberSaveable { mutableStateOf(false) }
    var showVMDetails by rememberSaveable { mutableStateOf(false) }
    var showVMScreen by rememberSaveable { mutableStateOf(false) }

    var selectedTab by rememberSaveable {
        mutableIntStateOf(0)
    }

    var selectedFileFilter by rememberSaveable {
        mutableStateOf("All")
    }

    var createdVM by remember {
        mutableStateOf<VMModel?>(initialVM)
    }

    var isVMRunning by rememberSaveable {
        mutableStateOf(false)
    }

    var qemuRuntimeStatus by rememberSaveable {
        mutableStateOf("")
    }

    var darkTheme by rememberSaveable {
        mutableStateOf(true)
    }

    /*
     * STEP 13B.2:
     * Android system Back button should navigate inside RedBox instead of
     * closing the app.
     *
     * Priority:
     * Create VM -> Home
     * Edit VM -> VM Details
     * VM Screen -> VM Details
     * VM Details -> previous app screen
     * Other bottom tabs -> Home
     * Home -> stay in RedBox
     */
    BackHandler(enabled = true) {
        when {
            showCreateVM -> {
                showCreateVM = false
            }

            showEditVM -> {
                showEditVM = false
                showVMDetails = true
            }

            showVMScreen -> {
                showVMScreen = false
                showVMDetails = true
            }

            showVMDetails -> {
                showVMDetails = false
            }

            selectedTab != 0 -> {
                selectedTab = 0
            }

            else -> {
                Log.d(
                    "RedBoxNavigation",
                    "System Back ignored on Home so RedBox stays open"
                )
            }
        }
    }

    if (showCreateVM) {
        CreateVMScreen(
            onBack = {
                showCreateVM = false
            },
            onVMCreated = { vm ->
                createdVM = vm
                onVMSaved(vm)
                isVMRunning = false
                qemuRuntimeStatus = ""
                showCreateVM = false
                selectedTab = 0
            }
        )
        return
    }

    if (showEditVM && createdVM != null) {
        EditVMScreen(
            vm = createdVM!!,
            onBack = {
                showEditVM = false
                showVMDetails = true
            },
            onSave = { updatedVM ->
                createdVM = updatedVM
                onVMSaved(updatedVM)
                isVMRunning = false
                qemuRuntimeStatus = ""
                showEditVM = false
                showVMDetails = true

                Log.d(
                    "RedBoxStorage",
                    "VM edited and saved: ${updatedVM.name}, RAM=${updatedVM.ram}, CPU=${updatedVM.cpuCores}"
                )
            }
        )
        return
    }

    if (showVMScreen && createdVM != null) {
        VMScreen(
            vm = createdVM!!,
            onStop = {
                qemuRuntimeStatus = "Stopping QEMU..."
                val stopRequested = onStopQemu()

                if (stopRequested) {
                    showVMScreen = false
                    showVMDetails = true
                } else {
                    qemuRuntimeStatus = "QEMU stop request failed"
                }
            },
            onBack = {
                showVMScreen = false
                showVMDetails = true
            }
        )
        return
    }

    if (showVMDetails && createdVM != null) {
        VMDetailsScreen(
            vm = createdVM!!,
            isRunning = isVMRunning,
            qemuRuntimeStatus = qemuRuntimeStatus,
            onEditVM = {
                if (!isVMRunning) {
                    showVMDetails = false
                    showEditVM = true
                }
            },
            onStartVM = {
                isVMRunning = true
                showVMDetails = false
                showVMScreen = true
                qemuRuntimeStatus = "Starting QEMU..."

                onStartQemu(createdVM!!) { result ->
                    qemuRuntimeStatus = result
                    isVMRunning = false

                    Log.d(
                        "RedBoxQEMU",
                        "Result returned to UI: $result"
                    )
                }
            },
            onStopVM = {
                qemuRuntimeStatus = "Stopping QEMU..."

                if (!onStopQemu()) {
                    qemuRuntimeStatus = "QEMU stop request failed"
                }
            },
            onBack = {
                showVMDetails = false
            }
        )
        return
    }

    RedBoxMaterialTheme(darkTheme = darkTheme) {
        Scaffold(
            containerColor =
                MaterialTheme.colorScheme.background,
            bottomBar = {
                RedBoxBottomBar(
                    selectedTab = selectedTab,
                    onSelected = {
                        selectedTab = it
                    }
                )
            }
        ) { innerPadding ->

            when (selectedTab) {
                0 -> RedBoxHomeMaterial(
                    modifier = Modifier.padding(innerPadding),
                    createdVM = createdVM,
                    isRunning = isVMRunning,
                    qemuStatus = qemuStatus,
                    onCreateVM = {
                        showCreateVM = true
                    },
                    onVMClick = {
                        if (createdVM != null) {
                            showVMDetails = true
                        }
                    },
                    onVmsClick = {
                        selectedTab = 1
                    },
                    onQuickAccess = { category ->
                        selectedFileFilter = category
                        selectedTab = 2
                    }
                )

                1 -> RedBoxVMsMaterial(
                    modifier = Modifier.padding(innerPadding),
                    createdVM = createdVM,
                    isRunning = isVMRunning,
                    onCreateVM = {
                        showCreateVM = true
                    },
                    onVMClick = {
                        if (createdVM != null) {
                            showVMDetails = true
                        }
                    }
                )

                2 -> RedBoxFilesMaterial(
                    modifier = Modifier.padding(innerPadding),
                    createdVM = createdVM,
                    selectedFilter = selectedFileFilter,
                    onFilterChanged = { selectedFileFilter = it }
                )

                else -> RedBoxSettingsMaterial(
                    modifier = Modifier.padding(innerPadding),
                    darkTheme = darkTheme,
                    onDarkThemeChanged = {
                        darkTheme = it
                    }
                )
            }
        }
    }
}

@Composable
fun RedBoxMaterialTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val darkColors = darkColorScheme(
        primary = Color(0xFFFF3345),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF5A111A),
        onPrimaryContainer = Color(0xFFFFDAD9),
        secondary = Color(0xFFBFC6D1),
        background = Color(0xFF07090C),
        surface = Color(0xFF101318),
        surfaceVariant = Color(0xFF1A1E24),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFC2C7D0)
    )

    val lightColors = lightColorScheme(
        primary = Color(0xFFD7192B),
        onPrimary = Color.White,
        background = Color(0xFFF7F7F9),
        surface = Color.White,
        surfaceVariant = Color(0xFFE9E9EE),
        onBackground = Color(0xFF15161A),
        onSurface = Color(0xFF15161A),
        onSurfaceVariant = Color(0xFF555862)
    )

    MaterialTheme(
        colorScheme =
            if (darkTheme) darkColors else lightColors,
        typography = Typography(),
        content = content
    )
}

@Composable
private fun RedBoxBottomBar(
    selectedTab: Int,
    onSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        RedBoxNavigationItem(
            selected = selectedTab == 0,
            icon = "⌂",
            label = "Home",
            onClick = { onSelected(0) }
        )

        RedBoxNavigationItem(
            selected = selectedTab == 1,
            icon = "▣",
            label = "VMs",
            onClick = { onSelected(1) }
        )

        RedBoxNavigationItem(
            selected = selectedTab == 2,
            icon = "□",
            label = "Files",
            onClick = { onSelected(2) }
        )

        RedBoxNavigationItem(
            selected = selectedTab == 3,
            icon = "⚙",
            label = "Settings",
            onClick = { onSelected(3) }
        )
    }
}

@Composable
private fun RowScope.RedBoxNavigationItem(
    selected: Boolean,
    icon: String,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RedBoxHomeMaterial(
    modifier: Modifier,
    createdVM: VMModel?,
    isRunning: Boolean,
    qemuStatus: String,
    onCreateVM: () -> Unit,
    onVMClick: () -> Unit,
    onVmsClick: () -> Unit,
    onQuickAccess: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = 18.dp,
                vertical = 12.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RedBoxLogo(
                modifier = Modifier.size(46.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "RedBox",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "PC Emulator",
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color =
                    MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "v0.1.2",
                    modifier = Modifier.padding(
                        horizontal = 11.dp,
                        vertical = 7.dp
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Welcome",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text =
                "Run virtual machines on your Android device.",
            color =
                MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onCreateVM,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor =
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = "＋  Create Virtual Machine",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        SectionHeader(
            title = "Your VMs",
            action =
                if (createdVM != null) "See all" else null,
            onAction = onVmsClick
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (createdVM == null) {
            EmptyVMCard(onCreateVM)
        } else {
            VMHomeCard(
                vm = createdVM,
                isRunning = isRunning,
                onClick = onVMClick
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Quick Access",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {
            QuickAccessCard(
                modifier = Modifier.weight(1f),
                icon = "▣",
                title = "Windows",
                onClick = { onQuickAccess("Windows") }
            )

            QuickAccessCard(
                modifier = Modifier.weight(1f),
                icon = "●",
                title = "Android",
                onClick = { onQuickAccess("Android") }
            )

            QuickAccessCard(
                modifier = Modifier.weight(1f),
                icon = "◈",
                title = "Linux",
                onClick = { onQuickAccess("Linux") }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader(title = "Engine")

        Spacer(modifier = Modifier.height(10.dp))

        EngineCard(qemuStatus)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )

        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = action,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun EmptyVMCard(onCreateVM: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "▣",
                fontSize = 34.sp,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "No virtual machines yet",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Create your first VM to get started.",
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedButton(
                onClick = onCreateVM,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Create VM")
            }
        }
    }
}

@Composable
private fun VMHomeCard(
    vm: VMModel,
    isRunning: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = RoundedCornerShape(15.dp),
                    color =
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "▣",
                            fontSize = 24.sp,
                            color =
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = vm.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = vm.architecture,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                StatusPill(isRunning)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text =
                    "${vm.ram}  •  ${vm.cpuCores}",
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(5.dp))

            val storageName =
                when {
                    vm.diskImageName.isNotEmpty() ->
                        vm.diskImageName

                    vm.isoImageName.isNotEmpty() ->
                        vm.isoImageName

                    else ->
                        "No disk image selected"
                }

            Text(
                text = storageName,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StatusPill(isRunning: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color =
            if (isRunning) {
                Color(0xFF123D28)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
    ) {
        Text(
            text =
                if (isRunning) "● Running" else "Stopped",
            modifier = Modifier.padding(
                horizontal = 10.dp,
                vertical = 6.dp
            ),
            color =
                if (isRunning) {
                    Color(0xFF62E59B)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun QuickAccessCard(
    modifier: Modifier,
    icon: String,
    title: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 17.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = icon,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(7.dp))

            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EngineCard(qemuStatus: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color =
                    MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "Q",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color =
                            MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "QEMU",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Text(
                    text = "Virtual machine engine",
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            Text(
                text = "●",
                color = Color(0xFF55D98B),
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun RedBoxVMsMaterial(
    modifier: Modifier,
    createdVM: VMModel?,
    isRunning: Boolean,
    onCreateVM: () -> Unit,
    onVMClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = 18.dp,
                vertical = 14.dp
            )
    ) {
        Text(
            text = "VMs",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = "Manage your virtual machines",
            color =
                MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text("All") }
            )

            FilterChip(
                selected = false,
                onClick = {},
                label = { Text("Running") }
            )

            FilterChip(
                selected = false,
                onClick = {},
                label = { Text("Stopped") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (createdVM == null) {
            EmptyVMCard(onCreateVM)
        } else {
            VMHomeCard(
                vm = createdVM,
                isRunning = isRunning,
                onClick = onVMClick
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        FloatingActionButton(
            onClick = onCreateVM,
            containerColor =
                MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(
                text = "+",
                fontSize = 24.sp
            )
        }
    }
}

@Composable
private fun RedBoxFilesMaterial(
    modifier: Modifier,
    createdVM: VMModel?,
    selectedFilter: String,
    onFilterChanged: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = 18.dp,
                vertical = 14.dp
            )
    ) {
        Text(
            text = "Files",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = "Disk images and ISO files",
            color =
                MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Windows", "Android", "Linux").forEach { option ->
                FilterChip(
                    selected = selectedFilter == option,
                    onClick = { onFilterChanged(option) },
                    label = { Text(option) }
                )
            }
        }

        if (selectedFilter != "All") {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$selectedFilter quick access",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        FileCategoryCard(
            title = "Disk Image",
            subtitle =
                createdVM?.diskImageName
                    ?.takeIf { it.isNotEmpty() }
                    ?: "No disk image selected",
            icon = "□"
        )

        Spacer(modifier = Modifier.height(12.dp))

        FileCategoryCard(
            title = "ISO Image",
            subtitle =
                createdVM?.isoImageName
                    ?.takeIf { it.isNotEmpty() }
                    ?: "No ISO image selected",
            icon = "◉"
        )

        Spacer(modifier = Modifier.height(22.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Supported formats",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = ".img  •  .qcow2  •  .vhd  •  .iso",
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun FileCategoryCard(
    title: String,
    subtitle: String,
    icon: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color =
                    MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = icon,
                        fontSize = 23.sp,
                        color =
                            MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = subtitle,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            Text(
                text = "›",
                fontSize = 27.sp,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RedBoxSettingsMaterial(
    modifier: Modifier,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var showAboutDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About RedBox") },
            text = {
                Text(
                    "RedBox PC Emulator\nVersion 0.1.2\n\n" +
                            "Run. Explore. Create."
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("Check for Update") },
            text = {
                Text(
                    "Installed version: 0.1.0\n\n" +
                            "The update button is working. An online update source can be connected later when RedBox has a release page or update server."
                )
            },
            confirmButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showStorageDialog) {
        AlertDialog(
            onDismissRequest = { showStorageDialog = false },
            title = { Text("Storage Location") },
            text = {
                Text(
                    "RedBox internal storage:\n${context.filesDir.absolutePath}\n\n" +
                            "Temporary cache:\n${context.cacheDir.absolutePath}"
                )
            },
            confirmButton = {
                TextButton(onClick = { showStorageDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Cache") },
            text = { Text("Delete RedBox temporary cache files?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleared = try {
                            context.cacheDir.listFiles()?.forEach { file ->
                                file.deleteRecursively()
                            }
                            true
                        } catch (error: Exception) {
                            Log.e("RedBoxSettings", "Failed to clear cache", error)
                            false
                        }

                        Toast.makeText(
                            context,
                            if (cleared) "Cache cleared" else "Could not clear cache",
                            Toast.LENGTH_SHORT
                        ).show()

                        showClearCacheDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = 18.dp,
                vertical = 14.dp
            )
    ) {
        Text(
            text = "Settings",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(18.dp))

        SettingsCard {
            SettingsRow(
                icon = "☼",
                title = "Dark Theme",
                subtitle =
                    if (darkTheme) {
                        "Dark appearance"
                    } else {
                        "Light appearance"
                    },
                trailing = {
                    Switch(
                        checked = darkTheme,
                        onCheckedChange =
                            onDarkThemeChanged
                    )
                }
            )

            SettingsDivider()

            SettingsRow(
                icon = "ⓘ",
                title = "About RedBox",
                subtitle = "RedBox PC Emulator",
                onClick = { showAboutDialog = true }
            )

            SettingsDivider()

            SettingsRow(
                icon = "↻",
                title = "Check for Update",
                subtitle = "Version 0.1.2",
                onClick = { showUpdateDialog = true }
            )

            SettingsDivider()

            SettingsRow(
                icon = "□",
                title = "Storage Location",
                subtitle = "Internal Storage",
                onClick = { showStorageDialog = true }
            )

            SettingsDivider()

            SettingsRow(
                icon = "⌫",
                title = "Clear Cache",
                subtitle = "Manage temporary files",
                onClick = { showClearCacheDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RedBoxLogo(
                    modifier = Modifier.size(52.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "RedBox PC Emulator",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Text(
                        text = "Version 0.1.2",
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = "Run. Explore. Create.",
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Material Design 3  •  RedBox",
            modifier = Modifier.fillMaxWidth(),
            color =
                MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = 17.dp,
                vertical = 16.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            fontSize = 23.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(34.dp)
        )

        Spacer(modifier = Modifier.width(9.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )

            Text(
                text = subtitle,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }

        if (trailing != null) {
            trailing()
        } else {
            Text(
                text = "›",
                fontSize = 25.sp,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 17.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
private fun RedBoxLogo(
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(
            id = R.drawable.ic_redbox
        ),
        contentDescription = "RedBox PC Emulator",
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VMDetailsScreen(
    vm: VMModel,
    isRunning: Boolean,
    qemuRuntimeStatus: String,
    onEditVM: () -> Unit,
    onStartVM: () -> Unit,
    onStopVM: () -> Unit,
    onBack: () -> Unit
) {
    RedBoxMaterialTheme(darkTheme = true) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = vm.name,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Virtual Machine",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        TextButton(onClick = onBack) {
                            Text(
                                text = "‹",
                                fontSize = 30.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(64.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "▣",
                                        fontSize = 30.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = vm.name,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = vm.architecture,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isRunning) {
                                    Color(0xFF123D28)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = if (isRunning) "● Running" else "Stopped",
                                    modifier = Modifier.padding(
                                        horizontal = 11.dp,
                                        vertical = 7.dp
                                    ),
                                    color = if (isRunning) {
                                        Color(0xFF62E59B)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "System",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(9.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        DetailRowMaterial(
                            icon = "▣",
                            title = "Architecture",
                            value = vm.architecture
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        DetailRowMaterial(
                            icon = "▤",
                            title = "Memory",
                            value = vm.ram
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        DetailRowMaterial(
                            icon = "⚙",
                            title = "CPU Cores",
                            value = vm.cpuCores
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "CPU Performance",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(9.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        DetailRowMaterial(
                            icon = "◈",
                            title = "Preset",
                            value = vm.performancePreset
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        DetailRowMaterial(
                            icon = "C",
                            title = "CPU Model",
                            value = vm.cpuModel
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        DetailRowMaterial(
                            icon = "T",
                            title = "TCG Cache",
                            value = vm.tcgCache
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        DetailRowMaterial(
                            icon = "≡",
                            title = "Multi-threaded TCG",
                            value = if (vm.multiThreadedTcg) "Enabled" else "Disabled"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        DetailRowMaterial(
                            icon = "M",
                            title = "Machine Type",
                            value = if (vm.machineType == "pc") "PC (i440FX)" else "Q35"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        DetailRowMaterial(
                            icon = "D",
                            title = "Disk Interface",
                            value = vm.diskInterface
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        DetailRowMaterial(
                            icon = "G",
                            title = "Display Adapter",
                            value = vm.displayAdapter
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Storage",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(9.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        StorageRowMaterial(
                            icon = "□",
                            title = "Disk Image",
                            value = vm.diskImageName.ifEmpty {
                                "No disk image selected"
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 13.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )

                        StorageRowMaterial(
                            icon = "◉",
                            title = "ISO Image",
                            value = vm.isoImageName.ifEmpty {
                                "No ISO image selected"
                            }
                        )
                    }
                }

                if (qemuRuntimeStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(18.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "QEMU Engine",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = qemuRuntimeStatus,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                OutlinedButton(
                    onClick = onEditVM,
                    enabled = !isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(
                        text = if (isRunning) "Edit VM (stop VM first)" else "✎  Edit VM",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = if (isRunning) onStopVM else onStartVM,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) {
                            Color(0xFFB3261E)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                ) {
                    Text(
                        text = if (isRunning) "Stop VM" else "Start VM",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Back to VMs")
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingsGroupTitle(
    title: String,
    subtitle: String
) {
    Spacer(modifier = Modifier.height(18.dp))
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = subtitle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditVMScreen(
    vm: VMModel,
    onBack: () -> Unit,
    onSave: (VMModel) -> Unit
) {
    val context = LocalContext.current

    var name by remember(vm) { mutableStateOf(vm.name) }
    var architecture by remember(vm) { mutableStateOf(vm.architecture) }
    var ram by remember(vm) { mutableStateOf(vm.ram) }
    var cpuCores by remember(vm) { mutableStateOf(vm.cpuCores) }

    var performancePreset by remember(vm) { mutableStateOf(vm.performancePreset) }
    var cpuModel by remember(vm) { mutableStateOf(vm.cpuModel) }
    var cpuFlags by remember(vm) { mutableStateOf(vm.cpuFlags) }
    var tcgCache by remember(vm) { mutableStateOf(vm.tcgCache) }
    var multiThreadedTcg by remember(vm) { mutableStateOf(vm.multiThreadedTcg) }
    var machineType by remember(vm) { mutableStateOf(vm.machineType) }
    var showCpuOptions by remember(vm) { mutableStateOf(false) }
    var showRamOptions by remember(vm) { mutableStateOf(false) }
    var showMachineOptions by remember(vm) { mutableStateOf(false) }
    var diskInterface by remember(vm) { mutableStateOf(vm.diskInterface) }
    var displayAdapter by remember(vm) { mutableStateOf(vm.displayAdapter) }

    // RedBox blank disk creator.
    var showCreateDiskDialog by remember(vm) { mutableStateOf(false) }
    var newDiskName by remember(vm) { mutableStateOf("RedBoxDisk.img") }
    var newDiskSizeGb by remember(vm) { mutableIntStateOf(32) }

    var networkEnabled by remember(vm) { mutableStateOf(vm.networkEnabled) }
    var networkAdapter by remember(vm) { mutableStateOf(vm.networkAdapter) }
    var networkMode by remember(vm) { mutableStateOf(vm.networkMode) }
    var qemuParams by remember(vm) { mutableStateOf(vm.qemuParams) }
    var biosDate by remember(vm) { mutableStateOf(vm.biosDate) }
    var soundCard by remember(vm) { mutableStateOf(vm.soundCard) }

    var diskImage by remember(vm) { mutableStateOf(vm.diskImage) }
    var diskImageName by remember(vm) { mutableStateOf(vm.diskImageName) }

    var isoImage by remember(vm) { mutableStateOf(vm.isoImage) }
    var isoImageName by remember(vm) { mutableStateOf(vm.isoImageName) }
    var driverIsoImage by remember(vm) { mutableStateOf(vm.driverIsoImage) }
    var driverIsoImageName by remember(vm) { mutableStateOf(vm.driverIsoImageName) }
    var sharedDiskImage by remember(vm) { mutableStateOf(vm.sharedDiskImage) }
    var sharedDiskImageName by remember(vm) { mutableStateOf(vm.sharedDiskImageName) }
    var sharedFolderEnabled by remember(vm) { mutableStateOf(vm.sharedFolderEnabled) }
    var sharedFolderLastFileName by remember(vm) { mutableStateOf(vm.sharedFolderLastFileName) }

    fun displayName(uri: Uri): String {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index) ?: uri.lastPathSegment.orEmpty()
                } else {
                    uri.lastPathSegment.orEmpty()
                }
            } ?: uri.lastPathSegment.orEmpty()
        } catch (_: Exception) {
            uri.lastPathSegment.orEmpty()
        }
    }

    fun persistUri(uri: Uri) {
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
    }

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

                persistUri(uri)
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
            persistUri(uri)
            diskImage = uri.toString()
            diskImageName = displayName(uri)
        }
    }

    val isoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistUri(uri)
            isoImage = uri.toString()
            isoImageName = displayName(uri)
        }
    }

    val driverIsoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistUri(uri)
            driverIsoImage = uri.toString()
            driverIsoImageName = displayName(uri)
        }
    }

    val sharedDiskPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistUri(uri)
            sharedDiskImage = uri.toString()
            sharedDiskImageName = displayName(uri)
        }
    }

    fun importFileToSharedFolder(uri: Uri) {
        try {
            val safeName = File(displayName(uri)).name.ifBlank { "shared_file" }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("1", "2", "4", "6", "8").forEach { option ->
                            FilterChip(
                                selected = cpuCores == option,
                                onClick = { cpuCores = option },
                                label = { Text(option) }
                            )
                        }
                    }

                    Text("Preset", fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Compatibility", "Balanced").forEach { option ->
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
                                        }
                                    },
                                    label = { Text(option) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Performance", "Custom").forEach { option ->
                                FilterChip(
                                    selected = performancePreset == option,
                                    onClick = {
                                        performancePreset = option
                                        if (option == "Performance") {
                                            cpuModel = "Default"
                                            tcgCache = "512 MB"
                                            multiThreadedTcg = true
                                            machineType = "pc"
                                        }
                                    },
                                    label = { Text(option) }
                                )
                            }
                        }
                    }

                    Text("CPU Model", fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    }

                    Text("CPU Flags", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Optional CPU features. Default CPU Model becomes qemu64 when flags are enabled.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        text = "Sparse RAW uses little space at first and grows as the guest writes data.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

    RedBoxMaterialTheme(darkTheme = true) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Edit VM",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = vm.name,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        TextButton(onClick = onBack) {
                            Text(
                                text = "‹",
                                fontSize = 30.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "General",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("VM Name") },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Architecture",
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("x86_64", "x86").forEach { option ->
                            FilterChip(
                                selected = architecture == option,
                                onClick = { architecture = option },
                                label = { Text(option) }
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("ARM64", "ARM").forEach { option ->
                            FilterChip(
                                selected = architecture == option,
                                onClick = { architecture = option },
                                label = { Text(option) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "System Configuration",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCpuOptions = true },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CPU", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "$cpuCores Cores · $cpuModel · $performancePreset",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Text("›", fontSize = 30.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showRamOptions = true },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RAM", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(
                                ram,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Text("›", fontSize = 30.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showMachineOptions = true },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Machine", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (machineType == "pc") "PC (i440FX)" else "Q35",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Text("›", fontSize = 30.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Display Adapter",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when (displayAdapter) {
                        "Bochs Display" -> "Modern software framebuffer. Worth testing for better desktop responsiveness."
                        "VirtIO VGA" -> "Paravirtualized graphics. Guest driver support may be required."
                        "Cirrus VGA" -> "Legacy graphics adapter for older operating systems."
                        else -> "Standard VGA is the safest compatibility option."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Standard VGA", "Bochs Display").forEach { option ->
                            FilterChip(
                                selected = displayAdapter == option,
                                onClick = { displayAdapter = option },
                                label = { Text(option) }
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("VirtIO VGA", "Cirrus VGA").forEach { option ->
                            FilterChip(
                                selected = displayAdapter == option,
                                onClick = { displayAdapter = option },
                                label = { Text(option) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Disk Interface",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Choose how the virtual hard disk is connected.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = diskInterface == "AHCI",
                            onClick = {
                                diskInterface = "AHCI"
                                performancePreset = "Custom"
                            },
                            label = { Text("AHCI") }
                        )

                        FilterChip(
                            selected = diskInterface == "IDE",
                            onClick = {
                                diskInterface = "IDE"
                                machineType = "pc"
                                performancePreset = "Custom"
                            },
                            label = { Text("IDE") }
                        )

                        FilterChip(
                            selected = diskInterface == "VirtIO Block",
                            onClick = {
                                diskInterface = "VirtIO Block"
                                performancePreset = "Custom"
                            },
                            label = { Text("VirtIO Block") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when (diskInterface) {
                        "IDE" -> "IDE uses PC (i440FX) machine mode for compatibility."
                        "VirtIO Block" -> "VirtIO Block can reduce disk emulation overhead, but Windows needs a VirtIO storage driver before it can boot from this disk mode."
                        else -> "AHCI provides SATA-style disk compatibility for supported guests."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Storage",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Text(
                            text = "Disk Image",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        Text(
                            text = diskImageName.ifEmpty { "No disk image selected" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 2
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                diskPicker.launch(
                                    arrayOf(
                                        "application/octet-stream",
                                        "application/x-qemu-disk",
                                        "*/*"
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Choose Disk Image")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { showCreateDiskDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Create New Disk")
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Creates a blank sparse RAW .img disk for IDE, AHCI or VirtIO Block.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "ISO Image",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        Text(
                            text = isoImageName.ifEmpty { "No ISO image selected" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 2
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
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
                            Text("Choose ISO Image")
                        }

                        if (isoImage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))

                            TextButton(
                                onClick = {
                                    isoImage = ""
                                    isoImageName = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Remove ISO")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "CD-ROM 2 / Driver ISO",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        Text(
                            text = driverIsoImageName.ifEmpty { "No driver ISO selected" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 2
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                driverIsoPicker.launch(
                                    arrayOf(
                                        "application/x-iso9660-image",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Choose Driver ISO")
                        }

                        if (driverIsoImage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))

                            TextButton(
                                onClick = {
                                    driverIsoImage = ""
                                    driverIsoImageName = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Remove Driver ISO")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Shared Hard Drive",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        Text(
                            text = sharedDiskImageName.ifEmpty { "No shared drive selected" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 2
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                sharedDiskPicker.launch(
                                    arrayOf(
                                        "application/octet-stream",
                                        "application/x-qemu-disk",
                                        "*/*"
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (sharedDiskImage.isEmpty()) "Choose Shared Drive" else "Change Shared Drive")
                        }

                        if (sharedDiskImage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    sharedDiskImage = ""
                                    sharedDiskImageName = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Remove Shared Drive")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Shared Folder",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        Text(
                            text = if (sharedFolderLastFileName.isNotEmpty()) {
                                "Last added: $sharedFolderLastFileName"
                            } else {
                                "Import Android files into a Windows-readable shared drive."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(if (sharedFolderEnabled) "Enabled" else "Disabled")

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

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                sharedFolderFilePicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add File to Shared Folder")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Network",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Network",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (networkEnabled) "Enabled" else "Disabled",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }

                            Switch(
                                checked = networkEnabled,
                                onCheckedChange = { networkEnabled = it }
                            )
                        }

                        if (networkEnabled) {
                            Spacer(modifier = Modifier.height(18.dp))

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            Text(
                                text = "Network Adapter",
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("Intel E1000", "Intel E1000E", "Realtek RTL8139", "AMD PCnet").forEach { option ->
                                    FilterChip(
                                        selected = networkAdapter == option,
                                        onClick = { networkAdapter = option },
                                        label = { Text(option) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            Text(
                                text = "Network Mode",
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            FilterChip(
                                selected = networkMode == "User (NAT)",
                                onClick = { networkMode = "User (NAT)" },
                                label = { Text("User (NAT)") }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "User (NAT) shares the Android device's connection without root.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Audio",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(text = "Sound Card", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        listOf("Intel HDA", "AC97", "Sound Blaster 16").forEach { option ->
                            FilterChip(
                                selected = soundCard == option,
                                onClick = { soundCard = option },
                                label = { Text(option) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Text(
                            text = when (soundCard) {
                                "AC97" -> "Legacy AC97 audio for older Windows and Linux guests."
                                "Sound Blaster 16" -> "Classic ISA Sound Blaster 16 for legacy operating systems."
                                else -> "Intel HD Audio using RedBox's existing SDL audio backend."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }



                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Advanced",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Text(
                            text = "BIOS / Guest Date",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        Text(
                            text = "Set the guest RTC date for Windows beta builds. Default uses QEMU's normal current date.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

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
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Example: 2005-04-01.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Text(
                            text = "QEMU Parameters",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        Text(
                            text = "Optional extra QEMU command-line parameters. Leave empty for normal RedBox settings.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = qemuParams,
                            onValueChange = { qemuParams = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("-rtc base=localtime") },
                            minLines = 2,
                            maxLines = 5
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Invalid or conflicting parameters can stop a VM from starting.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val cleanName = name.trim()

                        if (cleanName.isNotEmpty()) {
                            onSave(
                                VMModel(
                                    name = cleanName,
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
                                    audioBackend = "Default"
                                )
                            )
                        }
                    },
                    enabled = name.trim().isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "Save Changes",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Cancel")
                }

                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun DetailRowMaterial(
    icon: String,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = icon,
                    fontSize = 19.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun StorageRowMaterial(
    icon: String,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = icon,
                    fontSize = 19.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}
