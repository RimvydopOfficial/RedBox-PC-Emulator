#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <android/log.h>

#define REDBOX_TAG "RedBoxQEMU"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, REDBOX_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, REDBOX_TAG, __VA_ARGS__)

static void* qemuHandle = nullptr;

typedef void (*QemuInitFunc)(int argc, char** argv, char** envp);
typedef void (*QemuMainLoopFunc)();
typedef void (*QemuCleanupFunc)();

typedef void (*SetJniFunc)(
        JNIEnv* env,
        jobject object,
        jclass objectClass,
        const char* storageDir,
        const char* baseDir
);

static QemuInitFunc qemuInit = nullptr;
static QemuMainLoopFunc qemuMainLoop = nullptr;
static QemuCleanupFunc qemuCleanup = nullptr;
static SetJniFunc setJni = nullptr;

static bool qemuInitialized = false;


/*
 * Get the Android application's internal files directory.
 *
 * Limbo uses Android-provided directories for its compatibility layer.
 * For our first RedBox QEMU test we use the application's private
 * files directory for both storage and QEMU base files.
 */
static std::string getAppFilesDirectory(
        JNIEnv* env,
        jobject thiz
) {
    jclass activityClass = env->GetObjectClass(thiz);

    if (activityClass == nullptr) {
        LOGE("Could not get Activity class");
        return "";
    }

    jmethodID getFilesDirMethod =
            env->GetMethodID(
                    activityClass,
                    "getFilesDir",
                    "()Ljava/io/File;"
            );

    if (getFilesDirMethod == nullptr) {
        LOGE("Could not find Context.getFilesDir()");
        return "";
    }

    jobject filesDirObject =
            env->CallObjectMethod(
                    thiz,
                    getFilesDirMethod
            );

    if (filesDirObject == nullptr) {
        LOGE("getFilesDir() returned null");
        return "";
    }

    jclass fileClass =
            env->FindClass("java/io/File");

    if (fileClass == nullptr) {
        LOGE("Could not find java.io.File");
        return "";
    }

    jmethodID getAbsolutePathMethod =
            env->GetMethodID(
                    fileClass,
                    "getAbsolutePath",
                    "()Ljava/lang/String;"
            );

    if (getAbsolutePathMethod == nullptr) {
        LOGE("Could not find File.getAbsolutePath()");
        return "";
    }

    jstring pathString =
            static_cast<jstring>(
                    env->CallObjectMethod(
                            filesDirObject,
                            getAbsolutePathMethod
                    )
            );

    if (pathString == nullptr) {
        LOGE("getAbsolutePath() returned null");
        return "";
    }

    const char* pathChars =
            env->GetStringUTFChars(pathString, nullptr);

    if (pathChars == nullptr) {
        LOGE("Could not read files directory path");
        return "";
    }

    std::string result(pathChars);

    env->ReleaseStringUTFChars(
            pathString,
            pathChars
    );

    return result;
}


/*
 * Load the QEMU shared library and locate the functions we need.
 */
static bool loadQemuEngine() {

    if (qemuHandle != nullptr) {
        return true;
    }

    LOGI("Loading QEMU engine...");

    qemuHandle =
            dlopen(
                    "libqemu-system-x86_64.so",
                    RTLD_NOW | RTLD_LOCAL
            );

    if (qemuHandle == nullptr) {

        const char* error = dlerror();

        LOGE(
                "QEMU dlopen failed: %s",
                error != nullptr ? error : "unknown error"
        );

        return false;
    }

    dlerror();

    qemuInit =
            reinterpret_cast<QemuInitFunc>(
                    dlsym(
                            qemuHandle,
                            "qemu_init"
                    )
            );

    const char* initError = dlerror();

    if (initError != nullptr || qemuInit == nullptr) {

        LOGE(
                "Could not find qemu_init: %s",
                initError != nullptr ? initError : "unknown error"
        );

        dlclose(qemuHandle);
        qemuHandle = nullptr;

        return false;
    }


    dlerror();

    qemuMainLoop =
            reinterpret_cast<QemuMainLoopFunc>(
                    dlsym(
                            qemuHandle,
                            "qemu_main_loop"
                    )
            );

    const char* loopError = dlerror();

    if (loopError != nullptr || qemuMainLoop == nullptr) {

        LOGE(
                "Could not find qemu_main_loop: %s",
                loopError != nullptr ? loopError : "unknown error"
        );

        dlclose(qemuHandle);
        qemuHandle = nullptr;

        return false;
    }


    dlerror();

    qemuCleanup =
            reinterpret_cast<QemuCleanupFunc>(
                    dlsym(
                            qemuHandle,
                            "qemu_cleanup"
                    )
            );

    const char* cleanupError = dlerror();

    if (cleanupError != nullptr || qemuCleanup == nullptr) {

        LOGE(
                "Could not find qemu_cleanup: %s",
                cleanupError != nullptr ? cleanupError : "unknown error"
        );

        dlclose(qemuHandle);
        qemuHandle = nullptr;

        return false;
    }


    /*
     * Limbo's compatibility library provides set_jni().
     *
     * Because libqemu-system-x86_64.so depends on libcompat-limbo.so,
     * we can resolve the function from the loaded QEMU dependency tree.
     */
    dlerror();

    setJni =
            reinterpret_cast<SetJniFunc>(
                    dlsym(
                            qemuHandle,
                            "set_jni"
                    )
            );

    const char* jniError = dlerror();

    if (jniError != nullptr || setJni == nullptr) {

        LOGE(
                "Could not find Limbo set_jni: %s",
                jniError != nullptr ? jniError : "unknown error"
        );

        dlclose(qemuHandle);
        qemuHandle = nullptr;

        return false;
    }


    LOGI("QEMU engine loaded");
    LOGI("qemu_init found");
    LOGI("qemu_main_loop found");
    LOGI("qemu_cleanup found");
    LOGI("Limbo set_jni found");

    return true;
}


