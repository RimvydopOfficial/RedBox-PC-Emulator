#include <jni.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_rimvydop_redboxpcemulator_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {

    return env->NewStringUTF("RedBox Native Engine Ready");
}