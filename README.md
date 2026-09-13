# 🔴 RedBox PC Emulator

**RedBox PC Emulator** is an Android virtual machine and PC emulator powered by **QEMU 11.1.1**.

The project is focused on bringing a modern, customizable PC emulation experience to Android with a simple interface, configurable virtual hardware, and an optimized QEMU backend.

RedBox is currently under active development.

---

## 🚀 About RedBox

RedBox PC Emulator allows you to create and run virtual machines directly on Android.

The project currently focuses primarily on:

- x86 / x86_64 PC emulation
- Windows guests
- QEMU TCG emulation
- Custom VM configuration
- Touchscreen mouse controls
- Physical volume-button mouse controls
- Disk image and ISO support
- Performance tuning
- Modern Android UI

RedBox is being developed with the long-term goal of providing a powerful Android VM experience while keeping VM configuration easy to understand.

---

## ⚙️ Emulator Engine

RedBox currently uses:

**QEMU 11.1.1**

The QEMU engine is built for:

- Android
- ARM64 host devices
- x86_64 guest emulation
- SDL2 display output
- TCG/JIT CPU translation

RedBox integrates QEMU directly into the Android application through native C/C++ and JNI.

---

## 🖥️ Guest Operating Systems

RedBox is designed to eventually support many operating systems.

Current development and testing primarily focuses on:

- Windows 7
- Other x86/x86_64 Windows versions

Future testing will include:

- Windows XP
- Windows 8 / 8.1
- Windows 10
- Linux distributions
- Other x86 operating systems
- ARM / ARM64 guests

Guest compatibility depends on the selected virtual hardware and QEMU configuration.

---

## 💾 Disk Image Support

RedBox can load virtual disk images through the Android file picker.

Current development includes support for formats such as:

- VHD
- IMG
- QCOW2
- Other QEMU-compatible disk images

Disk images are accessed through Android's Storage Access Framework.

---

## 💿 ISO Support

ISO images can be attached to a virtual machine as virtual CD/DVD media.

This can be used for:

- Operating system installation
- Driver installation
- Recovery environments
- Bootable utilities
- Software installation

The virtual hard disk remains the primary boot device in the current configuration.

---

## 🧠 CPU & Memory Configuration

Each virtual machine can be configured with its own hardware settings.

### Architecture

Current UI options include:

- x86_64
- x86
- ARM64
- ARM

x86_64 is currently the primary working QEMU backend.

### RAM

Available presets include:

- 1024 MB
- 2048 MB
- 4096 MB
- 6144 MB
- 8192 MB

Actual usable memory depends on the Android device.

### CPU Cores

Available options include:

- 1 core
- 2 cores
- 4 cores
- 6 cores
- 8 cores

More virtual CPU cores do not always mean better performance when using TCG.

---

## ⚡ Performance Settings

RedBox includes configurable QEMU performance options inspired by advanced PC emulator frontends.

### Performance Presets

Available presets:

- Compatibility
- Balanced
- Performance
- Custom

### CPU Model

Available CPU models currently include:

- Default
- qemu64
- max

### TCG Translation Cache

Available cache sizes:

- 128 MB
- 256 MB
- 512 MB

### Multi-threaded TCG

RedBox supports QEMU multi-threaded TCG.

When enabled:

```text
-accel tcg,thread=multi
