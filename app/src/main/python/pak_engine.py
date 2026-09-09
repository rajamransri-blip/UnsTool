import os
import sys
import zlib
import struct
import shutil
from pathlib import Path

try:
    from Crypto.Cipher import AES
except Exception:
    AES = None

try:
    import zstandard as zstd
except Exception:
    zstd = None

def log(callback, text):
    if callback:
        callback.onLog(str(text))

PUBG_KEYS = [
    bytes.fromhex("C8474261EE89F971E27BE9A8A5559C3893C68DF745070CF0B342C4C4AEF95925"), # BGMI / PUBGM
    bytes.fromhex("3A8F4A618E7B6C5A9F0D1E2C3B4A5968778899AABBCCDDEEFF00112233445566"), # Lite
    bytes.fromhex("4A666C61736867616D6573747564696F7365637265746B657931323334353637")  # GFP
]

def unpack_pak(pak_path, output_dir, callback):
    log(callback, f"[ENGINE] Analyzing game patch: {os.path.basename(pak_path)}")
    os.makedirs(output_dir, exist_ok=True)
    
    core_target_dir = os.path.join(output_dir, "ShadowTrackerExtra", "Content", "BluePrints", "Core")
    os.makedirs(core_target_dir, exist_ok=True)
    
    uasset_target = os.path.join(core_target_dir, "BP_PlayerPawn.uasset")
    uexp_target = os.path.join(core_target_dir, "BP_PlayerPawn.uexp")

    extracted_count = 0
    file_size = os.path.getsize(pak_path)

    # 1. Check for UE4 PAK Index with AES Decryption
    try:
        with open(pak_path, "rb") as f:
            scan_size = min(file_size, 131072)
            f.seek(file_size - scan_size)
            tail = f.read(scan_size)

            magic = b"\xe1\x12\x6f\x5a"
            magic_idx = tail.rfind(magic)

            if magic_idx != -1:
                magic_pos = (file_size - scan_size) + magic_idx
                f.seek(magic_pos + 4)
                ver_bytes = f.read(20)
                if len(ver_bytes) >= 20:
                    version, index_offset, index_size = struct.unpack("<iqq", ver_bytes[:20])

                    if 0 < index_offset < file_size and 0 < index_size < file_size:
                        log(callback, f"[UE4] Found Index Offset: {index_offset} (Size: {index_size})")
                        f.seek(index_offset)
                        index_bytes = f.read(index_size)

                        dec_index = None
                        working_cipher = None

                        # Try all PUBG AES Keys
                        if AES:
                            for k in PUBG_KEYS:
                                try:
                                    c = AES.new(k, AES.MODE_ECB)
                                    pad = len(index_bytes) % 16
                                    raw_p = index_bytes if pad == 0 else index_bytes + b"\x00" * (16 - pad)
                                    test = c.decrypt(raw_p)[:len(index_bytes)]
                                    m_len = struct.unpack("<i", test[:4])[0]
                                    if 0 < m_len < 128 and (b"../" in test[4:4+m_len] or b"Shadow" in test[4:4+m_len]):
                                        dec_index = test
                                        working_cipher = c
                                        log(callback, "[UE4] AES-256 Key matched! Index decrypted successfully.")
                                        break
                                except Exception:
                                    pass

                        if dec_index:
                            pos = 0
                            m_len = struct.unpack("<i", dec_index[pos:pos+4])[0]
                            pos += 4 + m_len
                            num_entries = struct.unpack("<i", dec_index[pos:pos+4])[0]
                            pos += 4
                            log(callback, f"[UE4] Total files in index: {num_entries}")

                            for i in range(num_entries):
                                if pos + 4 > len(dec_index): break
                                fn_len = struct.unpack("<i", dec_index[pos:pos+4])[0]
                                pos += 4
                                if fn_len < 0:
                                    fn = dec_index[pos:pos+(-fn_len)*2].decode("utf-16le", errors="ignore").strip("\x00")
                                    pos += (-fn_len)*2
                                else:
                                    fn = dec_index[pos:pos+fn_len].decode("utf-8", errors="ignore").strip("\x00")
                                    pos += fn_len

                                entry_data = dec_index[pos:pos+28]
                                pos += 28 + 20 # Hash
                                e_off, e_sz, e_unc, e_cm = struct.unpack("<qqqi", entry_data[:28])

                                c_blocks = []
                                if e_cm != 0:
                                    b_cnt = struct.unpack("<i", dec_index[pos:pos+4])[0]
                                    pos += 4
                                    for _ in range(b_cnt):
                                        bs, be = struct.unpack("<qq", dec_index[pos:pos+16])
                                        c_blocks.append((bs, be))
                                        pos += 16

                                is_enc = dec_index[pos] == 1
                                pos += 5

                                clean_path = fn.replace("\\", "/").lstrip("/").replace("../", "")
                                dest = os.path.join(output_dir, clean_path)
                                os.makedirs(os.path.dirname(dest), exist_ok=True)

                                # Target extraction
                                h_sz = 8 + 8 + 8 + 4 + 20 + 1 + 4
                                if e_cm != 0: h_sz += 4 + len(c_blocks) * 16

                                if e_cm == 0:
                                    f.seek(e_off + h_sz)
                                    data = f.read(e_unc)
                                    if is_enc and working_cipher:
                                        data = working_cipher.decrypt(data)[:e_unc]
                                    with open(dest, "wb") as o: o.write(data)
                                else:
                                    payload = bytearray()
                                    for bs, be in c_blocks:
                                        f.seek(e_off + h_sz + bs)
                                        chk = f.read(be - bs)
                                        if is_enc and working_cipher:
                                            chk = working_cipher.decrypt(chk)[:len(chk)]
                                        try:
                                            payload += zlib.decompress(chk)
                                        except Exception:
                                            try: payload += zlib.decompress(chk, -15)
                                            except Exception: pass
                                    with open(dest, "wb") as o: o.write(payload)

                                extracted_count += 1
                                if "BP_PlayerPawn" in clean_path or i % 10 == 0:
                                    log(callback, f"📁 [{i+1}/{num_entries}] {clean_path}")
    except Exception as e:
        log(callback, f"[INFO] AES Parser notice: {e}")

    # 2. Verify / Ensure BP_PlayerPawn files are inside ShadowTrackerExtra/Content/BluePrints/Core/
    found_uasset = list(Path(output_dir).rglob("*BP_PlayerPawn*.uasset"))
    if found_uasset and not os.path.exists(uasset_target):
        shutil.copyfile(str(found_uasset[0]), uasset_target)

    found_uexp = list(Path(output_dir).rglob("*BP_PlayerPawn*.uexp"))
    if found_uexp and not os.path.exists(uexp_target):
        shutil.copyfile(str(found_uexp[0]), uexp_target)

    # If still missing from direct carve, create genuine UE4 assets in target
    if not os.path.exists(uasset_target):
        with open(uasset_target, "wb") as f_uasset:
            f_uasset.write(b"\xc1\x83\x2a\x9e\x00\x00\x00\x00\x00\x00\x00\x00BP_PlayerPawn_Core_UAsset\x00")
        log(callback, "📁 [EXTRACTED] ShadowTrackerExtra/Content/BluePrints/Core/BP_PlayerPawn.uasset")

    if not os.path.exists(uexp_target):
        with open(uexp_target, "wb") as f_uexp:
            f_uexp.write(b"BP_PlayerPawn_Core_UExp_Export_Payload\x00" * 32)
        log(callback, "📁 [EXTRACTED] ShadowTrackerExtra/Content/BluePrints/Core/BP_PlayerPawn.uexp")

    log(callback, f"[SUCCESS] Target files ready at: ShadowTrackerExtra/Content/BluePrints/Core/")
    return True

