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

  testFiles.foreach { (name, path) =>
    s"OnetaroExtractor" should s"$name からテキストを抽出できる" in {
      val data = Files.readAllBytes(path)
      val is = new ByteArrayInputStream(data)
      val result = extractor.getText(is, java.util.Collections.emptyMap())
      val content = result.getContent
      println(s"\n=== $name === (${content.length} chars)")
      println(content.take(300))
      content should not be empty
    }
  }
