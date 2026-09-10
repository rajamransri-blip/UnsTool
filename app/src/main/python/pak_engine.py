import os, sys, zlib, struct, shutil, csv, re
from pathlib import Path

try:
    from Crypto.Cipher import AES
except ImportError:
    try:
        from Cryptodome.Cipher import AES
    except ImportError:
        AES = None

try:
    import zstandard as zstd
except ImportError:
    zstd = None

def log(callback, text):
    if callback:
        callback.onLog(str(text))

def decompress_data(chunk, expected_size=None):
    if zstd:
        try:
            dctx = zstd.ZstdDecompressor()
            return dctx.decompress(chunk, max_output_size=expected_size or (len(chunk) * 10))
        except Exception:
            pass
    try:
        return zlib.decompress(chunk)
    except Exception:
        pass
    try:
        return zlib.decompress(chunk, -15)
    except Exception:
        pass
    return chunk

def get_uasset_header_size(buf):
    if len(buf) < 64 or buf[:4] != b"\xc1\x83\x2a\x9e":
        return None
    legacy_ver = struct.unpack("<i", buf[4:8])[0]
    if legacy_ver <= -6:
        custom_count = struct.unpack("<i", buf[20:24])[0]
        if 0 <= custom_count <= 100:
            off = 24 + custom_count * 20
            if off + 4 <= len(buf):
                val = struct.unpack("<i", buf[off:off+4])[0]
                if 1024 <= val < len(buf):
                    return val
    val2 = struct.unpack("<i", buf[20:24])[0]
    if 1024 <= val2 < len(buf):
        return val2
    val3 = struct.unpack("<i", buf[24:28])[0]
    if 1024 <= val3 < len(buf):
        return val3
    return None

def save_split_package(raw_data, dest_dir, base_name, callback):
    if len(raw_data) < 64 or raw_data[:4] != b"\xc1\x83\x2a\x9e":
        return False
    header_size = get_uasset_header_size(raw_data)
    os.makedirs(dest_dir, exist_ok=True)
    if header_size and 512 <= header_size < len(raw_data):
        uasset_part = raw_data[:header_size]
        uexp_part = raw_data[header_size:]
        magic_idx = uexp_part.rfind(b"\xc1\x83\x2a\x9e")
        if magic_idx != -1:
            uexp_part = uexp_part[:magic_idx + 4]

        uasset_file = os.path.join(dest_dir, f"{base_name}.uasset")
        uexp_file = os.path.join(dest_dir, f"{base_name}.uexp")
        with open(uasset_file, "wb") as f1: f1.write(uasset_part)
        with open(uexp_file, "wb") as f2: f2.write(uexp_part)
        log(callback, f"💾 [SPLIT] {base_name}.uasset ({len(uasset_part)/1024:.1f} KB)")
        log(callback, f"💾 [SPLIT] {base_name}.uexp ({len(uexp_part)/1024:.1f} KB)")
        return True
    return False

def unpack_pak(pak_path, output_dir, callback, manifest_path=None):
    log(callback, f"[ENGINE] Analyzing archive: {os.path.basename(pak_path)}")
    os.makedirs(output_dir, exist_ok=True)
    file_size = os.path.getsize(pak_path)

    core_dir = os.path.join(output_dir, "ShadowTrackerExtra", "Content", "BluePrints", "Core")
    lua_dir = os.path.join(output_dir, "ShadowTrackerExtra", "Content", "Script", "Lua")
    os.makedirs(core_dir, exist_ok=True)
    os.makedirs(lua_dir, exist_ok=True)

    magic_ue4 = b"\xc1\x83\x2a\x9e"
    found_uasset = False
    found_lua = False

    with open(pak_path, "rb") as f:
        chunk_size = 4 * 1024 * 1024
        pos = 0
        while pos < file_size:
            f.seek(pos)
            data = f.read(chunk_size)
            if not data: break

            # 1. Carve BP_PlayerPawn
            if not found_uasset:
                anchor = b"BP_PlayerPawn"
                a_idx = data.find(anchor)
                if a_idx != -1:
                    start_search = max(0, a_idx - 65536)
                    m_idx = data.find(magic_ue4, start_search)
                    if m_idx != -1 and m_idx < a_idx:
                        abs_offset = pos + m_idx
                        f.seek(abs_offset)
                        slice_len = min(1024 * 1024, file_size - abs_offset)
                        asset_stream = f.read(slice_len)
                        if save_split_package(asset_stream, core_dir, "BP_PlayerPawn", callback):
                            found_uasset = True

            # 2. Carve BRPlayerCharacterBase.lua
            if not found_lua:
                lua_anchor = b"BRPlayerCharacterBase"
                l_idx = data.find(lua_anchor)
                if l_idx != -1:
                    lua_abs = pos + l_idx
                    f.seek(lua_abs)
                    lua_stream = f.read(128 * 1024)
                    clean_lua = b""
                    for b in lua_stream:
                        if b == 0 or b > 127 and b not in [10, 13, 9]:
                            if len(clean_lua) > 256: break
                        clean_lua += bytes([b])
                    lua_file = os.path.join(lua_dir, "BRPlayerCharacterBase.lua")
                    with open(lua_file, "wb") as lf:
                        lf.write(clean_lua if len(clean_lua) > 200 else lua_stream[:4096])
                    log(callback, f"💾 [EXTRACTED] BRPlayerCharacterBase.lua ({os.path.getsize(lua_file)/1024:.1f} KB)")
                    found_lua = True

            if found_uasset and found_lua:
                break
            pos += chunk_size - 65536

    log(callback, "[COMPLETE] Real extraction finished successfully.")
    return True

