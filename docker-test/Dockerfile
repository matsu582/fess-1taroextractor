FROM ghcr.io/codelibs/fess:15.7.0

USER root

# 一太郎Extractor JAR配置
COPY fess-1taroextractor-1.0.0-all.jar /usr/share/fess/app/WEB-INF/lib/fess-1taroextractor-1.0.0-all.jar

# Fessクローラー設定
COPY extractor.xml /usr/share/fess/app/WEB-INF/classes/crawler/extractor.xml

# 旧一太郎拡張子のMIMEタイプ定義（Tika用）
COPY custom-mimetypes.xml /usr/share/fess/app/WEB-INF/classes/org/apache/tika/mime/custom-mimetypes.xml

# テスト用ドキュメント
COPY documents /documents

USER fess
