package com.github.matsu582.fess.extractor.onetaro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * OnetaroOle2Parser のユニットテスト
 */
class OnetaroOle2ParserSpec extends AnyFlatSpec with Matchers:

  "isOle2Format" should "OLE2シグネチャを持つデータをtrueと判定する" in {
    val data = Array[Byte](
      0xD0.toByte, 0xCF.toByte, 0x11.toByte, 0xE0.toByte,
      0xA1.toByte, 0xB1.toByte, 0x1A.toByte, 0xE1.toByte,
      0x00, 0x00, 0x00, 0x00
    )
    OnetaroOle2Parser.isOle2Format(data) shouldBe true
  }

  it should "OLE2シグネチャがないデータをfalseと判定する" in {
    val data = Array[Byte](0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    OnetaroOle2Parser.isOle2Format(data) shouldBe false
  }

  it should "データが短すぎる場合にfalseと判定する" in {
    val data = Array[Byte](0xD0.toByte, 0xCF.toByte, 0x11.toByte)
    OnetaroOle2Parser.isOle2Format(data) shouldBe false
  }
