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

#define LOG_TAG "UpstoolEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

constexpr uint32_t PAK_MAGIC = 0x5A6F12E1;

#pragma pack(push, 1)
struct FPakCompressedBlock {
    int64_t compressedStart;
    int64_t compressedEnd;
};

struct FPakInfo {
    int32_t  version = 8;
    int64_t  indexOffset = 0;
    int64_t  indexSize = 0;
    uint8_t  indexHash[20] = {0};
    uint8_t  bEncryptedIndex = 0;
    uint32_t magic = PAK_MAGIC;
};
#pragma pack(pop)

struct FPakEntry {
    std::string filename;
    int64_t  offset = 0;
    int64_t  size = 0;
    int64_t  uncompressedSize = 0;
    int32_t  compressionMethod = 0; // 0 = None, 1 = Zlib
    uint8_t  hash[20] = {0};
    std::vector<FPakCompressedBlock> compressionBlocks;
    uint8_t  bEncrypted = 0;
    uint32_t compressionBlockSize = 0;

    int64_t getSerializedHeaderSize() const {
        int64_t baseSize = sizeof(int64_t) * 3 + sizeof(int32_t) + 20 + sizeof(uint8_t) + sizeof(uint32_t);
        if (compressionMethod != 0) {
            baseSize += sizeof(int32_t) + (compressionBlocks.size() * sizeof(FPakCompressedBlock));
        }
        return baseSize;
    }
};

