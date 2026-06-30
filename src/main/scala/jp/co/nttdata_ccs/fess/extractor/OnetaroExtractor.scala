package jp.co.nttdata_ccs.fess.extractor

import org.codelibs.fess.crawler.entity.ExtractData
import org.codelibs.fess.crawler.exception.ExtractException
import org.codelibs.fess.crawler.extractor.impl.AbstractExtractor

import java.io.{ByteArrayOutputStream, InputStream}
import java.util.{Map => JMap}

/**
 * 一太郎ファイル専用のFess Extractor
 *
 * 対応形式:
 * - ver4-6: 独自バイナリ形式 (DOC\x00シグネチャ、Shift-JISテキスト)
 * - ver7: OLE2 Compound Document形式 (DocumentTextストリーム)
 * - ver8以降: OLE2 Compound Document形式 (DocumentTextストリーム、UTF-16BE)
 */
class OnetaroExtractor extends AbstractExtractor:

  override def getText(
      inputStream: InputStream,
      params: JMap[String, String]
  ): ExtractData =
    if inputStream == null then
      throw new ExtractException("入力ストリームがnullです")

    try
      val data = readAllBytes(inputStream)
      val text = extractText(data)
      new ExtractData(text)
    catch
      case e: ExtractException => throw e
      case e: Exception =>
        throw new ExtractException("一太郎ファイルのテキスト抽出に失敗しました", e)

  /**
   * バイナリデータからテキストを抽出する
   * ファイル形式を自動判別し、適切なパーサを呼び出す
   */
  private def extractText(data: Array[Byte]): String =
    if data.length < 4 then
      throw new ExtractException("ファイルサイズが不正です")

    if OnetaroLegacyParser.isLegacyFormat(data) then
      // ver4-6: 独自バイナリ形式
      OnetaroLegacyParser.extractText(data)
    else if OnetaroOle2Parser.isOle2Format(data) then
      // ver7以降: OLE2形式
      OnetaroOle2Parser.extractText(data)
    else
      throw new ExtractException("一太郎ファイルとして認識できません")

  /**
   * InputStreamから全バイトを読み取る
   */
  private def readAllBytes(is: InputStream): Array[Byte] =
    val buffer = new ByteArrayOutputStream(8192)
    val tmp = new Array[Byte](4096)
    var len = is.read(tmp)
    while len >= 0 do
      buffer.write(tmp, 0, len)
      len = is.read(tmp)
    buffer.toByteArray
