package com.crochetpatternguide.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** Runs on the JVM without Android or additional test dependencies. */
public final class SharedPdfCacheTest {
  public static void main(String[] args) throws Exception {
    File root = Files.createTempDirectory("crochet-cache-test-").toFile();
    try {
      long now = System.currentTimeMillis();
      File directory = new File(root, "shared");
      File previous = null;
      File first = null;
      for (int i = 0; i < 20; i++) {
        File file = SharedPdfCache.prepare(directory, "same-name.pdf", now + i);
        Files.write(file.toPath(), new byte[] { (byte) i });
        check(file.getParentFile().setLastModified(now + i), "set transfer timestamp");
        check(directory.list().length <= 3, "cache exceeds three transfers");
        if (previous != null) {
          check(!file.equals(previous), "duplicate URI path");
          check(Files.readAllBytes(previous.toPath())[0] == i - 1,
              "previous attachment was removed or overwritten");
        }
        if (first == null) first = file;
        previous = file;
      }
      check(!first.exists(), "oldest attachment was not evicted");
      check(directory.list().length == 3, "should retain the three latest transfers");
      System.out.println("PASS: repeated transfers stay bounded and preserve recent attachments");

      // Both legacy flat files and transfer directories must expire by age.
      File legacy = new File(directory, "legacy.pdf");
      Files.write(legacy.toPath(), new byte[] { 1 });
      check(legacy.setLastModified(now - TimeUnit.DAYS.toMillis(8)), "set legacy timestamp");
      File newest = SharedPdfCache.prepare(directory, "fresh.pdf", now + TimeUnit.DAYS.toMillis(8));
      Files.write(newest.toPath(), new byte[] { 2 });
      check(directory.list().length == 1 && !legacy.exists() && !previous.exists(),
          "expired files or directories survived cleanup");
      System.out.println("PASS: seven-day expiry also removes legacy files");

      File blocked = new File(root, "not-a-directory");
      Files.write(blocked.toPath(), new byte[] { 1 });
      try {
        SharedPdfCache.prepare(blocked, "pattern.pdf", now);
        throw new AssertionError("invalid cache directory should fail");
      } catch (IOException expected) {
        System.out.println("PASS: invalid cache directory fails without preparing a transfer");
      }
    } finally {
      delete(root);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void delete(File entry) throws IOException {
    File[] children = entry.listFiles();
    if (children != null) for (File child : children) delete(child);
    Files.delete(entry.toPath());
  }
}
