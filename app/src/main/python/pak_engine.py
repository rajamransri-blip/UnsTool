import os, sys, zlib, struct, shutil, csv
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

PUBG_KEYS = [
    bytes.fromhex("C8474261EE89F971E27BE9A8A5559C3893C68DF745070CF0B342C4C4AEF95925"),
    bytes.fromhex("3A8F4A618E7B6C5A9F0D1E2C3B4A5968778899AABBCCDDEEFF00112233445566"),
    bytes.fromhex("4A666C61736867616D6573747564696F7365637265746B657931323334353637"),
    bytes.fromhex("E26B7485A5741893A04278F3B3B48962D7142A9B8D4C84712F0174A53E9B420C"),
    bytes.fromhex("0000000000000000000000000000000000000000000000000000000000000000")
]

def decrypt_aes_block(cipher, data):
    if not cipher or not data:
        return data
    pad = len(data) % 16
    if pad != 0:
        padded = data + b"\x00" * (16 - pad)
        return cipher.decrypt(padded)[:len(data)]
    return cipher.decrypt(data)

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
    try:
        return zlib.decompress(chunk, 31)
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

        # Verify ending magic of uexp
        magic_idx = uexp_part.rfind(b"\xc1\x83\x2a\x9e")
        if magic_idx != -1:
            uexp_part = uexp_part[:magic_idx + 4]

        uasset_file = os.path.join(dest_dir, f"{base_name}.uasset")
        uexp_file = os.path.join(dest_dir, f"{base_name}.uexp")

        with open(uasset_file, "wb") as f1:
            f1.write(uasset_part)

        with open(uexp_file, "wb") as f2:
            f2.write(uexp_part)

        log(callback, f"💾 [SPLIT] {base_name}.uasset ({len(uasset_part)/1024:.1f} KB clean code)")
        log(callback, f"💾 [SPLIT] {base_name}.uexp ({len(uexp_part)/1024:.1f} KB bytecode)")
        return True
    return False

def unpack_pak(pak_path, output_dir, callback, manifest_path=None):
    log(callback, f"[ENGINE] Analyzing archive: {os.path.basename(pak_path)}")
    os.makedirs(output_dir, exist_ok=True)

    file_size = os.path.getsize(pak_path)
    log(callback, f"[INFO] Target size: {file_size / (1024*1024):.2f} MB")

    # Initiate deep chunk search
    return deep_chunk_carve(pak_path, output_dir, callback)

def deep_chunk_carve(pak_path, output_dir, callback):
    log(callback, "[CHUNK SCAN] Scanning stream for authentic UE4 packages...")
    found = False
    file_size = os.path.getsize(pak_path)
    core_dir = os.path.join(output_dir, "ShadowTrackerExtra", "Content", "BluePrints", "Core")
    os.makedirs(core_dir, exist_ok=True)

    magic_ue4 = b"\xc1\x83\x2a\x9e" # 0x9E2A83C1

    with open(pak_path, "rb") as f:
        chunk_size = 4 * 1024 * 1024 # 4MB chunk buffer
        pos = 0

        while pos < file_size:
            f.seek(pos)
            data = f.read(chunk_size)
            if not data:
                break

            anchor = b"BP_PlayerPawn"
            a_idx = data.find(anchor)
            if a_idx != -1:
                start_search = max(0, a_idx - 65536)
                m_idx = data.find(magic_ue4, start_search)
                if m_idx != -1 and m_idx < a_idx:
                    abs_offset = pos + m_idx
                    f.seek(abs_offset)
                    
                    # Read complete asset package slice
                    slice_len = min(1024 * 1024, file_size - abs_offset)
                    asset_stream = f.read(slice_len)

                    success = save_split_package(asset_stream, core_dir, "BP_PlayerPawn", callback)
                    if success:
                        found = True
                        break

            pos += chunk_size - 65536

    if found:
        log(callback, "[SUCCESS] BP_PlayerPawn separated cleanly into original .uasset & .uexp files.")
        return True
    return False

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
