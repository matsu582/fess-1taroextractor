package com.github.matsu582.fess.extractor

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}

/**
 * 実ファイルでの統合テスト
 */
class RealFileIntegrationTest extends AnyFlatSpec with Matchers:

  private val testFiles = Seq(
    "28246.jtd" -> "/home/ubuntu/attachments/44b51d2e-3578-47a1-ae09-3407d42bd3ae/28246.jtd",
    "28253.jtd" -> "/home/ubuntu/attachments/bdff7548-3802-49e3-94e3-871e0b345709/28253.jtd",
    "28254.jtd" -> "/home/ubuntu/attachments/4b6439bb-bb41-4a90-82a1-6dfe9df3e739/28254.jtd"
  ).filter((_, p) => Files.exists(Path.of(p)))

  private val extractor = new OnetaroExtractor()

  testFiles.foreach { (name, path) =>
    s"OnetaroExtractor" should s"$name からテキストを抽出できる" in {
      val data = Files.readAllBytes(Path.of(path))
      val is = new ByteArrayInputStream(data)
      val result = extractor.getText(is, java.util.Collections.emptyMap())
      val content = result.getContent
      println(s"\n=== $name === (${content.length} chars)")
      println(content.take(300))
      content should not be empty
    }
  }
