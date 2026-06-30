package com.github.matsu582.fess.extractor.onetaro

import org.apache.logging.log4j.LogManager

/**
 * 旧一太郎ファイル (ver4-6) テキスト抽出パーサ
 *
 * 独自バイナリ形式の構造:
 * - 先頭4バイト: DOC\x00 シグネチャ
 * - オフセット0x3C-0x3F: フォーマット検証値 (0x22028919)
 * - オフセット0x800: テキストサイズ (4バイト LE)
 * - オフセット0x804: テキスト開始位置
 * - テキスト: Shift-JIS (CP932) エンコード + 制御コード混在
 */
object OnetaroLegacyParser:

  private val logger = LogManager.getLogger(getClass)

  // ファイルシグネチャ
  private val MagicDoc: Array[Byte] = Array(0x44, 0x4F, 0x43, 0x00).map(_.toByte)

  // ヘッダ内の検証値
  private val HeaderValidationOffset = 0x3C
  private val HeaderValidationValue = 0x22028919

  // テキストデータの位置
  private val TextSizeOffset = 0x800
  private val TextStartOffset = 0x804

  // ブロックテーブル関連
  private val BlockTableInfoOffset = 0xE2
  private val BlockEntrySize = 0x40

  // 改行を示す0xFE後続バイトの範囲 (A-C, E-G, I-K)
  private val NewlineRanges: Array[(Int, Int)] = Array(
    (0x41, 0x43), (0x45, 0x47), (0x49, 0x4B)
  )

  // 罫線文字変換テーブル (0xFD後のバイト → Shift-JISバイトペア)
  private val KeisenTable: Map[Int, Array[Byte]] = Map(
    0x21 -> Array(0x84.toByte, 0x9F.toByte), // ─
    0x22 -> Array(0x84.toByte, 0xA0.toByte), // │
    0x23 -> Array(0x84.toByte, 0xA1.toByte), // ┌
    0x24 -> Array(0x84.toByte, 0xA2.toByte), // ┐
    0x25 -> Array(0x84.toByte, 0xA3.toByte), // ┘
    0x26 -> Array(0x84.toByte, 0xA4.toByte), // └
    0x27 -> Array(0x84.toByte, 0xA5.toByte), // ├
    0x28 -> Array(0x84.toByte, 0xA6.toByte), // ┬
    0x29 -> Array(0x84.toByte, 0xA7.toByte)  // ┤
  )
  // デフォルト: 中黒 (・)
  private val KeisenDefault: Array[Byte] = Array(0x81.toByte, 0x45.toByte)

  /**
   * 旧一太郎形式 (DOC\x00) かどうかを判定する
   */
  def isLegacyFormat(data: Array[Byte]): Boolean =
    if data.length < TextStartOffset then return false
    // シグネチャ確認
    if !data.slice(0, 4).sameElements(MagicDoc) then return false
    // 検証値確認
    val validationVal = readU32LE(data, HeaderValidationOffset)
    validationVal == HeaderValidationValue

  /**
   * 旧一太郎バイナリからテキストを抽出する
   */
  def extractText(data: Array[Byte]): String =
    if !isLegacyFormat(data) then
      throw new IllegalArgumentException(
        "旧一太郎形式(ver4-6)として認識できません"
      )

    // テキスト領域の特定
    val (textStart, textSize) = findTextRegion(data)

    // Shift-JISテキストの抽出
    val sjisBytes = extractSjisText(data, textStart, textSize)

    if sjisBytes.isEmpty then
      throw new IllegalArgumentException("テキストを抽出できませんでした")

    // Shift-JIS → Unicode変換
    val text = new String(sjisBytes.toArray, "MS932")

    // CR+LFの正規化
    text.replace("\r\n", "\n").replace("\r", "\n")

  /**
   * テキスト領域の開始オフセットとサイズを特定する
   */
  private def findTextRegion(data: Array[Byte]): (Int, Int) =
    val fileSize = data.length

    // ブロックテーブル情報の読み取り
    val blockStartVal = readU16LE(data, BlockTableInfoOffset)
    val blockCount = readU16LE(data, BlockTableInfoOffset + 2)
    val baseOffsetVal = readU16LE(data, BlockTableInfoOffset + 4)
    val blockStride = readU16LE(data, BlockTableInfoOffset + 6)

    // ブロックテーブルのスキャン
    var textBlockOffset = -1
    if blockCount > 0 && blockStartVal > 0 then
      var i = 0
      while i < blockCount && textBlockOffset < 0 do
        val entryPos = blockStartVal + i * BlockEntrySize
        if entryPos + BlockEntrySize <= fileSize then
          val marker = readU16LE(data, entryPos)
          if marker == 0x0001 then
            val marker2Offset = entryPos + 2
            if marker2Offset + 2 <= fileSize then
              val marker2 = readU16LE(data, marker2Offset)
              if marker2 == 0x0002 then
                textBlockOffset = entryPos
        i += 1

    if textBlockOffset >= 0 then
      val pageCount = readU32LE(data, textBlockOffset + 8)
      if pageCount > 0 && blockStride > 0 then
        val calcOffset = blockStride * (pageCount - 1) + baseOffsetVal
        if calcOffset + 4 <= fileSize then
          val textSize = readU32LE(data, calcOffset)
          if textSize > 0 && textSize < fileSize then
            logger.debug(
              s"[旧JTD] ブロックテーブルから特定: offset=0x${TextStartOffset.toHexString}, size=$textSize"
            )
            return (TextStartOffset, textSize)

    // フォールバック: オフセット0x800から4バイトでサイズ取得
    var textSize = readU32LE(data, TextSizeOffset)
    if textSize <= 0 || textSize > fileSize then
      textSize = fileSize - TextStartOffset

    logger.debug(
      s"[旧JTD] フォールバック位置使用: offset=0x${TextStartOffset.toHexString}, size=$textSize"
    )
    (TextStartOffset, textSize)

  /**
   * バイナリデータからShift-JISテキストを抽出する
   * 制御コードを処理し、テキスト部分のみを返す
   */
  private def extractSjisText(
      data: Array[Byte],
      start: Int,
      size: Int
  ): Array[Byte] =
    val output = new java.io.ByteArrayOutputStream(size)
    var pos = 0
    val end = math.min(size, data.length - start)

    while pos < end do
      val byteVal = data(start + pos) & 0xFF

      byteVal match
        case 0xFE =>
          // 改行プレフィクス
          if pos + 1 < end then
            val nextByte = data(start + pos + 1) & 0xFF
            if isNewlineCode(nextByte) then
              output.write('\r')
              output.write('\n')
          pos += 2

        case 0x1E =>
          // 1バイトスキップ
          pos += 1

        case 0x1F =>
          // 可変長スキップ (0x1F + タイプ + スキップ長 の最低3バイト)
          if pos + 2 < end then
            val skipLen = data(start + pos + 2) & 0xFF
            // 制御シーケンス自体が3バイトなので、最低3バイト進める
            pos += math.max(3, skipLen)
          else
            pos += 1

        case 0x1C =>
          // 書式制御 (3バイトスキップ)
          pos += 3

        case 0x11 | 0x12 =>
          // 書式制御 (3バイトスキップ)
          pos += 3

        case 0xFD =>
          // 罫線文字
          if pos + 1 < end then
            val keisenCode = data(start + pos + 1) & 0xFF
            val sjis = KeisenTable.getOrElse(keisenCode, KeisenDefault)
            output.write(sjis)
            pos += 2
          else
            pos += 1

        case b if isSjisLeadByte(b) =>
          // Shift-JISマルチバイト文字の第1バイト
          if pos + 1 < end then
            output.write(b)
            output.write(data(start + pos + 1) & 0xFF)
            pos += 2
          else
            pos += 1

        case b if isPrintableByte(b) =>
          // 表示可能な1バイト文字
          output.write(b)
          pos += 1

        case 0x0D | 0x0A =>
          // CR/LF
          output.write(byteVal)
          pos += 1

        case _ =>
          // その他の制御コード: スキップ
          pos += 1

    output.toByteArray

  /** 0xFEに続くバイトが改行を示すかどうか判定 */
  private def isNewlineCode(byteVal: Int): Boolean =
    NewlineRanges.exists { case (low, high) => byteVal >= low && byteVal <= high }

  /** Shift-JISマルチバイト文字の第1バイトかどうか判定 */
  private def isSjisLeadByte(b: Int): Boolean =
    (b >= 0x81 && b <= 0x9F) || (b >= 0xE0 && b <= 0xFC)

  /** 表示可能なASCII/半角カナ文字かどうか判定 */
  private def isPrintableByte(b: Int): Boolean =
    (b >= 0x20 && b <= 0x7E) || (b >= 0xA1 && b <= 0xDF)

  /** リトルエンディアンで4バイト整数を読み取る */
  private def readU32LE(data: Array[Byte], offset: Int): Int =
    if offset + 4 > data.length then 0
    else
      (data(offset) & 0xFF) |
      ((data(offset + 1) & 0xFF) << 8) |
      ((data(offset + 2) & 0xFF) << 16) |
      ((data(offset + 3) & 0xFF) << 24)

  /** リトルエンディアンで2バイト整数を読み取る */
  private def readU16LE(data: Array[Byte], offset: Int): Int =
    if offset + 2 > data.length then 0
    else
      (data(offset) & 0xFF) | ((data(offset + 1) & 0xFF) << 8)
