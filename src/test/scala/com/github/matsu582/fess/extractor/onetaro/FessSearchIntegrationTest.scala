package com.github.matsu582.fess.extractor.onetaro

import org.junit.jupiter.api.{Test, Tag, Assumptions, BeforeAll, AfterAll, TestInstance}
import org.junit.jupiter.api.Assertions._
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Duration

/**
 * Fess検索統合テスト（Testcontainers）
 * Fess + OpenSearch環境を構築し、一太郎ファイルのクロール→検索を検証する
 *
 * このテストは完全なFess環境を必要とするため実行時間が長い（約5分）
 * 通常のテストからは除外し、明示的に実行する:
 *   ./gradlew test --tests "*FessSearchIntegrationTest" -Dinclude.tags=fess-search
 *
 * 前提条件:
 * - testfiles/ にテスト用一太郎ファイルが配置されていること
 * - build/libs/fess-1taroextractor-1.0.0-all.jar が存在すること (./gradlew fatJar)
 * - docker-test/extractor.xml, custom-mimetypes.xml が存在すること
 *
 * 手動検証: docker-fess-search/docker-compose.yml で同等の環境構築が可能
 */
@Tag("fess-search")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FessSearchIntegrationTest:

  private val testFilesDir = Path.of("testfiles")
  private val fatJarPath = Path.of("build/libs/fess-1taroextractor-1.0.0-all.jar")
  private val extractorXml = Path.of("docker-test/extractor.xml")
  private val mimetypesXml = Path.of("docker-test/custom-mimetypes.xml")

  private val httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  private var network: Network = _
  private var opensearch: GenericContainer[?] = _
  private var fess: GenericContainer[?] = _

  @BeforeAll
  def setUp(): Unit =
    Assumptions.assumeTrue(
      Files.exists(fatJarPath),
      "fatJar が存在しません。先に ./gradlew fatJar を実行してください"
    )
    Assumptions.assumeTrue(
      Files.exists(testFilesDir) && Files.list(testFilesDir).findAny().isPresent,
      "testfiles/ にテストファイルが配置されていません"
    )
    Assumptions.assumeTrue(
      Files.exists(extractorXml) && Files.exists(mimetypesXml),
      "docker-test/extractor.xml または custom-mimetypes.xml が存在しません"
    )

    network = Network.newNetwork()

    // OpenSearch コンテナ
    opensearch = new GenericContainer(
      DockerImageName.parse("ghcr.io/codelibs/fess-opensearch:2.17.1")
    )
    opensearch.withNetwork(network)
    opensearch.withNetworkAliases("opensearch")
    opensearch.withExposedPorts(Integer.valueOf(9200))
    opensearch.withEnv("discovery.type", "single-node")
    opensearch.withEnv("DISABLE_SECURITY_PLUGIN", "true")
    opensearch.withEnv("FESS_DICTIONARY_PATH", "/usr/share/opensearch/config/dictionary/")
    opensearch.withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
    opensearch.waitingFor(
      Wait.forHttp("/_cluster/health")
        .forPort(9200)
        .forStatusCode(200)
        .withStartupTimeout(Duration.ofSeconds(120))
    )
    opensearch.start()

    // Fess コンテナ（カスタムイメージ）
    val fessImage = new ImageFromDockerfile()
      .withDockerfileFromBuilder { builder =>
        builder
          .from("ghcr.io/codelibs/fess:15.7.0")
          .copy("lib.jar", "/usr/share/fess/app/WEB-INF/lib/fess-1taroextractor.jar")
          .copy("extractor.xml", "/usr/share/fess/app/WEB-INF/classes/crawler/extractor.xml")
          .copy("custom-mimetypes.xml", "/usr/share/fess/app/WEB-INF/classes/org/apache/tika/mime/custom-mimetypes.xml")
          .copy("testfiles", "/testfiles")
          .build()
      }
      .withFileFromPath("lib.jar", fatJarPath)
      .withFileFromPath("extractor.xml", extractorXml)
      .withFileFromPath("custom-mimetypes.xml", mimetypesXml)
      .withFileFromPath("testfiles", testFilesDir)

    fess = new GenericContainer(fessImage)
    fess.withNetwork(network)
    fess.withEnv("SEARCH_ENGINE_HTTP_URL", "http://opensearch:9200")
    fess.withEnv("FESS_DICTIONARY_PATH", "/usr/share/opensearch/config/dictionary/")
    fess.withExposedPorts(Integer.valueOf(8080))
    fess.waitingFor(
      Wait.forHttp("/")
        .forPort(8080)
        .forStatusCode(200)
        .withStartupTimeout(Duration.ofSeconds(300))
    )
    fess.start()

  @AfterAll
  def tearDown(): Unit =
    if fess != null then fess.stop()
    if opensearch != null then opensearch.stop()
    if network != null then network.close()

  private def fessUrl: String =
    val host = fess.getHost
    val port = fess.getMappedPort(8080)
    s"http://$host:$port"

  private def httpGet(url: String): String =
    val request = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofSeconds(30))
      .GET()
      .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    response.body()

  private def httpPost(url: String, body: String, contentType: String): String =
    val request = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", contentType)
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    response.body()

  /** トークン抽出用のsedパターン */
  private val tokenSed = """sed -n 's/.*TRANSACTION_TOKEN..value=.\([^"]*\).*/\1/p'"""

  /** Fess管理画面にログイン→クロール設定→クローラー起動 */
  private def loginAndSetupCrawl(): Unit =
    // Fessの初回ログインフロー: admin/admin → パスワード変更 → 再ログイン
    val setupScript = s"""
COOKIE=/tmp/fess_cookie
BASE=http://localhost:8080

# Step1: ログインページのトークン取得
TOKEN=$$(curl -sf -c $$COOKIE "$$BASE/login/" | $tokenSed)

# Step2: admin/adminでログイン → パスワード変更画面(HTTP200)が返る
CHANGE_PAGE=$$(curl -sf -c $$COOKIE -b $$COOKIE -X POST "$$BASE/login/" \\
  --data-urlencode "username=admin" \\
  --data-urlencode "password=admin" \\
  --data-urlencode "lastaflute.action.TRANSACTION_TOKEN=$$TOKEN" \\
  --data-urlencode "login=Login")

# Step3: パスワード変更トークン抽出
TOKEN2=$$(echo "$$CHANGE_PAGE" | $tokenSed)

# Step4: パスワード変更 → /login/へリダイレクト
curl -sf -c $$COOKIE -b $$COOKIE -o /dev/null -X POST "$$BASE/login/" \\
  --data-urlencode "password=FessAdmin1!" \\
  --data-urlencode "confirmPassword=FessAdmin1!" \\
  --data-urlencode "lastaflute.action.TRANSACTION_TOKEN=$$TOKEN2" \\
  --data-urlencode "changePassword=Update"

# Step5: 新パスワードで再ログイン → /admin/dashboard/へリダイレクト
TOKEN3=$$(curl -sf -c $$COOKIE -b $$COOKIE "$$BASE/login/" | $tokenSed)
curl -sf -c $$COOKIE -b $$COOKIE -L -o /dev/null -X POST "$$BASE/login/" \\
  --data-urlencode "username=admin" \\
  --data-urlencode "password=FessAdmin1!" \\
  --data-urlencode "lastaflute.action.TRANSACTION_TOKEN=$$TOKEN3" \\
  --data-urlencode "login=Login"

# Step6: ファイルクロール設定の作成
CONFIG_PAGE=$$(curl -sf -c $$COOKIE -b $$COOKIE "$$BASE/admin/fileconfig/createnew/")
TOKEN4=$$(echo "$$CONFIG_PAGE" | $tokenSed | head -1)
RESULT=$$(curl -sf -c $$COOKIE -b $$COOKIE -L -X POST "$$BASE/admin/fileconfig/createnew/" \\
  --data-urlencode "name=TestFiles" \\
  --data-urlencode "paths=file:/testfiles/" \\
  --data-urlencode "numOfThread=5" \\
  --data-urlencode "intervalTime=1000" \\
  --data-urlencode "boost=1.0" \\
  --data-urlencode "permissions={role}guest" \\
  --data-urlencode "available=true" \\
  --data-urlencode "lastaflute.action.TRANSACTION_TOKEN=$$TOKEN4" \\
  --data-urlencode "create=Create")
echo "$$RESULT" | grep -qi "fileconfig\\|list" && echo "CRAWL_CONFIG_OK" || echo "CRAWL_CONFIG_FAIL"

# Step7: Default Crawlerジョブを開始
SCHED_PAGE=$$(curl -sf -c $$COOKIE -b $$COOKIE "$$BASE/admin/scheduler/details/4/default_crawler")
TOKEN5=$$(echo "$$SCHED_PAGE" | $tokenSed | head -1)
RESULT2=$$(curl -sf -c $$COOKIE -b $$COOKIE -L -X POST "$$BASE/admin/scheduler/details/4/default_crawler" \\
  --data-urlencode "lastaflute.action.TRANSACTION_TOKEN=$$TOKEN5" \\
  --data-urlencode "start=Start Now")
echo "$$RESULT2" | grep -qi "scheduler\\|job" && echo "CRAWLER_STARTED" || echo "CRAWLER_START_FAIL"
"""

    val result = fess.execInContainer("bash", "-c", setupScript)
    val stdout = result.getStdout
    println(s"Setup output: $stdout")
    assertTrue(stdout.contains("CRAWL_CONFIG_OK"), s"クロール設定の作成に失敗:\n$stdout\nSTDERR: ${result.getStderr}")
    assertTrue(stdout.contains("CRAWLER_STARTED"), s"クローラーの起動に失敗:\n$stdout\nSTDERR: ${result.getStderr}")

  /** クローラーの完了を待機（最大180秒） */
  private def waitForCrawlCompletion(maxWaitSeconds: Int = 180): Unit =
    val startTime = System.currentTimeMillis()
    var completed = false
    while !completed && (System.currentTimeMillis() - startTime) < maxWaitSeconds * 1000L do
      Thread.sleep(10000)
      val checkResult = fess.execInContainer("bash", "-c",
        "grep 'Crawler execution completed' /var/log/fess/fess-crawler.log /var/log/fess/fess.log 2>/dev/null || echo 'NOT_DONE'")
      val output = checkResult.getStdout
      if !output.contains("NOT_DONE") then
        completed = true
        // インデックス反映待ち
        Thread.sleep(10000)
    assertTrue(completed, "クローラーが制限時間内に完了しませんでした")

  /** Fess検索APIを使って検索結果を取得 */
  private def searchFess(query: String): String =
    val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
    httpGet(s"$fessUrl/search/?q=$encodedQuery")

  @Test
  def testFessSearchIchitaro(): Unit =
    // クロール設定・実行
    loginAndSetupCrawl()
    waitForCrawlCompletion()

    // 「計画の目的」で検索 → 28246.jtd がヒットすること
    val result1 = searchFess("計画の目的")
    assertTrue(result1.contains("28246"), s"「計画の目的」検索で28246.jtdが見つかりません:\n${result1.take(500)}")

    // 「情報収集」で検索 → 28253.jtd がヒットすること
    val result2 = searchFess("情報収集")
    assertTrue(result2.contains("28253"), s"「情報収集」検索で28253.jtdが見つかりません:\n${result2.take(500)}")

    // 「入手情報」で検索 → 28254.jtd がヒットすること
    val result3 = searchFess("入手情報")
    assertTrue(result3.contains("28254"), s"「入手情報」検索で28254.jtdが見つかりません:\n${result3.take(500)}")
