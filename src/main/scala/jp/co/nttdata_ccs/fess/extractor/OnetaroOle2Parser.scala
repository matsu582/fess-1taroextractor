package jp.co.nttdata_ccs.fess.extractor

import org.apache.logging.log4j.LogManager
import org.apache.poi.poifs.filesystem.{POIFSFileSystem, DirectoryEntry}

import java.io.ByteArrayInputStream
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.{Charset, StandardCharsets}

/**
 * 一太郎 OLE2形式 (ver7以降) テキスト抽出パーサ
 *
 * OLE2 Compound Document形式の構造:
 * - DocumentTextストリーム内に TextV.01 マーカー
 * - ver7: Shift-JIS テキスト
 * - ver8以降: UTF-16BE テキスト
 */
object OnetaroOle2Parser:

  private val logger = LogManager.getLogger(getClass)

  // OLE2シグネチャ (先頭8バイト)
  private val Ole2Signature: Array[Byte] = Array(
    0xD0.toByte, 0xCF.toByte, 0x11.toByte, 0xE0.toByte,
    0xA1.toByte, 0xB4.toByte, 0x1A.toByte, 0xE1.toByte
  )

  // 一太郎ストリーム名
  private val DocumentTextStream = "DocumentText"

  // テキストセクション開始マーカー
  private val TextMarker = "TextV.01"
  private val TextMarkerBytes: Array[Byte] = TextMarker.getBytes(StandardCharsets.US_ASCII)

  /**
   * OLE2形式かどうかを判定する
   */
  def isOle2Format(data: Array[Byte]): Boolean =
    if data.length < 8 then return false
    data.slice(0, 8).sameElements(Ole2Signature)

  /**
   * OLE2形式の一太郎ファイルからテキストを抽出する
   */
  def extractText(data: Array[Byte]): String =
    if !isOle2Format(data) then
      throw new IllegalArgumentException("OLE2形式ではありません")

    val bais = new ByteArrayInputStream(data)
    val fs = new POIFSFileSystem(bais)
    try
      extractFromOle2(fs)
    finally
      fs.close()

  /**
   * OLE2ファイルシステムからDocumentTextストリームを読み取りテキストを抽出
   */
  private def extractFromOle2(fs: POIFSFileSystem): String =
    val root = fs.getRoot

    // DocumentTextストリームを検索
    if !root.hasEntry(DocumentTextStream) then
      throw new IllegalArgumentException(
        s"一太郎のDocumentTextストリームが見つかりません"
      )

    val entry = root.getEntry(DocumentTextStream)
      .asInstanceOf[org.apache.poi.poifs.filesystem.DocumentEntry]
    val streamData = new Array[Byte](entry.getSize)
    val dis = fs.createDocumentInputStream(DocumentTextStream)
    try
      dis.readFully(streamData)
    finally
      dis.close()

    // TextV.01マーカーを検索
    val markerPos = findMarker(streamData)
    if markerPos < 0 then
      throw new IllegalArgumentException(
        "TextV.01マーカーが見つかりません"
      )

    // マーカー後のテキストデータを抽出
    val textStart = markerPos + TextMarkerBytes.length
    extractTextFromStream(streamData, textStart)

  /**
   * ストリームデータ内でTextV.01マーカーの位置を検索する
   */
  private def findMarker(data: Array[Byte]): Int =
    val limit = data.length - TextMarkerBytes.length
    var i = 0
    while i <= limit do
      var matched = true
      var j = 0
      while j < TextMarkerBytes.length && matched do
        if data(i + j) != TextMarkerBytes(j) then
          matched = false
        j += 1
      if matched then return i
      i += 1
    -1

  /**
   * TextV.01マーカー以降のデータからテキストを抽出する
   *
   * エンコーディング判定:
   * - UTF-16BEのBOM (0xFEFF) がある場合: UTF-16BE
   * - それ以外: Shift-JIS (ver7互換)
   */
  private def extractTextFromStream(data: Array[Byte], offset: Int): String =
    if offset >= data.length then return ""

    // ヘッダ部分をスキップ (バージョン情報等)
    // 一般的に8バイト程度のヘッダが続く
    var pos = offset

    // テキスト開始位置を探索
    // テキストブロック開始を検出する
    val remaining = data.length - pos
    if remaining < 4 then return ""

    // テキストサイズの取得を試行 (4バイト LE)
    val textSizeCandidate = readU32LE(data, pos)

    // UTF-16BE判定: 先にBOM (FE FF) を確認
    val isUtf16 = if pos + 4 < data.length then
      (data(pos + 4) & 0xFF) == 0xFE && (data(pos + 5) & 0xFF) == 0xFF
    else if remaining > 8 then
      // NULL交じりのパターンでUTF-16を推定
      hasUtf16Pattern(data, pos + 4, math.min(32, remaining - 4))
    else
      false

    if isUtf16 then
      extractUtf16Text(data, pos + 4)
    else
      extractSjisStreamText(data, pos + 4)

  /**
   * UTF-16BEテキストを抽出する (ver8以降)
   */
  private def extractUtf16Text(data: Array[Byte], offset: Int): String =
    var pos = offset
    // BOMスキップ
    if pos + 2 <= data.length &&
       (data(pos) & 0xFF) == 0xFE && (data(pos + 1) & 0xFF) == 0xFF then
      pos += 2

    val sb = new StringBuilder
    val limit = data.length - 1

    while pos < limit do
      val hi = data(pos) & 0xFF
      val lo = data(pos + 1) & 0xFF
      val ch = (hi << 8) | lo

      ch match
        case 0x0000 =>
          // NULL終端チェック: 連続NULLならテキスト終了
          if pos + 3 < data.length &&
             (data(pos + 2) & 0xFF) == 0x00 &&
             (data(pos + 3) & 0xFF) == 0x00 then
            return sb.toString()
          pos += 2

        case 0x000D =>
          // CR → 改行
          sb.append('\n')
          pos += 2
          // 後続のLFをスキップ
          if pos + 1 < data.length &&
             (data(pos) & 0xFF) == 0x00 &&
             (data(pos + 1) & 0xFF) == 0x0A then
            pos += 2

        case 0x000A =>
          sb.append('\n')
          pos += 2

        case c if c >= 0x0020 =>
          sb.append(c.toChar)
          pos += 2

        case _ =>
          // 制御コード: スキップ
          pos += 2

    sb.toString()

  /**
   * Shift-JISテキストを抽出する (ver7互換)
   */
  private def extractSjisStreamText(data: Array[Byte], offset: Int): String =
    val output = new java.io.ByteArrayOutputStream(data.length - offset)
    var pos = offset
    val end = data.length

    while pos < end do
      val b = data(pos) & 0xFF

      b match
        case 0x00 =>
          // NULL: 連続NULLならテキスト終了
          if pos + 1 < end && (data(pos + 1) & 0xFF) == 0x00 then
            val text = new String(output.toByteArray, "MS932")
            return text.replace("\r\n", "\n").replace("\r", "\n")
          pos += 1

        case 0x0D =>
          output.write('\r')
          pos += 1

        case 0x0A =>
          output.write('\n')
          pos += 1

        case _ if isSjisLeadByte(b) =>
          if pos + 1 < end then
            output.write(b)
            output.write(data(pos + 1) & 0xFF)
            pos += 2
          else
            pos += 1

        case _ if b >= 0x20 && b <= 0x7E =>
          output.write(b)
          pos += 1

        case _ if b >= 0xA1 && b <= 0xDF =>
          // 半角カナ
          output.write(b)
          pos += 1

        case _ =>
          pos += 1

    val text = new String(output.toByteArray, "MS932")
    text.replace("\r\n", "\n").replace("\r", "\n")

  /** UTF-16パターンかどうかを簡易推定 */
  private def hasUtf16Pattern(
      data: Array[Byte], offset: Int, checkLen: Int
  ): Boolean =
    if checkLen < 4 then return false
    var nullCount = 0
    val limit = math.min(offset + checkLen, data.length)
    var i = offset
    while i < limit do
      if (data(i) & 0xFF) == 0x00 then nullCount += 1
      i += 1
    // 半数以上がNULLバイトならUTF-16の可能性大
    nullCount > checkLen / 3

  /** Shift-JISマルチバイト文字の第1バイトかどうか */
  private def isSjisLeadByte(b: Int): Boolean =
    (b >= 0x81 && b <= 0x9F) || (b >= 0xE0 && b <= 0xFC)

  /** リトルエンディアンで4バイト整数を読み取る */
  private def readU32LE(data: Array[Byte], offset: Int): Int =
    if offset + 4 > data.length then 0
    else
      (data(offset) & 0xFF) |
      ((data(offset + 1) & 0xFF) << 8) |
      ((data(offset + 2) & 0xFF) << 16) |
      ((data(offset + 3) & 0xFF) << 24)
