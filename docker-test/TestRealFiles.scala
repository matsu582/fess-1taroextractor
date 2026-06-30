package jp.co.nttdata_ccs.fess.extractor

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}

/**
 * 実ファイルでのOLE2パーサ動作確認用
 */
object TestRealFiles:
  def main(args: Array[String]): Unit =
    val files = Seq(
      "/documents/28246.jtd",
      "/documents/28253.jtd",
      "/documents/28254.jtd"
    ).filter(f => Files.exists(Path.of(f)))

    // ローカルテスト用フォールバック
    val localFiles = if files.isEmpty then
      Seq(
        "/home/ubuntu/attachments/44b51d2e-3578-47a1-ae09-3407d42bd3ae/28246.jtd",
        "/home/ubuntu/attachments/bdff7548-3802-49e3-94e3-871e0b345709/28253.jtd",
        "/home/ubuntu/attachments/4b6439bb-bb41-4a90-82a1-6dfe9df3e739/28254.jtd"
      ).filter(f => Files.exists(Path.of(f)))
    else files

    val extractor = new OnetaroExtractor()

    localFiles.foreach { f =>
      println(s"\n=== ${Path.of(f).getFileName} ===")
      try
        val data = Files.readAllBytes(Path.of(f))
        val is = new ByteArrayInputStream(data)
        val result = extractor.getText(is, java.util.Collections.emptyMap())
        val content = result.getContent
        println(s"抽出文字数: ${content.length}")
        println(content.take(500))
        println("...")
      catch
        case e: Exception =>
          println(s"エラー: ${e.getMessage}")
          e.printStackTrace()
    }
