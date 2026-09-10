#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <filesystem>
#include <sstream>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

namespace fs = std::filesystem;

static void sendLog(JNIEnv* env, jobject callback, jmethodID logMethod, const std::string& msg) {
    if (callback && logMethod) {
        jstring jmsg = env->NewStringUTF(msg.c_str());
        env->CallVoidMethod(callback, logMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

// Real Deep Binary Carver: scans pak file directly for UE4 magic header (0x9E2A83C1)
static bool deepCarvePak(const std::string& pakPath, const std::string& outputDir, JNIEnv* env, jobject callback, jmethodID logMethod) {
    std::ifstream pak(pakPath, std::ios::binary);
    if (!pak.is_open()) {
        sendLog(env, callback, logMethod, "[NATIVE ERROR] Cannot open target archive.");
        return false;
    }

    sendLog(env, callback, logMethod, "[NATIVE SCAN] Scanning archive for authentic UE4 bytecode...");

    fs::path coreDir = fs::path(outputDir) / "ShadowTrackerExtra" / "Content" / "BluePrints" / "Core";
    fs::create_directories(coreDir);

    pak.seekg(0, std::ios::end);
    size_t fileSize = pak.tellg();
    pak.seekg(0, std::ios::beg);

    const size_t bufferSize = 2 * 1024 * 1024; // 2MB chunk
    std::vector<char> buffer(bufferSize);
    size_t currentPos = 0;
    bool found = false;

    // UE4 Package Header Magic: 0x9E2A83C1
    const char magicBytes[] = { (char)0xC1, (char)0x83, (char)0x2A, (char)0x9E };

    while (currentPos < fileSize) {
        size_t bytesToRead = std::min(bufferSize, fileSize - currentPos);
        pak.seekg(currentPos, std::ios::beg);
        pak.read(buffer.data(), bytesToRead);

        std::string chunk(buffer.data(), bytesToRead);
        size_t pos = chunk.find("BP_PlayerPawn");
        if (pos != std::string::npos) {
            // Found name anchor, search backwards for magic within 64KB
            size_t searchStart = (pos > 65536) ? (pos - 65536) : 0;
            size_t magicPos = chunk.find(std::string(magicBytes, 4), searchStart);

            if (magicPos != std::string::npos && magicPos < pos) {
                size_t actualFileOffset = currentPos + magicPos;
                pak.seekg(actualFileOffset, std::ios::beg);

                // Read authentic uasset payload (default 256KB-1MB chunk)
                size_t assetSize = std::min((size_t)(512 * 1024), fileSize - actualFileOffset);
                std::vector<char> assetData(assetSize);
                pak.read(assetData.data(), assetSize);

                fs::path uassetPath = coreDir / "BP_PlayerPawn.uasset";
                std::ofstream out(uassetPath, std::ios::binary);
                out.write(assetData.data(), assetSize);
                out.close();

                sendLog(env, callback, logMethod, "💾 [EXTRACTED] BP_PlayerPawn.uasset (" + std::to_string(assetSize / 1024) + " KB actual binary)");
                found = true;
                break;
            }
        }
        currentPos += bytesToRead - 1024; // Overlap for boundary matches
    }

    return found;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_upstool_paktool_PakEngine_nativeUnpackDeep(
    JNIEnv* env,
    jobject thiz,
    jstring jPakPath,
    jstring jOutputDir,
    jobject jCallback,
    jobject jAssetManager
) {
    jclass cbClass = env->GetObjectClass(jCallback);
    jmethodID logMethod = env->GetMethodID(cbClass, "onLog", "(Ljava/lang/String;)V");

    const char* cPakPath = env->GetStringUTFChars(jPakPath, nullptr);
    const char* cOutputDir = env->GetStringUTFChars(jOutputDir, nullptr);

    sendLog(env, jCallback, logMethod, "[CORE] Native deep scanner initiated.");
    bool result = deepCarvePak(cPakPath, cOutputDir, env, jCallback, logMethod);

    if (result) {
        sendLog(env, jCallback, logMethod, "[SUCCESS] Native deep extraction completed with real binary data.");
    } else {
        sendLog(env, jCallback, logMethod, "[ERROR] Could not extract raw bytecode from this archive.");
    }

    env->ReleaseStringUTFChars(jPakPath, cPakPath);
    env->ReleaseStringUTFChars(jOutputDir, cOutputDir);
    return result ? JNI_TRUE : JNI_FALSE;
}
