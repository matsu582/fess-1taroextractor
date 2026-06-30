import com.github.matsu582.fess.extractor.OnetaroExtractor;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class TestExtractor {
    public static void main(String[] args) throws Exception {
        OnetaroExtractor extractor = new OnetaroExtractor();

        File docDir = new File("/documents");
        if (!docDir.exists()) {
            System.out.println("ERROR: /documents が見つかりません");
            System.exit(1);
        }

        File[] files = docDir.listFiles((dir, name) ->
            name.endsWith(".jtd") || name.endsWith(".jsw") ||
            name.endsWith(".jaw") || name.endsWith(".jbw"));

        if (files == null || files.length == 0) {
            System.out.println("ERROR: テスト対象ファイルが見つかりません");
            System.exit(1);
        }

        int success = 0;
        int failed = 0;

        for (File f : files) {
            System.out.println("\n=== " + f.getName() + " ===");
            try (InputStream is = new FileInputStream(f)) {
                var result = extractor.getText(is, Collections.emptyMap());
                String content = result.getContent();
                if (content != null && !content.isEmpty()) {
                    System.out.println("  抽出成功: " + content.length() + " 文字");
                    System.out.println("  先頭100文字: " + content.substring(0, Math.min(100, content.length())));
                    success++;
                } else {
                    System.out.println("  ERROR: テキスト抽出結果が空");
                    failed++;
                }
            } catch (Exception e) {
                System.out.println("  ERROR: " + e.getMessage());
                failed++;
            }
        }

        System.out.println("\n=== 結果 ===");
        System.out.println("成功: " + success + ", 失敗: " + failed);
        System.exit(failed > 0 ? 1 : 0);
    }
}
