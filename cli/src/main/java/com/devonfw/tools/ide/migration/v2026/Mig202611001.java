package com.devonfw.tools.ide.migration.v2026;

import java.nio.file.Path;

import com.devonfw.tools.ide.context.IdeContext;
import com.devonfw.tools.ide.migration.IdeVersionMigration;

/**
 * Migration to 2026.11.001. Moves the old virtualenv-based python installation out of the software folder into the backups so that the next
 * {@code ide update} rebuilds {@code software/python} as a uv-managed interpreter, see
 * <a href="https://github.com/devonfw/IDEasy/issues/2701">issue 2701</a>.
 */
public class Mig202611001 extends IdeVersionMigration {

  /**
   * The constructor.
   */
  public Mig202611001() {

    super("2026.11.001");
  }

  @Override
  public void run(IdeContext context) {

    Path softwarePath = context.getSoftwarePath();
    if (softwarePath != null) {
      context.getFileAccess().backup(softwarePath.resolve("python"));
    }
  }
}
