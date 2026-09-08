package com.rimvydop.redboxpcemulator

data class VMModel(
    val name: String,
    val architecture: String,
    val ram: String,
    val cpuCores: String,
    val diskImage: String,
    val diskImageName: String,
    val isoImage: String,
    val isoImageName: String
)