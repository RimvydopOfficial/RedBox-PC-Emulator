package com.rimvydop.redboxpcemulator

data class VMModel(
    val name: String,
    val architecture: String,
    val ram: String,
    val cpuCores: String,
    val diskImage: String,
    val diskImageName: String,
    val isoImage: String,
    val isoImageName: String,
    val driverIsoImage: String = "",
    val driverIsoImageName: String = "",
    val sharedDiskImage: String = "",
    val sharedDiskImageName: String = "",
    val sharedFolderEnabled: Boolean = false,
    val sharedFolderLastFileName: String = "",

    // Performance
    val performancePreset: String = "Balanced",
    val cpuModel: String = "Default",
    val cpuFlags: String = "",
    val tcgCache: String = "256 MB",
    val multiThreadedTcg: Boolean = true,
    val machineType: String = "pc",

    // Storage
    val diskInterface: String = "AHCI",

    // Display
    val displayAdapter: String = "Standard VGA",

    // Network
    val networkEnabled: Boolean = true,
    val networkAdapter: String = "Realtek RTL8139",
    val networkMode: String = "User (NAT)",

    // Advanced
    val qemuParams: String = "",

    // Guest RTC / BIOS date
    val biosDate: String = "Default",

    // Audio
    val soundCard: String = "Intel HDA",
    val audioBackend: String = "Default"
)
