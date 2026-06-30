package com.github.matsu582.fess.extractor.onetaro

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
    0xA1.toByte, 0xB1.toByte, 0x1A.toByte, 0xE1.toByte
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
   * - UTF-16BE BOM (0xFEFF) が見つかればUTF-16BE (ver8以降)
   * - Shift-JIS第1バイト範囲のパターンが多ければShift-JIS (ver7)
   * - デフォルトはUTF-16BE
   */
  private def extractTextFromStream(data: Array[Byte], offset: Int): String =
    if offset >= data.length then return ""

    // null終端の後、偶数アライメントでテキスト開始位置を決定
    var start = offset
    start = offset + 1  // null終端分
    if start % 2 != 0 then start += 1

    if start + 2 > data.length then return ""

    // エンコーディング判定
    if isShiftJisEncoded(data, start) then
      extractShiftJisStreamText(data, start)
    else
      extractUtf16StreamText(data, start)

  /**
   * テキストデータがShift-JISエンコードかどうかを判定する
   *
   * UTF-16BE (ver8以降) のDocumentTextストリームには制御コード
   * 0x001C (フォーマットブロック開始) や 0x001F (テキスト開始) が
   * 偶数アライメントで必ず存在する。これらが見つかればUTF-16BE。
   * 見つからなければShift-JIS (ver7) と判定する。
   */
  private def isShiftJisEncoded(data: Array[Byte], offset: Int): Boolean =
    // UTF-16BE BOMがあればUTF-16BE確定
    if offset + 1 < data.length then
      val bom = readU16BE(data, offset)
      if bom == 0xFEFF then return false

    // UTF-16BE制御コードマーカーをスキャン（偶数アライメント位置で探索）
    val scanEnd = math.min(offset + 512, data.length - 1)
    var i = offset
    while i < scanEnd do
      if i % 2 == offset % 2 then // アライメント維持
        val code = readU16BE(data, i)
        // UTF-16BE制御コードが見つかればUTF-16BE確定
        if code == 0x001C || code == 0x001F || code == 0x000E then
          return false
      i += 2

    // UTF-16BE制御コードが見つからなければShift-JIS
    true

  /**
   * UTF-16BEストリームからテキストを抽出する
   *
   * 制御コードを解釈しながらテキストゾーン内の文字のみを収集する
   */
  private def extractUtf16StreamText(data: Array[Byte], offset: Int): String =
    val total = data.length
    val lines = new java.util.ArrayList[String]()
    val currentChars = new StringBuilder
    var i = offset
    var inTextZone = false

    while i < total - 1 do
      val code = readU16BE(data, i)

      if code == 0x001C then
        // フォーマットブロック開始: フラッシュして0x001Fまでスキップ
        flushChars(currentChars, lines)
        i = skipFormatBlock(data, i)
        inTextZone = true
      else if code == 0x001F then
        // テキスト開始マーカー
        inTextZone = true
        i += 2
      else if !inTextZone then
        // テキストゾーン外はスキップ
        i += 2
      else if code == 0x000A then
        // 改行
        flushChars(currentChars, lines)
        i += 2
      else if code == 0x000E then
        // セクション終了マーカー
        flushChars(currentChars, lines)
        inTextZone = false
        i += 2
      else if code < 0x0020 && code != 0x0009 then
        // 制御文字スキップ (タブ以外)
        i += 2
      else if code >= 0xD800 && code <= 0xDBFF && i + 3 < total then
        // サロゲートペア処理
        val lo = readU16BE(data, i + 2)
        if lo >= 0xDC00 && lo <= 0xDFFF then
          val cp = 0x10000 + ((code - 0xD800) << 10) + (lo - 0xDC00)
          currentChars.append(Character.toChars(cp))
          i += 4
        else
          i += 2
      else if code >= 0xDC00 && code <= 0xDFFF then
        // 孤立下位サロゲートをスキップ
        i += 2
      else
        // 通常のUTF-16BE文字
        currentChars.append(code.toChar)
        i += 2

    // 末尾の残りテキスト
    if inTextZone then
      flushChars(currentChars, lines)

    lines.toArray(Array.empty[String]).mkString("\n")

  /**
   * Shift-JISエンコードされたストリームからテキストを抽出する (ver7用)
   *
   * OLE2ストリーム内の制御コード (単バイト) も処理する:
   * - 0x1C: フォーマットブロック開始 → 0x1Fまでスキップ
   * - 0x1F: テキスト開始マーカー
   * - 0x0E: セクション終了
   */
  private def extractShiftJisStreamText(data: Array[Byte], offset: Int): String =
    val total = data.length
    val output = new java.io.ByteArrayOutputStream(total - offset)
    var i = offset
    var inTextZone = false

    while i < total do
      val b = data(i) & 0xFF

      if b == 0x1C then
        // フォーマットブロック開始: 0x1Fまでスキップ
        i += 1
        while i < total && (data(i) & 0xFF) != 0x1F do
          i += 1
        if i < total then
          // 0x1Fを消費してテキストゾーンに入る
          inTextZone = true
          i += 1
      else if b == 0x1F then
        // テキスト開始マーカー
        inTextZone = true
        i += 1
      else if b == 0x0E then
        // セクション終了
        if output.size() > 0 then output.write('\n')
        inTextZone = false
        i += 1
      else if b == 0x00 then
        // null終端またはパディング
        i += 1
      else if b == 0x0A || b == 0x0D then
        // 改行
        output.write('\n')
        i += 1
        if b == 0x0D && i < total && (data(i) & 0xFF) == 0x0A then
          i += 1
      else if isSjisLeadByte(b) && i + 1 < total then
        // Shift-JISマルチバイト文字
        output.write(b)
        output.write(data(i + 1) & 0xFF)
        i += 2
      else if b >= 0x20 && b <= 0x7E then
        // ASCII表示可能文字
        output.write(b)
        i += 1
      else if b >= 0xA1 && b <= 0xDF then
        // 半角カナ
        output.write(b)
        i += 1
      else
        // その他の制御コードはスキップ
        i += 1

    val text = new String(output.toByteArray, "MS932")
    text.replaceAll("\n{3,}", "\n\n").trim

  /** Shift-JISマルチバイト文字の第1バイトかどうか判定 */
  private def isSjisLeadByte(b: Int): Boolean =
    (b >= 0x81 && b <= 0x9F) || (b >= 0xE0 && b <= 0xFC)

  /** 文字バッファを行リストにフラッシュ */
  private def flushChars(
      chars: StringBuilder, lines: java.util.ArrayList[String]
  ): Unit =
    val text = chars.toString().trim
    if text.nonEmpty then lines.add(text)
    chars.clear()

  /** 0x001Cから始まるフォーマットブロックをスキップし、0x001F直後の位置を返す */
  private def skipFormatBlock(data: Array[Byte], pos: Int): Int =
    val total = data.length
    var i = pos + 2  // 0x001Cの次から
    while i < total - 1 do
      val code = readU16BE(data, i)
      if code == 0x001F then
        return i + 2  // 0x001Fの次がテキスト開始
      i += 2
    total

  /** ビッグエンディアンで2バイト整数を読み取る */
  private def readU16BE(data: Array[Byte], offset: Int): Int =
    ((data(offset) & 0xFF) << 8) | (data(offset + 1) & 0xFF)
