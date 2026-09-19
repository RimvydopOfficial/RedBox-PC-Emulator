package com.rimvydop.redboxpcemulator

object QemuIpc {

    // Main process -> QEMU process
    const val MSG_START_VM = 1
    const val MSG_STOP_VM = 2

    const val MSG_SET_SURFACE = 3
    const val MSG_CLEAR_SURFACE = 4

    const val MSG_MOUSE_MOVE = 5
    const val MSG_MOUSE_BUTTON = 6
    const val MSG_KEYBOARD_KEY = 7

    // QEMU process -> Main process
    const val MSG_VM_STARTING = 100
    const val MSG_VM_RUNNING = 101
    const val MSG_VM_STOPPING = 102
    const val MSG_VM_STOPPED = 103
    const val MSG_VM_ERROR = 104

    // VM configuration Bundle keys
    const val KEY_RAM_MB = "ram_mb"
    const val KEY_CPU_CORES = "cpu_cores"

    const val KEY_CPU_MODEL = "cpu_model"
    const val KEY_CPU_FLAGS = "cpu_flags"
    const val KEY_TCG_CACHE_MB = "tcg_cache_mb"
    const val KEY_MULTI_THREADED_TCG = "multi_threaded_tcg"

    const val KEY_MACHINE_TYPE = "machine_type"
    const val KEY_DISK_INTERFACE = "disk_interface"
    const val KEY_DISPLAY_ADAPTER = "display_adapter"

    const val KEY_NETWORK_ENABLED = "network_enabled"
    const val KEY_NETWORK_ADAPTER = "network_adapter"
    const val KEY_NETWORK_MODE = "network_mode"

    // Advanced QEMU command-line parameters
    const val KEY_QEMU_PARAMS = "qemu_params"

    // Guest RTC / BIOS date
    const val KEY_BIOS_DATE = "bios_date"

    // Audio
    const val KEY_SOUND_CARD = "sound_card"
    // File descriptors transferred through Binder.
    const val KEY_DISK_FD = "disk_fd"
    const val KEY_ISO_FD = "iso_fd"
    const val KEY_DRIVER_ISO_FD = "driver_iso_fd"
    const val KEY_SHARED_DISK_FD = "shared_disk_fd"

    const val KEY_DISK_IMAGE_NAME = "disk_image_name"
    const val KEY_SHARED_DISK_IMAGE_NAME = "shared_disk_image_name"

    const val KEY_SHARED_FOLDER_ENABLED = "shared_folder_enabled"

    // Display
    const val KEY_SURFACE = "surface"
    const val KEY_SURFACE_WIDTH = "surface_width"
    const val KEY_SURFACE_HEIGHT = "surface_height"
    const val KEY_SURFACE_FORMAT = "surface_format"
    const val KEY_SURFACE_REFRESH_RATE = "surface_refresh_rate"

    // Mouse
    const val KEY_MOUSE_DX = "mouse_dx"
    const val KEY_MOUSE_DY = "mouse_dy"
    const val KEY_MOUSE_BUTTON = "mouse_button"
    const val KEY_MOUSE_BUTTON_DOWN = "mouse_button_down"

    // Keyboard
    const val KEY_KEYBOARD_KEY_CODE = "keyboard_key_code"
    const val KEY_KEYBOARD_KEY_DOWN = "keyboard_key_down"

    // Status/error information
    const val KEY_STATUS = "status"
}