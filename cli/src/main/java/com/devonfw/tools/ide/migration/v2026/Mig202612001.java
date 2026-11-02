package com.devonfw.tools.ide.migration.v2026;

import java.nio.file.Path;

import com.devonfw.tools.ide.context.IdeContext;
import com.devonfw.tools.ide.migration.IdeVersionMigration;

/**
 * Migration to 2026.12.001. Moves the old virtualenv-based python installation out of the software folder into the backups so that the next
 * {@code ide update} rebuilds {@code software/python} as a uv-managed interpreter, see
 * <a href="https://github.com/devonfw/IDEasy/issues/2784">issue 2784</a>.
 */
public class Mig202612001 extends IdeVersionMigration {

  /**
   * The constructor.
   */
  public Mig202612001() {

    super("2026.12.001");
  }

  @Override
  public void run(IdeContext context) {

    Path softwarePath = context.getSoftwarePath();
    if (softwarePath != null) {
      context.getFileAccess().backup(softwarePath.resolve("python"));
    }
  }
}
