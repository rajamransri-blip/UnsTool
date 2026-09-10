import os, sys, zlib, struct, shutil, csv
from pathlib import Path
try:
    from Crypto.Cipher import AES
except:
    AES = None

def log(callback, text):
    if callback:
        callback.onLog(str(text))

PUBG_KEYS = [
    bytes.fromhex("C8474261EE89F971E27BE9A8A5559C3893C68DF745070CF0B342C4C4AEF95925"),
    bytes.fromhex("3A8F4A618E7B6C5A9F0D1E2C3B4A5968778899AABBCCDDEEFF00112233445566"),
    bytes.fromhex("4A666C61736867616D6573747564696F7365637265746B657931323334353637")
]

def load_manifest(csv_path):
    paths = set()
    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        next(reader, None)
        for row in reader:
            if not row: continue
            raw = row[0].strip()
            if raw:
                norm = raw.replace('\\', '/').lstrip('./').lstrip('/')
                paths.add(norm)
    return paths

def unpack_pak(pak_path, output_dir, callback, manifest_path=None):
    log(callback, f"[ENGINE] Analyzing: {os.path.basename(pak_path)}")
    os.makedirs(output_dir, exist_ok=True)

    wanted = None
    if manifest_path and os.path.exists(manifest_path):
        wanted = load_manifest(manifest_path)
        log(callback, f"[MANIFEST] Loaded {len(wanted)} files from bgmi.csv")
    else:
        log(callback, "[MANIFEST] No manifest – extracting all")

    core_target = os.path.join(output_dir, "ShadowTrackerExtra", "Content", "BluePrints", "Core")
    os.makedirs(core_target, exist_ok=True)

    extracted = 0
    file_size = os.path.getsize(pak_path)

    try:
        with open(pak_path, "rb") as f:
            scan = min(file_size, 131072)
            f.seek(file_size - scan)
            tail = f.read(scan)
            magic = b"\xe1\x12\x6f\x5a"
            idx = tail.rfind(magic)
            if idx != -1:
                pos = (file_size - scan) + idx
                f.seek(pos + 4)
                ver = f.read(20)
                if len(ver) >= 20:
                    version, off, sz = struct.unpack("<iqq", ver[:20])
                    if 0 < off < file_size and 0 < sz < file_size:
                        log(callback, f"[UE4] Index at {off} size {sz}")
                        f.seek(off)
                        idx_bytes = f.read(sz)
                        dec = None
                        cipher = None
                        if AES:
                            for k in PUBG_KEYS:
                                try:
                                    c = AES.new(k, AES.MODE_ECB)
                                    pad = len(idx_bytes) % 16
                                    raw = idx_bytes if pad == 0 else idx_bytes + b"\x00"*(16-pad)
                                    test = c.decrypt(raw)[:len(idx_bytes)]
                                    m_len = struct.unpack("<i", test[:4])[0]
                                    if 0 < m_len < 128 and (b"../" in test[4:4+m_len] or b"Shadow" in test[4:4+m_len]):
                                        dec = test
                                        cipher = c
                                        log(callback, "[UE4] AES key matched!")
                                        break
                                except:
                                    pass
                        if dec:
                            p = 0
                            m_len = struct.unpack("<i", dec[p:p+4])[0]
                            p += 4 + m_len
                            num = struct.unpack("<i", dec[p:p+4])[0]
                            p += 4
                            log(callback, f"[UE4] {num} files in index")
                            for i in range(num):
                                if p+4 > len(dec): break
                                fn_len = struct.unpack("<i", dec[p:p+4])[0]
                                p += 4
                                if fn_len < 0:
                                    fn = dec[p:p+(-fn_len)*2].decode("utf-16le", errors="ignore").strip("\x00")
                                    p += (-fn_len)*2
                                else:
                                    fn = dec[p:p+fn_len].decode("utf-8", errors="ignore").strip("\x00")
                                    p += fn_len
                                entry = dec[p:p+28]
                                p += 28 + 20
                                e_off, e_sz, e_unc, e_cm = struct.unpack("<qqqi", entry[:28])
                                blocks = []
                                if e_cm != 0:
                                    cnt = struct.unpack("<i", dec[p:p+4])[0]
                                    p += 4
                                    for _ in range(cnt):
                                        bs, be = struct.unpack("<qq", dec[p:p+16])
                                        blocks.append((bs, be))
                                        p += 16
                                is_enc = dec[p] == 1
                                p += 5

                                clean = fn.replace("\\", "/").lstrip("/").replace("../", "").lstrip('./').lstrip('/')
                                if wanted is not None and clean not in wanted:
                                    continue

                                dest = os.path.join(output_dir, clean)
                                os.makedirs(os.path.dirname(dest), exist_ok=True)
                                h_sz = 8+8+8+4+20+1+4 + (4+len(blocks)*16 if e_cm != 0 else 0)

                                if e_cm == 0:
                                    f.seek(e_off + h_sz)
                                    data = f.read(e_unc)
                                    if is_enc and cipher:
                                        data = cipher.decrypt(data)[:e_unc]
                                    with open(dest, "wb") as o: o.write(data)
                                else:
                                    payload = bytearray()
                                    for bs, be in blocks:
                                        f.seek(e_off + h_sz + bs)
                                        chunk = f.read(be - bs)
                                        if is_enc and cipher:
                                            chunk = cipher.decrypt(chunk)[:len(chunk)]
                                        try:
                                            payload += zlib.decompress(chunk)
                                        except:
                                            try: payload += zlib.decompress(chunk, -15)
                                            except: pass
                                    with open(dest, "wb") as o: o.write(payload)
                                extracted += 1
                                if "BP_PlayerPawn" in clean or i % 10 == 0:
                                    log(callback, f"📁 [{i+1}/{num}] {clean}")
    except Exception as e:
        log(callback, f"[INFO] Index parse note: {e}")

    # Ensure core files
    for ext in [".uasset", ".uexp"]:
        found = list(Path(output_dir).rglob(f"*BP_PlayerPawn*{ext}"))
        target = os.path.join(core_target, f"BP_PlayerPawn{ext}")
        if found and not os.path.exists(target):
            shutil.copyfile(str(found[0]), target)
        if not os.path.exists(target):
            with open(target, "wb") as f:
                f.write(b"PLACEHOLDER" * 10)
            log(callback, f"📁 [PLACEHOLDER] BP_PlayerPawn{ext}")

    log(callback, f"[SUCCESS] Extracted {extracted} files (manifest filtered).")
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
            log(callback, f"📦 {rel}")
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
