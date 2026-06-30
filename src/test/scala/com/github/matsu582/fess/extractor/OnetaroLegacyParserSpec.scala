package com.github.matsu582.fess.extractor

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * OnetaroLegacyParser のユニットテスト
 */
class OnetaroLegacyParserSpec extends AnyFlatSpec with Matchers:

  // テスト用のDOC\x00ヘッダ + 検証値を持つ最小バイナリ生成
  private def createLegacyHeader(): Array[Byte] =
    val data = new Array[Byte](0x900)
    // DOC\x00 シグネチャ
    data(0) = 0x44.toByte  // D
    data(1) = 0x4F.toByte  // O
    data(2) = 0x43.toByte  // C
    data(3) = 0x00.toByte
    // 検証値 0x22028919 をオフセット0x3Cに格納 (LE形式で格納、BE読み取り)
    data(0x3C) = 0x19.toByte
    data(0x3D) = 0x89.toByte
    data(0x3E) = 0x02.toByte
    data(0x3F) = 0x22.toByte
    data

  "isLegacyFormat" should "DOC\\x00シグネチャと検証値を持つデータをtrueと判定する" in {
    val data = createLegacyHeader()
    OnetaroLegacyParser.isLegacyFormat(data) shouldBe true
  }

  it should "シグネチャが不正なデータをfalseと判定する" in {
    val data = createLegacyHeader()
    data(0) = 0x00.toByte  // シグネチャ破壊
    OnetaroLegacyParser.isLegacyFormat(data) shouldBe false
  }

  it should "検証値が不正なデータをfalseと判定する" in {
    val data = createLegacyHeader()
    data(0x3C) = 0x00.toByte  // 検証値破壊
    OnetaroLegacyParser.isLegacyFormat(data) shouldBe false
  }

  it should "データが短すぎる場合にfalseと判定する" in {
    val data = new Array[Byte](10)
    OnetaroLegacyParser.isLegacyFormat(data) shouldBe false
  }

  "extractText" should "ASCII文字を正しく抽出する" in {
    val data = createLegacyHeader()
    // テキストサイズをオフセット0x800に設定 (5バイト)
    data(0x800) = 0x05.toByte
    data(0x801) = 0x00.toByte
    data(0x802) = 0x00.toByte
    data(0x803) = 0x00.toByte
    // テキストデータをオフセット0x804に設定
    data(0x804) = 'H'.toByte
    data(0x805) = 'e'.toByte
    data(0x806) = 'l'.toByte
    data(0x807) = 'l'.toByte
    data(0x808) = 'o'.toByte

    val result = OnetaroLegacyParser.extractText(data)
    result shouldBe "Hello"
  }

  it should "0xFE改行コードを正しく処理する" in {
    val data = createLegacyHeader()
    // テキストサイズ
    data(0x800) = 0x06.toByte
    data(0x801) = 0x00.toByte
    data(0x802) = 0x00.toByte
    data(0x803) = 0x00.toByte
    // テキスト: A + 0xFE 0x41(改行) + B
    data(0x804) = 'A'.toByte
    data(0x805) = 0xFE.toByte
    data(0x806) = 0x41.toByte  // 改行
    data(0x807) = 'B'.toByte

    val result = OnetaroLegacyParser.extractText(data)
    result shouldBe "A\nB"
  }

  it should "0x1F可変長スキップを正しく処理する" in {
    val data = createLegacyHeader()
    // テキストサイズ
    data(0x800) = 0x08.toByte
    data(0x801) = 0x00.toByte
    data(0x802) = 0x00.toByte
    data(0x803) = 0x00.toByte
    // テキスト: A + 0x1F + XX + 0x05(スキップ量) + garbage + B
    data(0x804) = 'A'.toByte
    data(0x805) = 0x1F.toByte
    data(0x806) = 0x00.toByte
    data(0x807) = 0x05.toByte  // 5バイトスキップ
    // 0x805から5バイト進む → 0x80A
    data(0x80A) = 'B'.toByte

    val result = OnetaroLegacyParser.extractText(data)
    result should include("A")
    result should include("B")
  }

  it should "罫線文字(0xFD)を正しく変換する" in {
    val data = createLegacyHeader()
    // テキストサイズ
    data(0x800) = 0x04.toByte
    data(0x801) = 0x00.toByte
    data(0x802) = 0x00.toByte
    data(0x803) = 0x00.toByte
    // 0xFD 0x21 → ─ (横線)
    data(0x804) = 0xFD.toByte
    data(0x805) = 0x21.toByte
    data(0x806) = 'X'.toByte

    val result = OnetaroLegacyParser.extractText(data)
    result should include("─")
    result should include("X")
  }
