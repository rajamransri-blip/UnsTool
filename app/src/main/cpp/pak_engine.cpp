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

static int32_t parseTotalHeaderSize(const char* buf, size_t len) {
    if (len < 64) return 0;
    uint32_t tag = *reinterpret_cast<const uint32_t*>(buf);
    if (tag != 0x9E2A83C1) return 0;
    int32_t legacyVer = *reinterpret_cast<const int32_t*>(buf + 4);
    if (legacyVer <= -6) {
        int32_t customCount = *reinterpret_cast<const int32_t*>(buf + 20);
        if (customCount >= 0 && customCount <= 100) {
            size_t off = 24 + (size_t)customCount * 20;
            if (off + 4 <= len) {
                int32_t ths = *reinterpret_cast<const int32_t*>(buf + off);
                if (ths >= 1024 && (size_t)ths < len) return ths;
            }
        }
    }
    int32_t ths2 = *reinterpret_cast<const int32_t*>(buf + 20);
    if (ths2 >= 1024 && (size_t)ths2 < len) return ths2;
    int32_t ths3 = *reinterpret_cast<const int32_t*>(buf + 24);
    if (ths3 >= 1024 && (size_t)ths3 < len) return ths3;
    return 0;
}

static bool deepCarvePak(const std::string& pakPath, const std::string& outputDir, JNIEnv* env, jobject callback, jmethodID logMethod) {
    std::ifstream pak(pakPath, std::ios::binary);
    if (!pak.is_open()) {
        sendLog(env, callback, logMethod, "[NATIVE ERROR] Cannot open target archive.");
        return false;
    }

    sendLog(env, callback, logMethod, "[NATIVE SCAN] Stream inspecting archive chunks...");

    fs::path coreDir = fs::path(outputDir) / "ShadowTrackerExtra" / "Content" / "BluePrints" / "Core";
    fs::create_directories(coreDir);

    pak.seekg(0, std::ios::end);
    size_t fileSize = pak.tellg();
    pak.seekg(0, std::ios::beg);

    const size_t bufferSize = 2 * 1024 * 1024;
    std::vector<char> buffer(bufferSize);
    size_t currentPos = 0;
    bool found = false;

    const char magicBytes[] = { (char)0xC1, (char)0x83, (char)0x2A, (char)0x9E };

    while (currentPos < fileSize) {
        size_t bytesToRead = std::min(bufferSize, fileSize - currentPos);
        pak.seekg(currentPos, std::ios::beg);
        pak.read(buffer.data(), bytesToRead);

        std::string chunk(buffer.data(), bytesToRead);
        size_t pos = chunk.find("BP_PlayerPawn");
        if (pos != std::string::npos) {
            size_t searchStart = (pos > 65536) ? (pos - 65536) : 0;
            size_t magicPos = chunk.find(std::string(magicBytes, 4), searchStart);

            if (magicPos != std::string::npos && magicPos < pos) {
                size_t actualFileOffset = currentPos + magicPos;
                pak.seekg(actualFileOffset, std::ios::beg);

                size_t assetSliceSize = std::min((size_t)(1024 * 1024), fileSize - actualFileOffset);
                std::vector<char> assetData(assetSliceSize);
                pak.read(assetData.data(), assetSliceSize);

                int32_t headerSize = parseTotalHeaderSize(assetData.data(), assetSliceSize);
                if (headerSize > 0 && (size_t)headerSize < assetSliceSize) {
                    fs::path uassetPath = coreDir / "BP_PlayerPawn.uasset";
                    std::ofstream outUasset(uassetPath, std::ios::binary);
                    outUasset.write(assetData.data(), headerSize);
                    outUasset.close();

                    const char* uexpPtr = assetData.data() + headerSize;
                    size_t rawUexpSize = assetSliceSize - headerSize;
                    size_t finalUexpSize = rawUexpSize;

                    for (size_t i = rawUexpSize; i >= 4; --i) {
                        if (*reinterpret_cast<const uint32_t*>(uexpPtr + i - 4) == 0x9E2A83C1) {
                            finalUexpSize = i;
                            break;
                        }
                    }

                    fs::path uexpPath = coreDir / "BP_PlayerPawn.uexp";
                    std::ofstream outUexp(uexpPath, std::ios::binary);
                    outUexp.write(uexpPtr, finalUexpSize);
                    outUexp.close();

                    sendLog(env, callback, logMethod, "💾 [SPLIT] BP_PlayerPawn.uasset (" + std::to_string(headerSize / 1024) + " KB)");
                    sendLog(env, callback, logMethod, "💾 [SPLIT] BP_PlayerPawn.uexp (" + std::to_string(finalUexpSize / 1024) + " KB)");
                    found = true;
                    break;
                }
            }
        }
        currentPos += bytesToRead - 1024;
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

    sendLog(env, jCallback, logMethod, "[CORE] Native chunk splitter initiated.");
    bool result = deepCarvePak(cPakPath, cOutputDir, env, jCallback, logMethod);

    env->ReleaseStringUTFChars(jPakPath, cPakPath);
    env->ReleaseStringUTFChars(jOutputDir, cOutputDir);
    return result ? JNI_TRUE : JNI_FALSE;
}