/*
 * Initialize Limbo's Android JNI compatibility layer.
 */
static bool initializeLimboJni(
        JNIEnv* env,
        jobject thiz
) {

    if (setJni == nullptr) {
        LOGE("set_jni function is unavailable");
        return false;
    }

    std::string appFilesDirectory =
            getAppFilesDirectory(
                    env,
                    thiz
            );

    if (appFilesDirectory.empty()) {
        LOGE("Could not obtain application files directory");
        return false;
    }

    LOGI(
            "RedBox application directory: %s",
            appFilesDirectory.c_str()
    );

    /*
     * Limbo's set_jni() stores these directory pointers for its
     * Android compatibility functions.
     *
     * For this first engine test we use the RedBox private files
     * directory for both values.
     */
    jclass thizClass =
            env->GetObjectClass(thiz);

    if (thizClass == nullptr) {
        LOGE("Could not obtain MainActivity class");
        return false;
    }

    setJni(
            env,
            thiz,
            thizClass,
            appFilesDirectory.c_str(),
            appFilesDirectory.c_str()
    );

    LOGI("Limbo JNI compatibility initialized");

    return true;
}


/*
 * RedBox QEMU status.
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_rimvydop_redboxpcemulator_MainActivity_nativeQemuStatus(
        JNIEnv* env,
        jobject thiz
) {

    if (!loadQemuEngine()) {

        return env->NewStringUTF(
                "QEMU Load Failed"
        );
    }

    if (qemuInitialized) {

        return env->NewStringUTF(
                "QEMU Engine Initialized"
        );
    }

    return env->NewStringUTF(
            "QEMU Engine Loaded Successfully"
    );
}


/*
 * Start / initialize QEMU.
 *
 * IMPORTANT:
 * We are intentionally NOT calling qemu_main_loop() yet.
 * The main loop will later run on a background thread.
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_rimvydop_redboxpcemulator_MainActivity_nativeQemuStart(
        JNIEnv* env,
        jobject thiz
) {

    LOGI("=================================");
    LOGI("RedBox QEMU start requested");
    LOGI("=================================");


    if (!loadQemuEngine()) {

        return env->NewStringUTF(
                "QEMU Start Failed: engine could not be loaded"
        );
    }


    if (qemuInitialized) {

        return env->NewStringUTF(
                "QEMU Engine Already Initialized"
        );
    }


    /*
     * THIS IS THE IMPORTANT NEW PART.
     *
     * Limbo initializes its JNI compatibility layer before qemu_init().
     */
    if (!initializeLimboJni(env, thiz)) {

        return env->NewStringUTF(
                "QEMU Start Failed: Limbo JNI initialization failed"
        );
    }


    std::string appFilesDirectory =
            getAppFilesDirectory(
                    env,
                    thiz
            );

    if (appFilesDirectory.empty()) {

        return env->NewStringUTF(
                "QEMU Start Failed: application directory unavailable"
        );
    }


    /*
     * Minimal QEMU initialization arguments.
     *
     * argv[0] = program name
     * -L       = QEMU firmware/base directory
     * -display = no Android window yet
     * -nodefaults
     * -machine  = x86 PC machine
     * -m       = small test memory amount
     * -accel   = software TCG for this first test
     */
    const char* qemuArguments[] = {

            "qemu-system-x86_64",

            "-L",
            appFilesDirectory.c_str(),

            "-display",
            "none",

            "-nodefaults",

            "-machine",
            "pc",

            "-m",
            "128",

            "-accel",
            "tcg,thread=single"
    };


    int argc =
            sizeof(qemuArguments) /
            sizeof(qemuArguments[0]);


    char** argv =
            const_cast<char**>(
                    qemuArguments
            );


    char** envp = nullptr;


    LOGI("Calling qemu_init()...");
    LOGI("QEMU argument count: %d", argc);


    /*
     * Enter QEMU initialization.
     */
    qemuInit(
            argc,
            argv,
            envp
    );


    qemuInitialized = true;


    LOGI("qemu_init() completed successfully");


    return env->NewStringUTF(
            "QEMU Engine Initialized Successfully"
    );
}