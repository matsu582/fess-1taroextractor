package com.github.matsu582.fess.extractor

import java.io.{ByteArrayInputStream, File, FileInputStream}

/**
 * 動作確認用テストプログラム
 * 引数にディレクトリまたはファイルパスを指定して実行
 */
object TestMain:

  def main(args: Array[String]): Unit =
    val targetDir = if args.nonEmpty then args(0) else "/documents"
    val dir = new File(targetDir)

    if !dir.exists() then
      println(s"ERROR: $targetDir が見つかりません")
      System.exit(1)

    val extractor = new OnetaroExtractor()
    val extensions = Set(".jtd", ".jtt", ".jsw", ".jaw", ".jtw", ".jbw", ".juw", ".jfw", ".jvw")

    val files = if dir.isDirectory then
      dir.listFiles().filter(f => extensions.exists(e => f.getName.toLowerCase.endsWith(e)))
    else
      Array(dir)

    if files == null || files.isEmpty then
      println("ERROR: テスト対象ファイルが見つかりません")
      System.exit(1)

    var success = 0
    var failed = 0

    files.foreach { f =>
      println(s"\n=== ${f.getName} ===")
      try
        val is = new FileInputStream(f)
        try
          val result = extractor.getText(is, java.util.Collections.emptyMap())
          val content = result.getContent
          if content != null && content.nonEmpty then
            println(s"  抽出成功: ${content.length} 文字")
            println(s"  先頭100文字: ${content.take(100)}")
            success += 1
          else
            println("  ERROR: テキスト抽出結果が空")
            failed += 1
        finally
          is.close()
      catch
        case e: Exception =>
          println(s"  ERROR: ${e.getMessage}")
          failed += 1
    }

    println(s"\n=== 結果 ===")
    println(s"成功: $success, 失敗: $failed")
    System.exit(if failed > 0 then 1 else 0)
