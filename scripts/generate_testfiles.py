#!/usr/bin/env python3
"""
テスト用の合成一太郎ファイルを生成するスクリプト

OLE2形式 (.jtd) とレガシー形式 (.jsw) の両方を生成する
"""

import struct
import os

OUTPUT_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "testfiles")


def create_legacy_file(filename: str, text_sjis: bytes, ext: str = ".jsw"):
    """
    旧一太郎形式 (ver4-6) のテストファイルを生成する

    構造:
    - offset 0x00: DOC\\x00 シグネチャ
    - offset 0x3C: 検証値 0x22028919 (LE)
    - offset 0x800: テキストサイズ (4バイト LE)
    - offset 0x804: テキストデータ (Shift-JIS)
    """
    total_size = 0x804 + len(text_sjis) + 16
    data = bytearray(total_size)

    # シグネチャ
    data[0:4] = b"DOC\x00"

    # 検証値 (LE)
    struct.pack_into("<I", data, 0x3C, 0x22028919)

    # テキストサイズ (LE)
    struct.pack_into("<I", data, 0x800, len(text_sjis))

    # テキストデータ
    data[0x804 : 0x804 + len(text_sjis)] = text_sjis

    filepath = os.path.join(OUTPUT_DIR, filename + ext)
    with open(filepath, "wb") as f:
        f.write(data)
    print(f"  生成: {filepath} ({len(data)} bytes)")


def create_ole2_file(filename: str, text_utf16be: bytes, ext: str = ".jtd"):
    """
    一太郎 OLE2形式 (ver8+) のテストファイルを生成する

    olefile を使って OLE2 Compound Document を構築し、
    DocumentText ストリームに TextV.01 マーカー + テキストを配置する
    """
    import olefile

    # DocumentText ストリームの構築
    stream_data = bytearray()

    # TextV.01 マーカー
    stream_data.extend(b"TextV.01")
    # null 終端 + アライメント用パディング
    stream_data.extend(b"\x00")

    # 偶数アライメント
    if len(stream_data) % 2 != 0:
        stream_data.extend(b"\x00")

    # テキスト開始マーカー (0x001F) + テキスト + セクション終了 (0x000E)
    stream_data.extend(b"\x00\x1F")
    stream_data.extend(text_utf16be)
    stream_data.extend(b"\x00\x0E")

    # OLE2 ファイルとして書き出し
    filepath = os.path.join(OUTPUT_DIR, filename + ext)

    # 空の OLE2 ファイルを作成してストリームを追加
    ole = olefile.OleFileIO.__new__(olefile.OleFileIO)
    # olefile では直接作成が難しいので、低レベルで構築

    # 代替手法: cfb (Compound File Binary) を手動構築
    write_ole2_manual(filepath, stream_data)
    print(f"  生成: {filepath}")


