#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <filesystem>
#include <algorithm>
#include <cstdint>
#include <zlib.h>
#include <android/log.h>

namespace fs = std::filesystem;

#define LOG_TAG "UpstoolNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void sendTerminalLog(JNIEnv* env, jobject callback, jmethodID logMethod, const std::string& msg) {
    if (callback && logMethod) {
        jstring jmsg = env->NewStringUTF(msg.c_str());
        env->CallVoidMethod(callback, logMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

static bool deepCarveStreams(
    JNIEnv* env,
    jobject jCallback,
    jmethodID logMethod,
    const std::string& pakPath,
    const std::string& outputDir
) {
    sendTerminalLog(env, jCallback, logMethod, "[NATIVE CARVER] Scanning Zlib stream markers in game patch...");

    std::ifstream file(pakPath, std::ios::binary);
    if (!file.is_open()) {
        sendTerminalLog(env, jCallback, logMethod, "[ERROR] Cannot open archive file.");
        return false;
    }

    file.seekg(0, std::ios::end);
    int64_t totalSize = file.tellg();
    file.seekg(0, std::ios::beg);

    const size_t bufferSize = 1024 * 1024 * 4; // 4MB Chunk
    std::vector<uint8_t> buffer(bufferSize);
    std::vector<uint8_t> decompBuffer(1024 * 1024 * 8); // 8MB unpack buffer

    int extractedCount = 0;
    int64_t currentOffset = 0;

    while (currentOffset < totalSize) {
        file.seekg(currentOffset);
        file.read(reinterpret_cast<char*>(buffer.data()), bufferSize);
        std::streamsize bytesRead = file.gcount();
        if (bytesRead < 4) break;

        for (size_t i = 0; i < bytesRead - 4; ++i) {
            if (buffer[i] == 0x78 && (buffer[i+1] == 0x9C || buffer[i+1] == 0xDA || buffer[i+1] == 0x01)) {
                size_t available = bytesRead - i;
                z_stream strm{};
                strm.next_in = &buffer[i];
                strm.avail_in = available;
                strm.next_out = decompBuffer.data();
                strm.avail_out = decompBuffer.size();

                if (inflateInit(&strm) == Z_OK) {
                    int ret = inflate(&strm, Z_FINISH);
                    size_t decompressedSize = decompBuffer.size() - strm.avail_out;
                    inflateEnd(&strm);

                    if ((ret == Z_STREAM_END || ret == Z_OK) && decompressedSize > 64) {
                        std::string contentStr(reinterpret_cast<char*>(decompBuffer.data()), std::min(decompressedSize, (size_t)2048));
                        std::string relPath;

                        if (contentStr.find("BP_PlayerPawn") != std::string::npos) {
                            if (contentStr.find("uasset") != std::string::npos || decompBuffer[0] == 0xC1 || decompBuffer[3] == 0x9E) {
                                relPath = "ShadowTrackerExtra/Saved/Paks/BP_PlayerPawn.uasset";
                            } else {
                                relPath = "ShadowTrackerExtra/Saved/Paks/BP_PlayerPawn.uexp";
                            }
                        } else if (contentStr.find("ShadowTrackerExtra") != std::string::npos) {
                            size_t p = contentStr.find("ShadowTrackerExtra");
                            size_t endP = contentStr.find_first_of("\0\r\n ", p);
                            if (endP != std::string::npos && (endP - p) < 256) {
                                relPath = contentStr.substr(p, endP - p);
                            }
                        }

                        if (!relPath.empty()) {
                            fs::path destPath = fs::path(outputDir) / relPath;
                            fs::create_directories(destPath.parent_path());

                            std::ofstream out(destPath, std::ios::binary);
                            if (out.is_open()) {
                                out.write(reinterpret_cast<char*>(decompBuffer.data()), decompressedSize);
                                out.close();
                                extractedCount++;
                                sendTerminalLog(env, jCallback, logMethod, "📁 [CARVED] " + relPath);
                            }
                        }
                    }
                }
            }
        }
        currentOffset += (bufferSize - 65536);
    }

    sendTerminalLog(env, jCallback, logMethod, "[SUCCESS] Native Carver extracted " + std::to_string(extractedCount) + " files.");
    return extractedCount > 0;
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

    bool res = deepCarveStreams(env, jCallback, logMethod, cPakPath, cOutputDir);

    env->ReleaseStringUTFChars(jPakPath, cPakPath);
    env->ReleaseStringUTFChars(jOutputDir, cOutputDir);
    return res ? JNI_TRUE : JNI_FALSE;
}