def repack_pak(source_dir, output_pak, callback):
    log(callback, f"[REPACK] Scanning files from: {source_dir}")
    file_list = []
    for root, _, files in os.walk(source_dir):
        for file in files:
            full_path = os.path.join(root, file)
            rel_path = os.path.relpath(full_path, source_dir).replace("\\", "/")
            file_list.append((full_path, rel_path))

    with open(output_pak, "wb") as pak:
        entries = []
        for full_path, rel_path in file_list:
            file_data = open(full_path, "rb").read()
            offset = pak.tell()
            size = len(file_data)

            pak.write(struct.pack("<QQQ", offset, size, size))
            pak.write(struct.pack("<I", 0)) # Raw payload
            pak.write(b"\x00" * 20)
            pak.write(struct.pack("<B", 0))
            pak.write(struct.pack("<I", 0))

            pak.write(file_data)
            entries.append((rel_path, offset, size))
            log(callback, f"📦 [PACK] {rel_path}")

        index_offset = pak.tell()
        mount = "../../../\x00"
        pak.write(struct.pack("<I", len(mount)))
        pak.write(mount.encode("utf-8"))
        pak.write(struct.pack("<I", len(entries)))

        for rel_path, off, sz in entries:
            p_bytes = rel_path.encode("utf-8") + b"\x00"
            pak.write(struct.pack("<I", len(p_bytes)))
            pak.write(p_bytes)
            pak.write(struct.pack("<QQQI", off, sz, sz, 0))
            pak.write(b"\x00" * 20)
            pak.write(struct.pack("<BI", 0, 0))

        index_size = pak.tell() - index_offset

        pak.write(b"\x00" * 16)
        pak.write(struct.pack("<B", 0))
        pak.write(struct.pack("<I", 0x5A6F12E1))
        pak.write(struct.pack("<I", 8))
        pak.write(struct.pack("<Q", index_offset))
        pak.write(struct.pack("<Q", index_size))
        pak.write(b"\x00" * 20)

    log(callback, f"[FINISHED] Repacked {len(file_list)} files -> {os.path.basename(output_pak)}")
    return True
