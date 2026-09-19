package com.rimvydop.redboxpcemulator

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import android.graphics.PixelFormat
import org.libsdl.app.SDL
import org.libsdl.app.SDLActivity
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QemuService : Service() {

    companion object {
        private const val TAG = "RedBoxQemuService"

        init {
            System.loadLibrary("SDL2")
            System.loadLibrary("compat-SDL2-ext")
            System.loadLibrary("redboxpcemulator")

            Log.d(
                TAG,
                "Native RedBox libraries loaded in :qemu process"
            )
        }
    }

    private val limboFileDescriptors =
        mutableMapOf<Int, ParcelFileDescriptor>()

    private val qemuExecutor =
        Executors.newSingleThreadExecutor()

    private val qemuProcessActive =
        AtomicBoolean(false)

    /*
     * Native QEMU bridge.
     *
     * The C++ wrappers forward these calls to the existing
     * RedBox QEMU implementation.
     */
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

    private external fun nativeQemuStop(): Boolean

    // Part 2M.3: mouse input must execute inside the isolated :qemu process.
    private external fun nativeQemuMouseMove(
        deltaX: Int,
        deltaY: Int
    ): Boolean

    private external fun nativeQemuMouseButton(
        button: Int,
        down: Boolean
    ): Boolean

    private external fun nativeQemuKeyboardKey(
        androidKeyCode: Int,
        down: Boolean
    ): Boolean

    /*
     * Display Surface bridge for the isolated :qemu process.
     *
     * The Surface originates in MainActivity/VMDisplay and is sent through
     * Messenger. QEMU/SDL must receive it in this process because the native
     * QEMU library is loaded here.
     */
    private external fun nativeSetDisplaySurface(
        surface: Surface?
    )

    /*
     * Messenger messages arrive here.
     *
     * IMPORTANT:
     * QEMU itself runs on qemuExecutor, not Android's main thread.
     */
    private val incomingHandler =
        object : Handler(Looper.getMainLooper()) {

            override fun handleMessage(message: Message) {
                when (message.what) {

                    QemuIpc.MSG_START_VM -> {
                        Log.d(
                            TAG,
                            "MSG_START_VM received in :qemu process. PID=${android.os.Process.myPid()}"
                        )

                        if (!qemuProcessActive.compareAndSet(false, true)) {
                            sendStatus(
                                message.replyTo,
                                QemuIpc.MSG_VM_ERROR,
                                "QEMU VM is already running"
                            )
                            return
                        }

                        val data =
                            Bundle(message.data)

                        val replyTo =
                            message.replyTo

                        sendStatus(
                            replyTo,
                            QemuIpc.MSG_VM_STARTING,
                            "Starting QEMU in isolated process"
                        )

                        qemuExecutor.execute {
                            try {
                                val ramMb =
                                    data.getInt(
                                        QemuIpc.KEY_RAM_MB,
                                        512
                                    )

                                val cpuCores =
                                    data.getInt(
                                        QemuIpc.KEY_CPU_CORES,
                                        1
                                    )

                                val diskUri =
                                    data.getString(
                                        "disk_uri",
                                        ""
                                    )

                                val diskImageName =
                                    data.getString(
                                        QemuIpc.KEY_DISK_IMAGE_NAME,
                                        ""
                                    )

                                val isoUri =
                                    data.getString(
                                        "iso_uri",
                                        ""
                                    )

                                val driverIsoUri =
                                    data.getString(
                                        "driver_iso_uri",
                                        ""
                                    )

                                val sharedDiskUri =
                                    data.getString(
                                        "shared_disk_uri",
                                        ""
                                    )

                                val sharedDiskImageName =
                                    data.getString(
                                        QemuIpc.KEY_SHARED_DISK_IMAGE_NAME,
                                        ""
                                    )

                                val sharedFolderEnabled =
                                    data.getBoolean(
                                        QemuIpc.KEY_SHARED_FOLDER_ENABLED,
                                        false
                                    )

                                val cpuModel =
                                    data.getString(
                                        QemuIpc.KEY_CPU_MODEL,
                                        "Default"
                                    )

                                val cpuFlags =
                                    data.getString(
                                        QemuIpc.KEY_CPU_FLAGS,
                                        ""
                                    )

                                val tcgCacheMb =
                                    data.getInt(
                                        QemuIpc.KEY_TCG_CACHE_MB,
                                        256
                                    )

                                val multiThreadedTcg =
                                    data.getBoolean(
                                        QemuIpc.KEY_MULTI_THREADED_TCG,
                                        true
                                    )

                                val machineType =
                                    data.getString(
                                        QemuIpc.KEY_MACHINE_TYPE,
                                        "pc"
                                    )

                                val diskInterface =
                                    data.getString(
                                        QemuIpc.KEY_DISK_INTERFACE,
                                        "AHCI"
                                    )

                                val displayAdapter =
                                    data.getString(
                                        QemuIpc.KEY_DISPLAY_ADAPTER,
                                        "Standard VGA"
                                    )

                                val networkEnabled =
                                    data.getBoolean(
                                        QemuIpc.KEY_NETWORK_ENABLED,
                                        true
                                    )

                                val networkAdapter =
                                    data.getString(
                                        QemuIpc.KEY_NETWORK_ADAPTER,
                                        "Realtek RTL8139"
                                    )

                                val networkMode =
                                    data.getString(
                                        QemuIpc.KEY_NETWORK_MODE,
                                        "User (NAT)"
                                    )

                                val qemuParams =
                                    data.getString(
                                        QemuIpc.KEY_QEMU_PARAMS,
                                        ""
                                    )

                                val biosDate =
                                    data.getString(
                                        QemuIpc.KEY_BIOS_DATE,
                                        "Default"
                                    )

                                val soundCard =
                                    data.getString(
                                        QemuIpc.KEY_SOUND_CARD,
                                        "Intel HDA"
                                    )

                                Log.d(
                                    TAG,
                                    "Calling nativeQemuStart() in PID=${android.os.Process.myPid()}"
                                )

                                val result =
                                    nativeQemuStart(
                                        ramMb,
                                        cpuCores,
                                        diskUri,
                                        diskImageName,
                                        isoUri,
                                        driverIsoUri,
                                        sharedDiskUri,
                                        sharedDiskImageName,
                                        sharedFolderEnabled,
                                        cpuModel,
                                        cpuFlags,
                                        tcgCacheMb,
                                        multiThreadedTcg,
                                        machineType,
                                        diskInterface,
                                        displayAdapter,
                                        networkEnabled,
                                        networkAdapter,
                                        networkMode,
                                        qemuParams,
                                        biosDate,
                                        soundCard
                                    )

                                Log.d(
                                    TAG,
                                    "nativeQemuStart() returned: $result"
                                )

                                sendStatus(
                                    replyTo,
                                    QemuIpc.MSG_VM_STOPPED,
                                    result
                                )
                            } catch (error: Throwable) {
                                Log.e(
                                    TAG,
                                    "QEMU worker failed",
                                    error
                                )

                                sendStatus(
                                    replyTo,
                                    QemuIpc.MSG_VM_ERROR,
                                    "QEMU failed: ${error.message ?: error.javaClass.simpleName}"
                                )
                            } finally {
                                qemuProcessActive.set(false)

                                Log.d(
                                    TAG,
                                    "QEMU worker finished"
                                )

                                /*
                                 * Part 2L restored by Part 2M.3:
                                 * QEMU cannot be safely initialized a second time in the same
                                 * native process. End this isolated :qemu PID after QEMU returns.
                                 * MainActivity Part 2L.2 will receive the disconnect and bind a
                                 * completely fresh QemuService process for the next Start.
                                 */
                                Handler(Looper.getMainLooper()).postDelayed(
                                    {
                                        Log.i(
                                            TAG,
                                            "Part 2M.3: terminating old :qemu PID=${android.os.Process.myPid()} for clean restart"
                                        )
                                        android.os.Process.killProcess(android.os.Process.myPid())
                                    },
                                    350L
                                )
                            }
                        }
                    }

                    QemuIpc.MSG_STOP_VM -> {
                        Log.d(
                            TAG,
                            "MSG_STOP_VM received in :qemu process"
                        )

                        if (qemuProcessActive.get()) {
                            val accepted =
                                try {
                                    nativeQemuStop()
                                } catch (error: Throwable) {
                                    Log.e(
                                        TAG,
                                        "nativeQemuStop failed",
                                        error
                                    )

                                    false
                                }

                            sendStatus(
                                message.replyTo,
                                if (accepted) {
                                    QemuIpc.MSG_VM_STOPPING
                                } else {
                                    QemuIpc.MSG_VM_ERROR
                                },
                                if (accepted) {
                                    "QEMU shutdown requested"
                                } else {
                                    "QEMU shutdown request failed"
                                }
                            )
                        } else {
                            sendStatus(
                                message.replyTo,
                                QemuIpc.MSG_VM_STOPPED,
                                "No QEMU VM is running"
                            )
                        }
                    }

                    QemuIpc.MSG_SET_SURFACE -> {
                        Log.d(
                            TAG,
                            "MSG_SET_SURFACE received in :qemu process"
                        )

                        try {
                            val data = message.data

                            @Suppress("DEPRECATION")
                            val surface =
                                data.getParcelable<Surface>(
                                    QemuIpc.KEY_SURFACE
                                )

                            val width =
                                data.getInt(QemuIpc.KEY_SURFACE_WIDTH, 0)

                            val height =
                                data.getInt(QemuIpc.KEY_SURFACE_HEIGHT, 0)

                            val androidFormat =
                                data.getInt(QemuIpc.KEY_SURFACE_FORMAT, 0)

                            val refreshRate =
                                data.getFloat(
                                    QemuIpc.KEY_SURFACE_REFRESH_RATE,
                                    60f
                                )

                            if (surface != null && surface.isValid) {
                                /*
                                 * Part 2H + Part 2M.2:
                                 * Register the transferred Surface with SDLActivity in THIS
                                 * :qemu process. When this is a real runtime resize, also
                                 * forward the new dimensions to SDL's Android video backend.
                                 */
                                SDLActivity.setRedBoxRemoteSurface(surface)
                                nativeSetDisplaySurface(surface)

                                if (
                                    qemuProcessActive.get() &&
                                    width > 0 &&
                                    height > 0
                                ) {
                                    val sdlFormat =
                                        when (androidFormat) {
                                            PixelFormat.RGBA_4444 -> 0x15421002
                                            PixelFormat.RGBA_5551 -> 0x15441002
                                            PixelFormat.RGBA_8888 -> 0x16462004
                                            PixelFormat.RGBX_8888 -> 0x16261804
                                            PixelFormat.RGB_332 -> 0x14110801
                                            PixelFormat.RGB_565 -> 0x15151002
                                            PixelFormat.RGB_888 -> 0x16161804
                                            else -> 0x15151002
                                        }

                                    SDLActivity.onNativeResize(
                                        width,
                                        height,
                                        sdlFormat,
                                        refreshRate
                                    )

                                    Log.d(
                                        TAG,
                                        "Part 2M.2 SDL resize forwarded in :qemu: ${width}x${height}, androidFormat=$androidFormat, sdlFormat=$sdlFormat, refresh=${refreshRate}Hz"
                                    )
                                }

                                Log.d(
                                    TAG,
                                    "Display Surface registered with SDL + native QEMU in PID=${android.os.Process.myPid()}"
                                )
                            } else {
                                Log.w(
                                    TAG,
                                    "MSG_SET_SURFACE did not contain a valid Surface"
                                )
                            }
                        } catch (error: Throwable) {
                            Log.e(
                                TAG,
                                "Could not forward display Surface to native QEMU",
                                error
                            )
                        }
                    }

                    QemuIpc.MSG_CLEAR_SURFACE -> {
                        Log.d(
                            TAG,
                            "MSG_CLEAR_SURFACE received in :qemu process"
                        )

                        try {
                            SDLActivity.setRedBoxRemoteSurface(null)
                            nativeSetDisplaySurface(null)

                            Log.d(
                                TAG,
                                "SDL + native QEMU display Surface cleared"
                            )
                        } catch (error: Throwable) {
                            Log.e(
                                TAG,
                                "Could not clear native QEMU display Surface",
                                error
                            )
                        }
                    }

                    QemuIpc.MSG_MOUSE_MOVE -> {
                        val dx = message.data.getInt(QemuIpc.KEY_MOUSE_DX, 0)
                        val dy = message.data.getInt(QemuIpc.KEY_MOUSE_DY, 0)

                        if (qemuProcessActive.get() && (dx != 0 || dy != 0)) {
                            try {
                                nativeQemuMouseMove(dx, dy)
                            } catch (error: Throwable) {
                                Log.e(TAG, "Part 2M.3 native mouse move failed", error)
                            }
                        }
                    }

                    QemuIpc.MSG_MOUSE_BUTTON -> {
                        val button =
                            message.data.getInt(QemuIpc.KEY_MOUSE_BUTTON, 0)
                        val down =
                            message.data.getBoolean(QemuIpc.KEY_MOUSE_BUTTON_DOWN, false)

                        if (qemuProcessActive.get()) {
                            try {
                                nativeQemuMouseButton(button, down)
                            } catch (error: Throwable) {
                                Log.e(TAG, "Part 2M.3 native mouse button failed", error)
                            }
                        }
                    }

                    QemuIpc.MSG_KEYBOARD_KEY -> {
                        val keyCode =
                            message.data.getInt(QemuIpc.KEY_KEYBOARD_KEY_CODE, 0)
                        val down =
                            message.data.getBoolean(QemuIpc.KEY_KEYBOARD_KEY_DOWN, false)

                        if (qemuProcessActive.get()) {
                            try {
                                nativeQemuKeyboardKey(keyCode, down)
                            } catch (error: Throwable) {
                                Log.e(TAG, "Native keyboard input failed", error)
                            }
                        }
                    }

                    else -> {
                        super.handleMessage(message)
                    }
                }
            }
        }

    private val messenger =
        Messenger(incomingHandler)

    override fun onCreate() {
        super.onCreate()

        /*
         * REDBOX Part 2I:
         * QEMU's SDL backend now lives in the isolated :qemu process, so this
         * process must perform SDL's Android JNI/bootstrap sequence too.
         *
         * IMPORTANT: SDL.initialize() clears SDLActivity's static state,
         * including the Part 2H remote Surface slot. Therefore this runs once
         * when the service process is created, BEFORE MSG_SET_SURFACE registers
         * the Surface transferred from the UI process.
         */
        try {
            SDL.setupJNI()
            SDL.initialize()
            SDL.setContext(applicationContext)

            Log.i(
                TAG,
                "SDL Android bootstrap complete in :qemu PID=${android.os.Process.myPid()}"
            )
        } catch (throwable: Throwable) {
            Log.e(
                TAG,
                "SDL Android bootstrap failed in :qemu process",
                throwable
            )
        }

        Log.d(
            TAG,
            "QemuService created. PID=${android.os.Process.myPid()}"
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(
            TAG,
            "QemuService bound. PID=${android.os.Process.myPid()}"
        )

        return messenger.binder
    }

    override fun onDestroy() {
        Log.d(
            TAG,
            "QemuService destroyed. PID=${android.os.Process.myPid()}"
        )

        qemuExecutor.shutdownNow()

        try {
            SDLActivity.setRedBoxRemoteSurface(null)
            nativeSetDisplaySurface(null)
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Could not clear display Surface during service shutdown",
                error
            )
        }

        synchronized(limboFileDescriptors) {
            for (descriptor in limboFileDescriptors.values) {
                try {
                    descriptor.close()
                } catch (_: Exception) {
                }
            }

            limboFileDescriptors.clear()
        }

        super.onDestroy()
    }

    private fun sendStatus(
        target: Messenger?,
        messageType: Int,
        status: String
    ) {
        if (target == null) {
            return
        }

        try {
            val response =
                Message.obtain(
                    null,
                    messageType
                )

            response.data.putString(
                QemuIpc.KEY_STATUS,
                status
            )

            target.send(response)
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Could not send QEMU service response",
                error
            )
        }
    }

    /*
     * SAF / Limbo compatibility bridge.
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
                            .replace(
                                "/content//",
                                "content://"
                            )
                            .replace(
                                "^^^",
                                "%"
                            )
                    } else {
                        path
                    }

                val parcelFileDescriptor =
                    if (decodedPath.startsWith("content://")) {
                        val uri =
                            Uri.parse(decodedPath)

                        val mode =
                            if (
                                decodedPath
                                    .lowercase()
                                    .endsWith(".iso")
                            ) {
                                "r"
                            } else {
                                "rw"
                            }

                        contentResolver.openFileDescriptor(
                            uri,
                            mode
                        ) ?: return@synchronized -1
                    } else {
                        val file =
                            File(decodedPath)

                        if (!file.exists()) {
                            file.parentFile?.mkdirs()
                            file.createNewFile()
                        }

                        val mode =
                            if (
                                decodedPath
                                    .lowercase()
                                    .endsWith(".iso")
                            ) {
                                ParcelFileDescriptor.MODE_READ_ONLY
                            } else {
                                ParcelFileDescriptor.MODE_READ_WRITE
                            }

                        ParcelFileDescriptor.open(
                            file,
                            mode
                        )
                    }

                val fd =
                    parcelFileDescriptor.fd

                limboFileDescriptors[fd] =
                    parcelFileDescriptor

                Log.d(
                    TAG,
                    "get_fd(): $decodedPath -> FD $fd"
                )

                fd
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "get_fd() failed for: $path",
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
                        parcelFileDescriptor
                            .fileDescriptor
                            .sync()
                    } catch (_: IOException) {
                    }

                    parcelFileDescriptor.close()

                    Log.d(
                        TAG,
                        "close_fd(): FD $fd closed"
                    )

                    0
                } else {
                    val fallback =
                        ParcelFileDescriptor.fromFd(fd)

                    try {
                        fallback
                            .fileDescriptor
                            .sync()
                    } catch (_: IOException) {
                    }

                    fallback.close()

                    Log.d(
                        TAG,
                        "close_fd(): fallback FD $fd handled"
                    )

                    0
                }
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "close_fd() failed for FD $fd",
                    error
                )

                -1
            }
        }
    }

    /*
     * QEMU firmware extraction.
     */
    fun prepareQemuFirmware(): Boolean {
        return try {
            val root =
                filesDir

            val assetRoot =
                "pc-bios"

            fun copyAssetTree(
                assetPath: String,
                relativePath: String
            ) {
                val children =
                    assets.list(assetPath)
                        ?: emptyArray()

                if (children.isEmpty()) {
                    val destination =
                        if (relativePath.isEmpty()) {
                            root
                        } else {
                            File(
                                root,
                                relativePath
                            )
                        }

                    destination.parentFile?.mkdirs()

                    assets.open(assetPath).use { input ->
                        destination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    Log.d(
                        TAG,
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

            copyAssetTree(
                assetRoot,
                ""
            )

            val biosFile =
                File(
                    root,
                    "bios-256k.bin"
                )

            if (
                !biosFile.exists() ||
                biosFile.length() == 0L
            ) {
                Log.e(
                    TAG,
                    "QEMU BIOS extraction failed"
                )

                false
            } else {
                Log.i(
                    TAG,
                    "QEMU firmware ready: ${biosFile.absolutePath} (${biosFile.length()} bytes)"
                )

                true
            }
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Failed to extract QEMU firmware",
                error
            )

            false
        }
    }
}