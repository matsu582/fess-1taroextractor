package com.github.matsu582.fess.extractor.onetaro

import org.junit.jupiter.api.{Test, Assumptions, BeforeAll, AfterAll, TestInstance}
import org.junit.jupiter.api.Assertions._
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path}
import java.time.Duration

/**
 * Testcontainersを使用したDocker統合テスト
 * Fess環境にOnetaroExtractor JARを配置し、実ファイルのテキスト抽出を検証する
 *
 * 前提条件:
 * - testfiles/ にテスト用一太郎ファイルが配置されていること
 * - build/libs/fess-1taroextractor-1.0.0-docker-test.jar が存在すること (./gradlew dockerTestJar)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DockerIntegrationTest:

  private val testFilesDir = Path.of("testfiles")
  private val dockerTestJarPath = Path.of("build/libs/fess-1taroextractor-1.0.0-docker-test.jar")

  private var container: GenericContainer[?] = _

  @BeforeAll
  def setUp(): Unit =
    Assumptions.assumeTrue(
      Files.exists(dockerTestJarPath),
      "dockerTestJar が存在しません。先に ./gradlew dockerTestJar を実行してください"
    )
    Assumptions.assumeTrue(
      Files.exists(testFilesDir) && Files.list(testFilesDir).findAny().isPresent,
      "testfiles/ にテストファイルが配置されていません"
    )

    val image = new ImageFromDockerfile()
      .withDockerfileFromBuilder { builder =>
        builder
          .from("ghcr.io/codelibs/fess:15.7.0")
          .user("root")
          .copy("lib.jar", "/usr/share/fess/app/WEB-INF/lib/fess-1taroextractor-docker-test.jar")
          .copy("testfiles", "/testfiles")
          .entryPoint("tail", "-f", "/dev/null")
          .build()
      }
      .withFileFromPath("lib.jar", dockerTestJarPath)
      .withFileFromPath("testfiles", testFilesDir)

    container = new GenericContainer(image)
    container.withStartupTimeout(Duration.ofSeconds(120))
    container.start()

  @AfterAll
  def tearDown(): Unit =
    if container != null then container.stop()

  private def runExtractor(targetPath: String): String =
    val cmd = Array(
      "sh", "-c",
      s"FESS_LIB=/usr/share/fess/app/WEB-INF/lib && " +
      s"CP=$$(find $$FESS_LIB -name '*.jar' | tr '\\n' ':') && " +
      s"java -cp $$CP com.github.matsu582.fess.extractor.onetaro.TestMain $targetPath"
    )
    val result = container.execInContainer(cmd: _*)
    val stdout = result.getStdout
    val stderr = result.getStderr
    if stderr != null && stderr.nonEmpty then
      println(s"STDERR: $stderr")
    stdout

  @Test
  def testExtractOle2Basic(): Unit =
    val output = runExtractor("/testfiles/test_ole2_basic.jtd")
    assertTrue(output.contains("抽出成功"), s"test_ole2_basic.jtd の抽出に失敗:\n$output")
    assertTrue(output.contains("テスト文書"), s"test_ole2_basic.jtd の内容が不正:\n$output")

  @Test
  def testExtractOle2Multiline(): Unit =
    val output = runExtractor("/testfiles/test_ole2_multiline.jtd")
    assertTrue(output.contains("抽出成功"), s"test_ole2_multiline.jtd の抽出に失敗:\n$output")
    assertTrue(output.contains("文書のタイトル"), s"test_ole2_multiline.jtd の内容が不正:\n$output")

  @Test
  def testExtractLegacyBasic(): Unit =
    val output = runExtractor("/testfiles/test_legacy_basic.jsw")
    assertTrue(output.contains("抽出成功"), s"test_legacy_basic.jsw の抽出に失敗:\n$output")
    assertTrue(output.contains("テスト文書"), s"test_legacy_basic.jsw の内容が不正:\n$output")

  @Test
  def testExtractAllFiles(): Unit =
    val output = runExtractor("/testfiles")
    assertTrue(output.contains("成功: 6"), s"全ファイルの抽出結果が不正:\n$output")
    assertTrue(output.contains("失敗: 0"), s"一部ファイルの抽出に失敗:\n$output")
