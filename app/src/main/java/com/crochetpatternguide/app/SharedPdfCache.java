package com.crochetpatternguide.app;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Bounded, temporary storage for attachments handed to other apps. */
final class SharedPdfCache {
  private static final int MAX_TRANSFERS = 3;
  private static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(7);

  private SharedPdfCache() {}

  static File prepare(File directory, String fileName, long now) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IOException("Could not create attachment cache");
    }
    File[] entries = directory.listFiles();
    if (entries == null) throw new IOException("Could not read attachment cache");
    Arrays.sort(entries, Comparator.comparingLong(File::lastModified).reversed()
        .thenComparing(File::getName));
    int retained = 0;
    for (File entry : entries) {
      // Reserve one of the three slots for the transfer about to start.
      if (entry.lastModified() < now - MAX_AGE_MS || retained >= MAX_TRANSFERS - 1) {
        delete(entry);
      } else {
        retained++;
      }
    }
    File transfer = new File(directory, UUID.randomUUID().toString());
    if (!transfer.mkdir()) throw new IOException("Could not create attachment directory");
    return new File(transfer, fileName);
  }

  private static void delete(File entry) throws IOException {
    if (entry.isDirectory()) {
      File[] children = entry.listFiles();
      if (children == null) throw new IOException("Could not read attachment directory");
      for (File child : children) delete(child);
    }
    if (!entry.delete()) throw new IOException("Could not remove old attachment");
  }
}
