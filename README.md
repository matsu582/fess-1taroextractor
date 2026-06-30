# fess-1taroextractor

一太郎ファイル専用の Fess Extractor プラグイン（Scala実装）

## 概要

Fess全文検索サーバーに一太郎ファイルのテキスト抽出機能を追加するExtractorプラグインです。
JARファイルをFessのプラグインディレクトリに配置するだけで、一太郎ファイルのインデックス登録が可能になります。

## 対応形式

| バージョン | 拡張子 | 形式 |
|-----------|--------|------|
| ver4 | `.jsw` | 独自バイナリ (Shift-JIS) |
| ver5 | `.jaw`, `.jtw` | 独自バイナリ (Shift-JIS) |
| ver6 | `.jbw`, `.juw` | 独自バイナリ (Shift-JIS) |
| ver7 | `.jfw`, `.jvw` | OLE2 Compound Document |
| ver8以降 | `.jtd`, `.jtt` | OLE2 Compound Document (UTF-16BE) |

## ビルド

```bash
# 通常のJAR（Fess環境にPOIがある場合）
./gradlew jar

# Fat JAR（依存ライブラリ込み、単体で動作）
./gradlew fatJar

# テスト実行
./gradlew test
```

ビルド成果物:
- `build/libs/fess-1taroextractor-1.0.0.jar` — 通常JAR
- `build/libs/fess-1taroextractor-1.0.0-all.jar` — Fat JAR（依存込み）

## Fessへの組み込み

### 1. JARの配置

```bash
# Fat JARをFessプラグインディレクトリにコピー
cp build/libs/fess-1taroextractor-1.0.0-all.jar /path/to/fess/app/WEB-INF/lib/
```

### 2. extractor.xmlへの登録

`app/WEB-INF/classes/fess_config/extractor.xml` に以下を追加:

```xml
<component name="onetaroExtractor"
           class="jp.co.nttdata_ccs.fess.extractor.OnetaroExtractor"/>
```

ExtractorFactoryに登録:

```xml
<postConstruct name="addExtractor">
    <arg>"application/x-js-taro"</arg>
    <arg>onetaroExtractor</arg>
</postConstruct>
```

### 3. MIMEタイプの定義

旧一太郎拡張子をTikaに認識させるため、`custom-mimetypes.xml` を配置:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<mime-info>
    <mime-type type="application/x-js-taro">
        <glob pattern="*.jtd"/>
        <glob pattern="*.jtt"/>
        <glob pattern="*.jfw"/>
        <glob pattern="*.jvw"/>
        <glob pattern="*.jsw"/>
        <glob pattern="*.jaw"/>
        <glob pattern="*.jtw"/>
        <glob pattern="*.jbw"/>
        <glob pattern="*.juw"/>
        <magic priority="50">
            <match value="DOC\x00" type="string" offset="0"/>
        </magic>
    </mime-type>
</mime-info>
```

配置先: `app/WEB-INF/classes/org/apache/tika/mime/custom-mimetypes.xml`

### 4. Fessの再起動

```bash
systemctl restart fess
```

## 技術仕様

### パッケージ構成

```
jp.co.nttdata_ccs.fess.extractor
├── OnetaroExtractor       — Fess Extractorインターフェース実装
├── OnetaroLegacyParser    — ver4-6独自バイナリ形式パーサ
└── OnetaroOle2Parser      — ver7以降OLE2形式パーサ
```

### 旧形式 (ver4-6) テキスト抽出

- ファイルシグネチャ: `DOC\x00` (先頭4バイト)
- フォーマット検証値: `0x22028919` (オフセット0x3C-0x3F)
- テキスト領域: オフセット0x804から（サイズは0x800から取得）
- エンコーディング: Shift-JIS (MS932)
- 制御コード: 0xFE改行、0x1F可変長スキップ、0xFD罫線文字変換

### OLE2形式 (ver7以降) テキスト抽出

- DocumentTextストリームからTextV.01マーカーを検索
- ver7: Shift-JISテキスト
- ver8以降: UTF-16BEテキスト（BOM判定）

## 動作要件

- Java 21以上
- Fess 14.x / 15.x

## 注意事項

- 旧一太郎ファイル(ver4-6)は実ファイルが入手困難なため、合成テストデータによるユニットテストのみ実施しています
- 画像・図形の抽出には対応していません（テキストのみ）

## ライセンス

MIT License
