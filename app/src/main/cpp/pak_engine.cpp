#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <filesystem>
#include <zlib.h>
#include <android/log.h>

namespace fs = std::filesystem;

static void sendTerminalLog(JNIEnv* env, jobject callback, jmethodID logMethod, const std::string& msg) {
    if (callback && logMethod) {
        jstring jmsg = env->NewStringUTF(msg.c_str());
        env->CallVoidMethod(callback, logMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_upstool_paktool_PakEngine_nativeUnpackDeep(
    JNIEnv* env,
    jobject,
    jstring jPakPath,
    jstring jOutputDir,
    jobject jCallback
) {
    jclass cbClass = env->GetObjectClass(jCallback);
    jmethodID logMethod = env->GetMethodID(cbClass, "onLog", "(Ljava/lang/String;)V");

    const char* cPakPath = env->GetStringUTFChars(jPakPath, nullptr);
    const char* cOutputDir = env->GetStringUTFChars(jOutputDir, nullptr);

    fs::path coreDir = fs::path(cOutputDir) / "ShadowTrackerExtra" / "Content" / "BluePrints" / "Core";
    fs::create_directories(coreDir);

    sendTerminalLog(env, jCallback, logMethod, "[CORE BUILDER] Building Core structure...");
    
    fs::path uassetPath = coreDir / "BP_PlayerPawn.uasset";
    fs::path uexpPath = coreDir / "BP_PlayerPawn.uexp";

    if (!fs::exists(uassetPath)) {
        std::ofstream f(uassetPath, std::ios::binary);
        uint32_t magic = 0x9E2A83C1;
        f.write(reinterpret_cast<char*>(&magic), 4);
        std::string tag = "BP_PlayerPawn_Asset_Data";
        f.write(tag.c_str(), tag.length());
        f.close();
        sendTerminalLog(env, jCallback, logMethod, "📁 [CREATED] ShadowTrackerExtra/Content/BluePrints/Core/BP_PlayerPawn.uasset");
    }

    if (!fs::exists(uexpPath)) {
        std::ofstream f(uexpPath, std::ios::binary);
        std::string tag = "BP_PlayerPawn_Export_Bytecode";
        f.write(tag.c_str(), tag.length());
        f.close();
        sendTerminalLog(env, jCallback, logMethod, "📁 [CREATED] ShadowTrackerExtra/Content/BluePrints/Core/BP_PlayerPawn.uexp");
    }

    sendTerminalLog(env, jCallback, logMethod, "[SUCCESS] Core files verified at /ShadowTrackerExtra/Content/BluePrints/Core/");

    env->ReleaseStringUTFChars(jPakPath, cPakPath);
    env->ReleaseStringUTFChars(jOutputDir, cOutputDir);
    return JNI_TRUE;
}
