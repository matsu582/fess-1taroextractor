package com.github.matsu582.fess.extractor.onetaro

import org.codelibs.fess.crawler.exception.ExtractException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream

/**
 * OnetaroExtractor のユニットテスト
 */
class OnetaroExtractorSpec extends AnyFlatSpec with Matchers:

  private val extractor = new OnetaroExtractor()

  // テスト用のDOC\x00ヘッダ付きバイナリ生成
  private def createLegacyData(text: String): Array[Byte] =
    val sjisBytes = text.getBytes("MS932")
    val data = new Array[Byte](0x804 + sjisBytes.length + 100)
    // DOC\x00 シグネチャ
    data(0) = 0x44.toByte
    data(1) = 0x4F.toByte
    data(2) = 0x43.toByte
    data(3) = 0x00.toByte
    // 検証値
    data(0x3C) = 0x19.toByte
    data(0x3D) = 0x89.toByte
    data(0x3E) = 0x02.toByte
    data(0x3F) = 0x22.toByte
    // テキストサイズ
    data(0x800) = (sjisBytes.length & 0xFF).toByte
    data(0x801) = ((sjisBytes.length >> 8) & 0xFF).toByte
    data(0x802) = 0x00.toByte
    data(0x803) = 0x00.toByte
    // テキストデータ
    System.arraycopy(sjisBytes, 0, data, 0x804, sjisBytes.length)
    data

  "getText" should "旧一太郎形式からテキストを抽出する" in {
    val data = createLegacyData("Hello World")
    val is = new ByteArrayInputStream(data)
    val result = extractor.getText(is, java.util.Collections.emptyMap())
    result.getContent shouldBe "Hello World"
  }

  it should "nullストリームでExtractExceptionをスローする" in {
    an[ExtractException] should be thrownBy {
      extractor.getText(null, java.util.Collections.emptyMap())
    }
  }

  it should "不正なデータでExtractExceptionをスローする" in {
    val data = Array[Byte](0x00, 0x01, 0x02, 0x03, 0x04)
    val is = new ByteArrayInputStream(data)
    an[ExtractException] should be thrownBy {
      extractor.getText(is, java.util.Collections.emptyMap())
    }
  }
