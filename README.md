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

- Windows XP
- Windows Vista
- Windows 7
- Windows 8 / 8.1
- Windows 10
- Windows 11
- Linux distributions
- Other x86 operating systems
- ARM / ARM64 guests

Guest compatibility depends on the selected virtual hardware and QEMU configuration.

---

## ✨ Features

- x86/x86_64 PC emulation
- QEMU 11.1.1 engine
- Multi-Threaded TCG (MTTCG)
- Configurable RAM and CPU cores
- Multiple CPU models
- Configurable TCG translation cache
- PC/i440FX and Q35 machine types
- QCOW2, VHD and RAW disk images
- Create new virtual RAW disks
- ISO/CD-ROM support
- Secondary driver ISO support
- IDE, AHCI and VirtIO Block storage
- Standard VGA
- Bochs display
- VirtIO VGA
- VirGL/OpenGL foundation
- RTL8139 and E1000 networking
- User-mode networking (NAT/SLIRP)
- Shared virtual disk support
- Shared folder support
- Android Storage Access Framework support
- Touchscreen/mouse input
- Keyboard input
- Saved VM configurations
- Dark Android interface

## 💻 Recommended Windows Configuration

RedBox v0.1.0 has two recommended configurations depending on the display adapter you use.

### Standard VGA Configuration

Recommended for compatibility and a simple Windows setup.

| Setting | Recommended |
|---|---|
| RAM | 4096 MB |
| CPU | Core 2 Duo or Nehalem |
| CPU Cores | 4 |
| MTTCG | Enabled |
| TCG Cache | 256 MB |
| Machine | PC / i440FX |
| Disk | IDE |
| Display | Standard VGA |
| Network | RTL8139 |
| Network Mode | User (NAT) |

### VirtIO Configuration

Recommended when using VirtIO VGA and VirtIO drivers.

| Setting | Recommended |
|---|---|
| RAM | 4096 MB |
| CPU | Core 2 Duo or Nehalem |
| CPU Cores | 4 |
| MTTCG | Enabled |
| TCG Cache | 256 MB |
| Machine | PC / i440FX |
| Disk | VirtIO Block |
| Display | VirtIO VGA |
| Network | RTL8139 |
| Network Mode | User (NAT) |

**Note:** VirtIO Block requires the appropriate VirtIO storage driver inside Windows. When installing Windows onto a VirtIO Block disk, you may need to attach the VirtIO driver ISO and load the storage driver during Windows Setup.

## ⚡ Performance

RedBox v0.1.0 uses QEMU's TCG engine for CPU emulation.

When an x86/x86_64 guest is running on an ARM64 Android device, guest CPU instructions must be translated to ARM64 instructions.

Performance therefore depends heavily on the Android device and guest operating system.

v0.1.0 includes:

- Multi-Threaded TCG
- Configurable translation cache
- ARM64-native QEMU build
- Release-optimized QEMU engine
- VirtIO storage support

## 🎮 Graphics

RedBox supports several virtual display adapters, including VirtIO VGA.

The v0.1.0 engine also contains the foundation for VirGL/OpenGL rendering.

Windows VirtIO GPU support may depend on the guest driver, and full Windows 3D acceleration should not be assumed.

## 🌐 Networking

RedBox supports QEMU user-mode networking using SLIRP.

Available virtual network adapters include:

- Realtek RTL8139
- Intel E1000

Guest systems can access the internet without requiring root access on Android.

## 🔊 Audio

Audio support is experimental in v0.1.0.

Improved audio support is planned for a future release.

## 📱 Requirements

- ARM64 Android device
- Android 8.0 or newer recommended
- Sufficient free storage for virtual disks and ISO images
- At least 4 GB device RAM recommended
- More RAM is recommended for Windows guests

No root access is required for normal RedBox operation.

## ⚠️ Important

RedBox does not include Windows, Windows installation media, product keys, or other proprietary operating-system files.

Users must provide their own legally obtained operating-system installation media and licenses.

## 🛣️ Roadmap

### v0.1.0

First stable RedBox release.

### v0.2.0

The next major development cycle will focus heavily on performance, including research into improved CPU translation and execution beyond the current v0.1.0 configuration.

Other future work includes:

- Improved audio
- Additional VM configuration
- Improved VM experience
- Graphics improvements
- Performance improvements
- Additional guest architectures
- RedBox Accelerator research

## 🐛 Issues

If you encounter a bug, please open a GitHub issue and include:

- Android device
- Android version
- Guest operating system
- RedBox VM configuration
- What happened
- Steps to reproduce the problem

## 📜 License

See the repository's license information for details.

RedBox uses QEMU and other open-source components. Their respective licenses and copyright notices apply.