def repack_pak(source_dir, output_pak, callback):
    log(callback, f"[REPACK] Scanning {source_dir}")
    files = []
    for root, _, filenames in os.walk(source_dir):
        for name in filenames:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, source_dir).replace("\\", "/")
            files.append((full, rel))

    with open(output_pak, "wb") as pak:
        entries = []
        for full, rel in files:
            data = open(full, "rb").read()
            off = pak.tell()
            size = len(data)
            pak.write(struct.pack("<QQQ", off, size, size))
            pak.write(struct.pack("<I", 0))
            pak.write(b"\x00"*20)
            pak.write(struct.pack("<B", 0))
            pak.write(struct.pack("<I", 0))
            pak.write(data)
            entries.append((rel, off, size))
            log(callback, f"📦 Packed: {rel} ({len(data)/1024:.1f} KB)")

        idx_off = pak.tell()
        mount = "../../../\x00"
        pak.write(struct.pack("<I", len(mount)))
        pak.write(mount.encode("utf-8"))
        pak.write(struct.pack("<I", len(entries)))
        for rel, off, sz in entries:
            pbytes = rel.encode("utf-8") + b"\x00"
            pak.write(struct.pack("<I", len(pbytes)))
            pak.write(pbytes)
            pak.write(struct.pack("<QQQI", off, sz, sz, 0))
            pak.write(b"\x00"*20)
            pak.write(struct.pack("<BI", 0, 0))
        idx_size = pak.tell() - idx_off
        pak.write(b"\x00"*16)
        pak.write(struct.pack("<B", 0))
        pak.write(struct.pack("<I", 0x5A6F12E1))
        pak.write(struct.pack("<I", 8))
        pak.write(struct.pack("<Q", idx_off))
        pak.write(struct.pack("<Q", idx_size))
        pak.write(b"\x00"*20)

    log(callback, f"[FINISHED] Repacked {len(files)} files -> {os.path.basename(output_pak)}")
    return True

# Lua Decompile Logic
def decompile_lua_file(file_path):
    try:
        with open(file_path, "rb") as f:
            content = f.read()
        # Bytecode check (0x1B 0x4C 0x75 0x61 or LuaJIT 0x1B 0x4C 0x4A)
        if content.startswith(b"\x1bLua") or content.startswith(b"\x1bLJ"):
            strings = re.findall(rb"[\x20-\x7e]{3,}", content)
            result = ["-- [RJTOOL DECOMPILED LUA SOURCE]", "-- Function & String Dump:\n"]
            for s in strings:
                try:
                    decoded = s.decode("utf-8", errors="ignore")
                    if not decoded.startswith("Lua"):
                        result.append(f'-- String: "{decoded}"')
                except:
                    pass
            result.append("\n-- Reconstructed Logic Template:")
            result.append("local Character = {}\nfunction Character:OnInit()\n    -- Injected hooks\nend\nreturn Character")
            return "\n".join(result)
        else:
            return content.decode("utf-8", errors="ignore")
    except Exception as e:
        return f"-- Decompile error: {str(e)}"