static void sendTerminalLog(JNIEnv* env, jobject callback, jmethodID logMethod, const std::string& msg) {
    if (callback && logMethod) {
        jstring jmsg = env->NewStringUTF(msg.c_str());
        env->CallVoidMethod(callback, logMethod, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

static std::string readFString(std::ifstream& stream) {
    int32_t length = 0;
    if (!stream.read(reinterpret_cast<char*>(&length), sizeof(int32_t))) return "";
    if (length == 0) return "";
    if (length > 1048576 || length < -1048576) return "";

    if (length < 0) {
        int32_t ucs2Len = -length;
        std::vector<uint16_t> wbuf(ucs2Len);
        stream.read(reinterpret_cast<char*>(wbuf.data()), ucs2Len * sizeof(uint16_t));
        std::string res;
        res.reserve(ucs2Len);
        for (auto c : wbuf) {
            if (c != 0) res.push_back(static_cast<char>(c));
        }
        while (!res.empty() && res.back() == '\0') res.pop_back();
        return res;
    }

    std::vector<char> buffer(length);
    stream.read(buffer.data(), length);
    std::string res(buffer.data(), length);
    while (!res.empty() && res.back() == '\0') res.pop_back();
    return res;
}

static void writeFString(std::ofstream& stream, const std::string& str) {
    int32_t length = static_cast<int32_t>(str.length() + 1);
    stream.write(reinterpret_cast<const char*>(&length), sizeof(int32_t));
    stream.write(str.c_str(), length);
}

static FPakEntry readEntry(std::ifstream& stream) {
    FPakEntry entry;
    stream.read(reinterpret_cast<char*>(&entry.offset), sizeof(int64_t));
    stream.read(reinterpret_cast<char*>(&entry.size), sizeof(int64_t));
    stream.read(reinterpret_cast<char*>(&entry.uncompressedSize), sizeof(int64_t));
    stream.read(reinterpret_cast<char*>(&entry.compressionMethod), sizeof(int32_t));
    stream.read(reinterpret_cast<char*>(entry.hash), 20);

    if (entry.compressionMethod != 0) {
        int32_t blockCount = 0;
        stream.read(reinterpret_cast<char*>(&blockCount), sizeof(int32_t));
        if (blockCount > 0 && blockCount < 100000) {
            entry.compressionBlocks.resize(blockCount);
            for (int32_t i = 0; i < blockCount; ++i) {
                stream.read(reinterpret_cast<char*>(&entry.compressionBlocks[i]), sizeof(FPakCompressedBlock));
            }
        }
    }
    stream.read(reinterpret_cast<char*>(&entry.bEncrypted), sizeof(uint8_t));
    stream.read(reinterpret_cast<char*>(&entry.compressionBlockSize), sizeof(uint32_t));
    return entry;
}

static void writeEntry(std::ofstream& stream, const FPakEntry& entry) {
    stream.write(reinterpret_cast<const char*>(&entry.offset), sizeof(int64_t));
    stream.write(reinterpret_cast<const char*>(&entry.size), sizeof(int64_t));
    stream.write(reinterpret_cast<const char*>(&entry.uncompressedSize), sizeof(int64_t));
    stream.write(reinterpret_cast<const char*>(&entry.compressionMethod), sizeof(int32_t));
    stream.write(reinterpret_cast<const char*>(entry.hash), 20);

    if (entry.compressionMethod != 0) {
        int32_t blockCount = static_cast<int32_t>(entry.compressionBlocks.size());
        stream.write(reinterpret_cast<const char*>(&blockCount), sizeof(int32_t));
        for (const auto& block : entry.compressionBlocks) {
            stream.write(reinterpret_cast<const char*>(&block), sizeof(FPakCompressedBlock));
        }
    }
    stream.write(reinterpret_cast<const char*>(&entry.bEncrypted), sizeof(uint8_t));
    stream.write(reinterpret_cast<const char*>(&entry.compressionBlockSize), sizeof(uint32_t));
}

static bool decompressZlib(const uint8_t* src, size_t srcLen, uint8_t* dst, size_t dstLen) {
    z_stream strm{};
    strm.next_in = const_cast<Bytef*>(src);
    strm.avail_in = static_cast<uInt>(srcLen);
    strm.next_out = dst;
    strm.avail_out = static_cast<uInt>(dstLen);

    if (inflateInit(&strm) != Z_OK) return false;
    int ret = inflate(&strm, Z_FINISH);
    inflateEnd(&strm);
    return (ret == Z_STREAM_END || ret == Z_OK);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_upstool_paktool_PakEngine_nativeUnpackPak(
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

    sendTerminalLog(env, jCallback, logMethod, "[INIT] Opening archive: " + std::string(cPakPath));

    std::ifstream file(cPakPath, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        sendTerminalLog(env, jCallback, logMethod, "[ERROR] Cannot open source archive file.");
        env->ReleaseStringUTFChars(jPakPath, cPakPath);
        env->ReleaseStringUTFChars(jOutputDir, cOutputDir);
        return JNI_FALSE;
    }

    std::streamsize fileSize = file.tellg();
    if (fileSize < 128) {
        sendTerminalLog(env, jCallback, logMethod, "[ERROR] File size too small to be valid archive.");
        file.close();
        env->ReleaseStringUTFChars(jPakPath, cPakPath);
        env->ReleaseStringUTFChars(jOutputDir, cOutputDir);
        return JNI_FALSE;
    }

    // Scan last 64 KB backwards for PAK_MAGIC
    size_t scanSize = std::min(fileSize, static_cast<std::streamsize>(65536));
    std::vector<uint8_t> buffer(scanSize);
    file.seekg(fileSize - scanSize);
    file.read(reinterpret_cast<char*>(buffer.data()), scanSize);

    int64_t magicOffset = -1;
    for (int64_t i = scanSize - 4; i >= 0; --i) {
        if (*reinterpret_cast<uint32_t*>(&buffer[i]) == PAK_MAGIC) {
            magicOffset = fileSize - scanSize + i;
            break;
        }
    }

    if (magicOffset == -1) {
        sendTerminalLog(env, jCallback, logMethod, "[ERROR] Unreal PAK Magic bytes (0x5A6F12E1) not found.");
        file.close();
        env->ReleaseStringUTFChars(jPakPath, cPakPath);
        env->ReleaseStringUTFChars(jOutputDir, cOutputDir);
        return JNI_FALSE;
    }

    // In UE4: Magic is followed by Version, IndexOffset, IndexSize
    file.seekg(magicOffset + 4);
    int32_t version = 0;
    int64_t indexOffset = 0;
    int64_t indexSize = 0;
    file.read(reinterpret_cast<char*>(&version), sizeof(int32_t));
    file.read(reinterpret_cast<char*>(&indexOffset), sizeof(int64_t));
    file.read(reinterpret_cast<char*>(&indexSize), sizeof(int64_t));

    sendTerminalLog(env, jCallback, logMethod, "[INDEX] Found PAK v" + std::to_string(version) + " | Offset: " + std::to_string(indexOffset));

    if (indexOffset <= 0 || indexOffset >= fileSize) {
        sendTerminalLog(env, jCallback, logMethod, "[ERROR] Corrupt or invalid Index Table offset.");
        file.close();
        env->ReleaseStringUTFChars(jPakPath, cPakPath);
        env->ReleaseStringUTFChars(jOutputDir, cOutputDir);
        return JNI_FALSE;
    }

    file.seekg(indexOffset);
    std::string mountPoint = readFString(file);
    int32_t entryCount = 0;
    file.read(reinterpret_cast<char*>(&entryCount), sizeof(int32_t));

    sendTerminalLog(env, jCallback, logMethod, "[TREE] Mount: " + mountPoint + " | Total Files: " + std::to_string(entryCount));

    int successCount = 0;
    for (int32_t i = 0; i < entryCount; ++i) {
        std::string internalPath = readFString(file);
        FPakEntry entry = readEntry(file);

        std::string cleanRelPath = internalPath;
        while (cleanRelPath.rfind("../", 0) == 0) cleanRelPath.erase(0, 3);
        while (cleanRelPath.rfind("..\\", 0) == 0) cleanRelPath.erase(0, 3);
        while (!cleanRelPath.empty() && (cleanRelPath.front() == '/' || cleanRelPath.front() == '\\')) {
            cleanRelPath.erase(0, 1);
        }
        std::replace(cleanRelPath.begin(), cleanRelPath.end(), '\\', '/');

        fs::path destPath = fs::path(cOutputDir) / cleanRelPath;
        fs::create_directories(destPath.parent_path());

        std::ofstream outFile(destPath, std::ios::binary);
        if (!outFile.is_open()) continue;

        std::streampos savedIndexPos = file.tellg();
        int64_t dataOffset = entry.offset + entry.getSerializedHeaderSize();

        if (entry.compressionMethod == 0) {
            file.seekg(dataOffset);
            std::vector<char> chunk(65536);
            int64_t remain = entry.uncompressedSize;
            while (remain > 0) {
                std::streamsize readBytes = std::min(static_cast<int64_t>(chunk.size()), remain);
                file.read(chunk.data(), readBytes);
                outFile.write(chunk.data(), readBytes);
                remain -= readBytes;
            }
        } else {
            uint32_t blockSize = entry.compressionBlockSize > 0 ? entry.compressionBlockSize : 65536;
            std::vector<uint8_t> blockBuffer(blockSize);
            int64_t remain = entry.uncompressedSize;

            for (const auto& block : entry.compressionBlocks) {
                int64_t cSize = block.compressedEnd - block.compressedStart;
                std::vector<uint8_t> cBuffer(cSize);

                file.seekg(dataOffset + block.compressedStart);
                file.read(reinterpret_cast<char*>(cBuffer.data()), cSize);

                size_t outSize = std::min(static_cast<int64_t>(blockSize), remain);
                decompressZlib(cBuffer.data(), cSize, blockBuffer.data(), outSize);
                outFile.write(reinterpret_cast<char*>(blockBuffer.data()), outSize);
                remain -= outSize;
            }
        }

        outFile.close();
        file.seekg(savedIndexPos);
        successCount++;

        // Terminal stream update every 5 files or on uasset/uexp
        if (i % 5 == 0 || cleanRelPath.find(".uasset") != std::string::npos || cleanRelPath.find(".uexp") != std::string::npos) {
            sendTerminalLog(env, jCallback, logMethod, "📁 [" + std::to_string(i + 1) + "/" + std::to_string(entryCount) + "] " + cleanRelPath);
        }
    }

    file.close();
    sendTerminalLog(env, jCallback, logMethod, "[SUCCESS] Extracted " + std::to_string(successCount) + " files with full structure.");

    env->ReleaseStringUTFChars(jPakPath, cPakPath);
    env->ReleaseStringUTFChars(jOutputDir, cOutputDir);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_upstool_paktool_PakEngine_nativeRepackPak(
    JNIEnv* env,
    jobject,
    jstring jSourceDir,
    jstring jOutputPak,
    jobject jCallback
) {
    jclass cbClass = env->GetObjectClass(jCallback);
    jmethodID logMethod = env->GetMethodID(cbClass, "onLog", "(Ljava/lang/String;)V");

    const char* cSrc = env->GetStringUTFChars(jSourceDir, nullptr);
    const char* cOut = env->GetStringUTFChars(jOutputPak, nullptr);

    sendTerminalLog(env, jCallback, logMethod, "[REPACK] Serializing tree from: " + std::string(cSrc));

    std::ofstream pak(cOut, std::ios::binary | std::ios::trunc);
    if (!pak.is_open()) {
        sendTerminalLog(env, jCallback, logMethod, "[ERROR] Cannot create output archive: " + std::string(cOut));
        env->ReleaseStringUTFChars(jSourceDir, cSrc);
        env->ReleaseStringUTFChars(jOutputPak, cOut);
        return JNI_FALSE;
    }

    std::vector<FPakEntry> entries;

    for (const auto& dirEntry : fs::recursive_directory_iterator(cSrc)) {
        if (!dirEntry.is_regular_file()) continue;

        fs::path fullPath = dirEntry.path();
        std::string relPath = fs::relative(fullPath, cSrc).generic_string();

        int64_t fileOffset = pak.tellp();
        int64_t fileSize = fs::file_size(fullPath);

        FPakEntry entry;
        entry.filename = relPath;
        entry.offset = fileOffset;
        entry.size = fileSize;
        entry.uncompressedSize = fileSize;
        entry.compressionMethod = 0;
        entry.bEncrypted = 0;

        writeEntry(pak, entry);

        std::ifstream srcStream(fullPath, std::ios::binary);
        pak << srcStream.rdbuf();
        srcStream.close();

        entries.push_back(entry);
        sendTerminalLog(env, jCallback, logMethod, "📦 [PACK] " + relPath);
    }

    int64_t indexOffset = pak.tellp();
    writeFString(pak, "../../../");

    int32_t totalFiles = static_cast<int32_t>(entries.size());
    pak.write(reinterpret_cast<const char*>(&totalFiles), sizeof(int32_t));

    for (const auto& entry : entries) {
        writeFString(pak, entry.filename);
        writeEntry(pak, entry);
    }

    int64_t indexSize = static_cast<int64_t>(pak.tellp()) - indexOffset;

    FPakInfo footer;
    footer.version = 8;
    footer.indexOffset = indexOffset;
    footer.indexSize = indexSize;
    footer.magic = PAK_MAGIC;
    pak.write(reinterpret_cast<const char*>(&footer), sizeof(FPakInfo));

    pak.close();
    sendTerminalLog(env, jCallback, logMethod, "[FINISHED] Repacked " + std::to_string(totalFiles) + " files -> " + std::string(cOut));

    env->ReleaseStringUTFChars(jSourceDir, cSrc);
    env->ReleaseStringUTFChars(jOutputPak, cOut);
    return JNI_TRUE;
}
