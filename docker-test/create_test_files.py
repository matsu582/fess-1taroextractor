#!/usr/bin/env python3
"""テスト用の旧一太郎ファイルを生成するスクリプト"""

import os
import struct

def create_legacy_jtd(filepath: str, text: str):
    """ver4-6形式の旧一太郎バイナリファイルを生成する"""
    # テキストをShift-JISにエンコード
    sjis_bytes = text.encode('cp932')
    
    # ファイルサイズ: ヘッダ(0x804) + テキスト + 余白
    file_size = 0x804 + len(sjis_bytes) + 256
    data = bytearray(file_size)
    
    # DOC\x00 シグネチャ
    data[0:4] = b'DOC\x00'
    
    # 検証値 0x22028919 (LE格納)
    data[0x3C] = 0x19
    data[0x3D] = 0x89
    data[0x3E] = 0x02
    data[0x3F] = 0x22
    
    # テキストサイズ (LE uint32, offset 0x800)
    struct.pack_into('<I', data, 0x800, len(sjis_bytes))
    
    # テキストデータ (offset 0x804)
    data[0x804:0x804 + len(sjis_bytes)] = sjis_bytes
    
    with open(filepath, 'wb') as f:
        f.write(data)
    print(f"作成: {filepath} ({len(sjis_bytes)} bytes text)")


def create_legacy_jtd_with_newlines(filepath: str, lines: list[str]):
    """改行コード(0xFE 0x41)付きの旧一太郎ファイルを生成する"""
    output = bytearray()
    for i, line in enumerate(lines):
        output.extend(line.encode('cp932'))
        if i < len(lines) - 1:
            # 0xFE 0x41 = 改行
            output.extend(b'\xfe\x41')
    
    file_size = 0x804 + len(output) + 256
    data = bytearray(file_size)
    
    # ヘッダ
    data[0:4] = b'DOC\x00'
    data[0x3C] = 0x19
    data[0x3D] = 0x89
    data[0x3E] = 0x02
    data[0x3F] = 0x22
    
    # テキストサイズ
    struct.pack_into('<I', data, 0x800, len(output))
    
    # テキストデータ
    data[0x804:0x804 + len(output)] = output
    
    with open(filepath, 'wb') as f:
        f.write(data)
    print(f"作成: {filepath} ({len(output)} bytes text, {len(lines)} lines)")


if __name__ == '__main__':
    doc_dir = os.path.join(os.path.dirname(__file__), 'documents')
    os.makedirs(doc_dir, exist_ok=True)
    
    # テスト1: 単純なASCIIテキスト
    create_legacy_jtd(
        os.path.join(doc_dir, 'test_simple.jsw'),
        'Hello World from Ichitaro ver4'
    )
    
    # テスト2: 日本語テキスト
    create_legacy_jtd(
        os.path.join(doc_dir, 'test_japanese.jaw'),
        'これは一太郎のテストファイルです。日本語テキストの抽出確認。'
    )
    
    # テスト3: 改行付き日本語テキスト
    create_legacy_jtd_with_newlines(
        os.path.join(doc_dir, 'test_multiline.jbw'),
        [
            '第一章 はじめに',
            'これは旧一太郎ファイルのテストです。',
            'Fess Extractorによるテキスト抽出を検証します。',
            '以上。'
        ]
    )
    
    print("\nテストファイル作成完了")
