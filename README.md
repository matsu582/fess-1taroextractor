# fess-1taroextractor

一太郎ファイル専用の [Fess](https://fess.codelibs.org/) Extractor プラグイン（Scala実装）

## 概要

[Fess](https://github.com/codelibs/fess) 全文検索サーバーに一太郎ファイルのテキスト抽出機能を追加する Extractor プラグインです。
Fat JAR を Fess のライブラリディレクトリに配置し、設定ファイルを追加するだけで、一太郎ファイルのクロール・インデックス登録・全文検索が可能になります。

## 対応形式

| バージョン | 拡張子 | 形式 | エンコーディング |
|-----------|--------|------|----------------|
| ver4 | `.jsw` | 独自バイナリ | Shift-JIS (MS932) |
| ver5 | `.jaw`, `.jtw` | 独自バイナリ | Shift-JIS (MS932) |
| ver6 | `.jbw`, `.juw` | 独自バイナリ | Shift-JIS (MS932) |
| ver7 | `.jfw`, `.jvw` | OLE2 Compound Document | UTF-16BE |
| ver8以降 | `.jtd`, `.jtt` | OLE2 Compound Document | UTF-16BE |

## 動作要件

- Java 21 以上
- [Fess](https://fess.codelibs.org/) 15.x（fess-crawler 15.7.0 に対応）
- Gradle 8.10（ビルド時のみ、`./gradlew` で自動取得）

## ビルド

```bash
# Fat JAR（依存ライブラリ込み、Fess配置用）
./gradlew fatJar

# 通常のJAR（Fess環境にApache POI等がある場合）
./gradlew jar

# テスト実行
./gradlew test
```

ビルド成果物:
- `build/libs/fess-1taroextractor-1.0.0-all.jar` — Fat JAR（依存込み、Fess配置用）
- `build/libs/fess-1taroextractor-1.0.0.jar` — 通常JAR

## Fessへの組み込み

### 1. JAR の配置

Fat JAR を Fess のライブラリディレクトリにコピーします。

```bash
cp build/libs/fess-1taroextractor-1.0.0-all.jar \
  /path/to/fess/app/WEB-INF/lib/
```

### 2. extractor.xml の設定

`app/WEB-INF/classes/crawler/extractor.xml` に Extractor コンポーネントと MIMEタイプのルーティングを追加します。

コンポーネント定義:
```xml
<component name="onetaroExtractor"
           class="com.github.matsu582.fess.extractor.onetaro.OnetaroExtractor" />
```

ExtractorFactory への登録:
```xml
<postConstruct name="addExtractor">
    <arg>["application/x-js-taro"]</arg>
    <arg>onetaroExtractor</arg>
</postConstruct>
```

設定例の完全なファイルは [`docker-fess-search/extractor.xml`](docker-fess-search/extractor.xml) を参照してください。

### 3. MIMEタイプの定義（custom-mimetypes.xml）

Apache Tika が一太郎ファイルを認識できるよう、カスタム MIMEタイプ定義を配置します。

配置先: `app/WEB-INF/classes/org/apache/tika/mime/custom-mimetypes.xml`

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

設定例は [`docker-fess-search/custom-mimetypes.xml`](docker-fess-search/custom-mimetypes.xml) を参照してください。

### 4. Fessの再起動

```bash
systemctl restart fess
```

再起動後、ファイルクロール設定で一太郎ファイルが格納されたディレクトリを対象に設定すると、自動的にテキスト抽出・インデックス登録が行われます。

## Docker での動作確認

`docker-fess-search/` に Fess + OpenSearch の Docker Compose 環境を用意しています。

```bash
# Fat JAR をビルド
./gradlew fatJar

# Docker Compose で起動
cd docker-fess-search
docker compose up -d
```

起動後、`http://localhost:8080/` で Fess の管理画面にアクセスできます。
`testfiles/` ディレクトリのテストファイルが `/testfiles` にマウントされるので、ファイルクロール設定で `file:/testfiles/` を対象にして動作確認できます。

## テスト

```bash
# ユニットテスト + Docker統合テスト（通常）
./gradlew test

# Fess検索統合テスト（Fess + OpenSearch 環境が必要、実行時間: 約5分）
./gradlew fessSearchTest
```

テストファイルは `scripts/generate_testfiles.py` で生成した合成データを使用しています。

## パッケージ構成

```
com.github.matsu582.fess.extractor.onetaro
├── OnetaroExtractor       — Fess AbstractExtractor 実装（MIMEタイプルーティング）
├── OnetaroOle2Parser      — ver7以降 OLE2形式パーサ（Apache POI使用）
└── OnetaroLegacyParser    — ver4-6 独自バイナリ形式パーサ
```

## 技術仕様

### 旧形式 (ver4-6) テキスト抽出

- ファイルシグネチャ: `DOC\x00`（先頭4バイト）
- フォーマット検証値: `0x22028919`（オフセット 0x3C、リトルエンディアン）
- テキスト領域: オフセット 0x804 から（サイズはオフセット 0x800 の4バイトLE値）
- 制御コード処理: `0xFE` 改行、`0x1F` 可変長スキップ、`0x1C` 書式制御、`0xFD` 罫線文字変換

### OLE2形式 (ver7以降) テキスト抽出

- `DocumentText` ストリームから `TextV.01` マーカーを検索
- UTF-16BE テキストを制御コード解釈しながら抽出
- 制御コード処理: `0x001C` フォーマットブロック、`0x001F` テキスト開始、`0x000A` 改行、`0x000E` セクション終了

## 注意事項

- 旧一太郎ファイル (ver4-6) は実ファイルが入手困難なため、合成テストデータによるユニットテストのみ実施しています
- 画像・図形の抽出には対応していません（テキストのみ）

## 関連プロジェクト

- [Fess](https://fess.codelibs.org/) — オープンソース全文検索サーバー（[GitHub](https://github.com/codelibs/fess)）
- [fess-crawler](https://github.com/codelibs/fess-crawler) — Fess クローラーライブラリ
- [o2md](https://github.com/matsu582/o2md) — Office/PDF/一太郎からMarkdown/テキストへの変換ツール

## ライセンス

MIT License