def write_ole2_manual(filepath: str, doc_text_data: bytes):
    """
    OLE2 Compound File Binary Format を手動で構築する

    最小限の構造:
    - ヘッダセクタ (512 bytes)
    - FAT セクタ (512 bytes)
    - Directory セクタ (512 bytes)
    - Mini FAT セクタ (512 bytes)
    - Mini Stream セクタ群
    """
    SECTOR_SIZE = 512
    MINI_SECTOR_SIZE = 64
    MINI_STREAM_CUTOFF = 0x1000

    # データが小さい場合はミニストリーム、大きい場合は通常セクタ
    use_mini_stream = len(doc_text_data) < MINI_STREAM_CUTOFF

    if use_mini_stream:
        # ミニストリームを使うケース
        # ミニセクタ数
        mini_sector_count = (len(doc_text_data) + MINI_SECTOR_SIZE - 1) // MINI_SECTOR_SIZE
        # ミニストリームコンテナのサイズ (通常セクタ単位)
        mini_stream_size = mini_sector_count * MINI_SECTOR_SIZE
        container_sectors = (mini_stream_size + SECTOR_SIZE - 1) // SECTOR_SIZE

        # セクタ配置:
        # 0: FAT
        # 1: Directory
        # 2: Mini FAT
        # 3..(3+container_sectors-1): Mini Stream Container
        fat_sector = 0
        dir_sector = 1
        mini_fat_sector = 2
        container_start = 3
        total_sectors = container_start + container_sectors
    else:
        # 通常セクタを使うケース
        data_sectors = (len(doc_text_data) + SECTOR_SIZE - 1) // SECTOR_SIZE
        fat_sector = 0
        dir_sector = 1
        data_start = 2
        total_sectors = data_start + data_sectors

    # === ヘッダ構築 (512 bytes) ===
    header = bytearray(SECTOR_SIZE)

    # OLE2 シグネチャ
    header[0:8] = b"\xD0\xCF\x11\xE0\xA1\xB1\x1A\xE1"
    # Minor version
    struct.pack_into("<H", header, 0x18, 0x003E)
    # Major version (3)
    struct.pack_into("<H", header, 0x1A, 0x0003)
    # Byte order (little endian)
    struct.pack_into("<H", header, 0x1C, 0xFFFE)
    # Sector size power (9 = 512)
    struct.pack_into("<H", header, 0x1E, 0x0009)
    # Mini sector size power (6 = 64)
    struct.pack_into("<H", header, 0x20, 0x0006)
    # Total sectors in FAT (directory)
    struct.pack_into("<I", header, 0x2C, 1)
    # First directory sector SECID
    struct.pack_into("<I", header, 0x30, dir_sector)
    # Mini stream cutoff
    struct.pack_into("<I", header, 0x38, MINI_STREAM_CUTOFF)

    if use_mini_stream:
        # First mini FAT sector
        struct.pack_into("<I", header, 0x3C, mini_fat_sector)
        # Number of mini FAT sectors
        struct.pack_into("<I", header, 0x40, 1)
    else:
        # No mini FAT
        struct.pack_into("<i", header, 0x3C, -2)  # ENDOFCHAIN
        struct.pack_into("<I", header, 0x40, 0)

    # First DIFAT sector (none)
    struct.pack_into("<i", header, 0x44, -2)  # ENDOFCHAIN
    # Number of DIFAT sectors
    struct.pack_into("<I", header, 0x48, 0)

    # DIFAT array (109 entries starting at offset 0x4C)
    # First entry points to FAT sector
    struct.pack_into("<I", header, 0x4C, fat_sector)
    # Rest are free (-1)
    for i in range(1, 109):
        struct.pack_into("<i", header, 0x4C + i * 4, -1)

    # === FAT セクタ構築 ===
    fat = bytearray(SECTOR_SIZE)
    # Initialize all as free (-1)
    for i in range(SECTOR_SIZE // 4):
        struct.pack_into("<i", fat, i * 4, -1)

    # FAT sector 自体は FAT sector マーカー (-3)
    struct.pack_into("<i", fat, fat_sector * 4, -3)
    # Directory sector: end of chain (-2)
    struct.pack_into("<i", fat, dir_sector * 4, -2)

    if use_mini_stream:
        # Mini FAT sector: end of chain
        struct.pack_into("<i", fat, mini_fat_sector * 4, -2)
        # Container sectors chain
        for i in range(container_sectors):
            sid = container_start + i
            if i < container_sectors - 1:
                struct.pack_into("<i", fat, sid * 4, sid + 1)
            else:
                struct.pack_into("<i", fat, sid * 4, -2)  # end of chain
    else:
        for i in range(data_sectors):
            sid = data_start + i
            if i < data_sectors - 1:
                struct.pack_into("<i", fat, sid * 4, sid + 1)
            else:
                struct.pack_into("<i", fat, sid * 4, -2)

    # === Directory セクタ構築 ===
    directory = bytearray(SECTOR_SIZE)

    # Root Entry (128 bytes)
    root_name = "Root Entry".encode("utf-16-le")
    directory[0 : len(root_name)] = root_name
    # Name size (including null terminator)
    struct.pack_into("<H", directory, 0x40, len(root_name) + 2)
    # Object type: root storage (5)
    directory[0x42] = 0x05
    # Color: black (1)
    directory[0x43] = 0x01
    # Left/Right/Child SID
    struct.pack_into("<i", directory, 0x44, -1)  # left
    struct.pack_into("<i", directory, 0x48, -1)  # right
    struct.pack_into("<i", directory, 0x4C, 1)   # child = DocumentText entry

    if use_mini_stream:
        # Root entry start sector (mini stream container)
        struct.pack_into("<I", directory, 0x74, container_start)
        # Root entry size (mini stream container size)
        struct.pack_into("<I", directory, 0x78, mini_stream_size)
    else:
        struct.pack_into("<i", directory, 0x74, -2)
        struct.pack_into("<I", directory, 0x78, 0)

    # DocumentText Entry (128 bytes, offset 0x80)
    dt_offset = 0x80
    dt_name = "DocumentText".encode("utf-16-le")
    directory[dt_offset : dt_offset + len(dt_name)] = dt_name
    struct.pack_into("<H", directory, dt_offset + 0x40, len(dt_name) + 2)
    # Object type: stream (2)
    directory[dt_offset + 0x42] = 0x02
    # Color: black (1)
    directory[dt_offset + 0x43] = 0x01
    # Left/Right/Child
    struct.pack_into("<i", directory, dt_offset + 0x44, -1)
    struct.pack_into("<i", directory, dt_offset + 0x48, -1)
    struct.pack_into("<i", directory, dt_offset + 0x4C, -1)

    if use_mini_stream:
        # Start mini sector
        struct.pack_into("<I", directory, dt_offset + 0x74, 0)
    else:
        struct.pack_into("<I", directory, dt_offset + 0x74, data_start)

    # Stream size
    struct.pack_into("<I", directory, dt_offset + 0x78, len(doc_text_data))

    # === 出力 ===
    with open(filepath, "wb") as f:
        f.write(header)

        # セクタを順番に書き出し
        sectors = [None] * total_sectors
        sectors[fat_sector] = fat
        sectors[dir_sector] = directory

        if use_mini_stream:
            # Mini FAT セクタ
            mini_fat = bytearray(SECTOR_SIZE)
            for i in range(SECTOR_SIZE // 4):
                struct.pack_into("<i", mini_fat, i * 4, -1)
            for i in range(mini_sector_count):
                if i < mini_sector_count - 1:
                    struct.pack_into("<i", mini_fat, i * 4, i + 1)
                else:
                    struct.pack_into("<i", mini_fat, i * 4, -2)
            sectors[mini_fat_sector] = mini_fat

            # Mini stream container
            container_data = bytearray(container_sectors * SECTOR_SIZE)
            container_data[: len(doc_text_data)] = doc_text_data
            for i in range(container_sectors):
                s = container_data[i * SECTOR_SIZE : (i + 1) * SECTOR_SIZE]
                sectors[container_start + i] = s
        else:
            # 通常データセクタ
            padded = doc_text_data + b"\x00" * (
                data_sectors * SECTOR_SIZE - len(doc_text_data)
            )
            for i in range(data_sectors):
                sectors[data_start + i] = padded[
                    i * SECTOR_SIZE : (i + 1) * SECTOR_SIZE
                ]

        for s in sectors:
            if s is not None:
                f.write(s)
            else:
                f.write(b"\x00" * SECTOR_SIZE)


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print("=== レガシー形式テストファイル生成 ===")

    # テスト1: 基本的な日本語テキスト (.jsw)
    text1 = "テスト文書です。一太郎レガシー形式のサンプルファイルです。"
    sjis1 = text1.encode("cp932")
    create_legacy_file("test_legacy_basic", sjis1, ".jsw")

    # テスト2: 改行を含むテキスト (.jaw)
    # 0xFE 0x41 が改行
    text2_parts = [
        "見出し行".encode("cp932"),
        b"\xFE\x41",
        "本文の内容です。".encode("cp932"),
        b"\xFE\x41",
        "末尾の行です。".encode("cp932"),
    ]
    sjis2 = b"".join(text2_parts)
    create_legacy_file("test_legacy_newline", sjis2, ".jaw")

    # テスト3: 罫線文字を含むテキスト (.jbw)
    text3_parts = [
        "表の上".encode("cp932"),
        b"\xFE\x41",
        b"\xFD\x23",  # ┌
        b"\xFD\x21",  # ─
        b"\xFD\x24",  # ┐
        b"\xFE\x41",
        "表の下".encode("cp932"),
    ]
    sjis3 = b"".join(text3_parts)
    create_legacy_file("test_legacy_keisen", sjis3, ".jbw")

    print("\n=== OLE2形式テストファイル生成 ===")

    # テスト4: 基本的なOLE2テキスト (.jtd)
    text4 = "一太郎OLE2形式のテスト文書です。検索テスト用サンプル。"
    utf16_4 = text4.encode("utf-16-be")
    create_ole2_file("test_ole2_basic", utf16_4, ".jtd")

    # テスト5: 改行を含むOLE2テキスト (.jtd)
    lines5 = ["文書のタイトル", "本文の第一段落です。", "本文の第二段落です。"]
    utf16_5 = bytearray()
    for i, line in enumerate(lines5):
        utf16_5.extend(line.encode("utf-16-be"))
        if i < len(lines5) - 1:
            utf16_5.extend(b"\x00\x0A")  # 改行
    create_ole2_file("test_ole2_multiline", bytes(utf16_5), ".jtd")

    # テスト6: ver7形式 (.jfw) — Shift-JISエンコーディング
    text6 = "一太郎バージョン7形式のテストです。"
    sjis_6 = text6.encode("cp932")
    create_ole2_file("test_ole2_v7", sjis_6, ".jfw")

    print("\n生成完了")


if __name__ == "__main__":
    main()
