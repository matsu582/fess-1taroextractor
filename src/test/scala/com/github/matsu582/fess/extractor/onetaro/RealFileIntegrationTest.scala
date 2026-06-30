package com.github.matsu582.fess.extractor.onetaro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}

/**
 * 実ファイルでの統合テスト
 * テストファイルは /testfiles に配置
 */
class RealFileIntegrationTest extends AnyFlatSpec with Matchers:

  private val testFilesDir = Path.of("testfiles")

  private val testFiles = Seq(
    "test_ole2_basic.jtd",
    "test_ole2_multiline.jtd",
    "test_ole2_v7.jfw",
    "test_legacy_basic.jsw",
    "test_legacy_newline.jaw",
    "test_legacy_keisen.jbw"
  ).map(name => name -> testFilesDir.resolve(name))
   .filter((_, p) => Files.exists(p))

  private val extractor = new OnetaroExtractor()

  // テストファイルに埋め込まれた期待テキスト (部分一致)
  private val expectedSubstrings: Map[String, String] = Map(
    "test_ole2_basic.jtd" -> "一太郎OLE2形式のテスト文書です",
    "test_ole2_multiline.jtd" -> "文書のタイトル",
    "test_ole2_v7.jfw" -> "一太郎バージョン7形式のテストです",
    "test_legacy_basic.jsw" -> "テスト文書です",
    "test_legacy_newline.jaw" -> "見出し行",
    "test_legacy_keisen.jbw" -> "表の上"
  )

  testFiles.foreach { (name, path) =>
    s"OnetaroExtractor" should s"$name からテキストを抽出できる" in {
      val data = Files.readAllBytes(path)
      val is = new ByteArrayInputStream(data)
      val result = extractor.getText(is, java.util.Collections.emptyMap())
      val content = result.getContent
      println(s"\n=== $name === (${content.length} chars)")
      println(content.take(300))
      content should not be empty
      // 期待テキストが含まれることを確認 (文字化けしていないことの検証)
      expectedSubstrings.get(name).foreach { expected =>
        withClue(s"$name の抽出結果に「$expected」が含まれるべき: ") {
          content should include(expected)
        }
      }
    }
  }
