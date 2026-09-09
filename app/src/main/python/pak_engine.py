import os
import sys
import zlib
import struct
from pathlib import Path

try:
    import zstandard as zstd
except Exception:
    zstd = None

def log(callback, text):
    if callback:
        callback.onLog(str(text))

def unpack_pak(pak_path, output_dir, callback):
    log(callback, f"[PYTHON] Reading archive: {os.path.basename(pak_path)}")
    os.makedirs(output_dir, exist_ok=True)
    
    extracted = 0
    file_size = os.path.getsize(pak_path)
    
    with open(pak_path, "rb") as f:
        chunk_size = 1024 * 1024 * 4
        offset = 0
        
        while offset < file_size:
            f.seek(offset)
            data = f.read(chunk_size)
            if not data:
                break
                
            pos = 0
            while True:
                idx = data.find(b"\x78\x9c", pos)
                if idx == -1:
                    idx = data.find(b"\x78\xda", pos)
                if idx == -1:
                    break
                    
                stream_data = data[idx:idx + 1024 * 1024 * 2]
                try:
                    decomp = zlib.decompress(stream_data)
                    if len(decomp) > 64:
                        if b"BP_PlayerPawn" in decomp:
                            if decomp.startswith(b"\x9e\x2a\x83\xc1") or b".uasset" in decomp:
                                name = "ShadowTrackerExtra/Saved/Paks/BP_PlayerPawn.uasset"
                            else:
                                name = "ShadowTrackerExtra/Saved/Paks/BP_PlayerPawn.uexp"
                                
                            target_file = os.path.join(output_dir, name)
                            os.makedirs(os.path.dirname(target_file), exist_ok=True)
                            with open(target_file, "wb") as out_f:
                                out_f.write(decomp)
                            extracted += 1
                            log(callback, f"📁 [FOUND] {name} ({len(decomp)} bytes)")
                        
                        elif b"ShadowTrackerExtra" in decomp:
                            p_start = decomp.find(b"ShadowTrackerExtra")
                            p_end = decomp.find(b"\x00", p_start)
                            if p_end != -1 and (p_end - p_start) < 200:
                                try:
                                    rel_name = decomp[p_start:p_end].decode("utf-8", errors="ignore")
                                    if "." in rel_name:
                                        target_file = os.path.join(output_dir, rel_name)
                                        os.makedirs(os.path.dirname(target_file), exist_ok=True)
                                        with open(target_file, "wb") as out_f:
                                            out_f.write(decomp)
                                        extracted += 1
                                        log(callback, f"📁 [TREE] {rel_name}")
                                except Exception:
                                    pass
                except Exception:
                    pass
                pos = idx + 2
                
            offset += (chunk_size - 65536)

    if extracted > 0:
        log(callback, f"[SUCCESS] Python extracted {extracted} target files.")
        return True
    return False

def repack_pak(source_dir, output_pak, callback):
    log(callback, f"[REPACK] Serializing tree from: {source_dir}")
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
            pak.write(struct.pack("<I", 0))
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

    log(callback, f"[FINISHED] Repacked {len(file_list)} files into {os.path.basename(output_pak)}")
    return True
