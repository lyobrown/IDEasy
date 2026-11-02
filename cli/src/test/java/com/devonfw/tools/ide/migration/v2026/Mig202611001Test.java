package com.devonfw.tools.ide.migration.v2026;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.devonfw.tools.ide.context.AbstractIdeContextTest;
import com.devonfw.tools.ide.context.IdeContext;
import com.devonfw.tools.ide.context.IdeTestContext;
import com.devonfw.tools.ide.io.FileAccess;

/**
 * Test of {@link Mig202611001}.
 */
class Mig202611001Test extends AbstractIdeContextTest {

  /**
   * Test that an existing (virtualenv-based) python installation is moved into the backups folder so that the next {@code ide update} rebuilds
   * {@code software/python} as a uv-managed interpreter, see <a href="https://github.com/devonfw/IDEasy/issues/2701">issue 2701</a>.
   */
  @Test
  void testBacksUpExistingPythonInstallation() throws IOException {

    // arrange
    IdeTestContext context = newContext("migration");
    FileAccess fileAccess = context.getFileAccess();
    Path python = context.getSoftwarePath().resolve("python");
    fileAccess.mkdirs(python.resolve("bin"));
    fileAccess.writeFileContent("version = 3.12", python.resolve("pyvenv.cfg"));

    // act
    new Mig202611001().run(context);

    // assert - the installation is gone from the software folder and its content (e.g. user-installed packages) is preserved in the backups
    assertThat(python).doesNotExist();
    assertThat(context).logAtInfo().hasMessageContaining("Creating backup by moving");
    Path backups = context.getIdeHome().resolve(IdeContext.FOLDER_BACKUPS);
    try (Stream<Path> found = Files.walk(backups).filter(path -> path.getFileName().toString().equals("pyvenv.cfg"))) {
      List<Path> movedFiles = found.collect(Collectors.toList());
      assertThat(movedFiles).hasSize(1);
      assertThat(Files.readString(movedFiles.get(0))).isEqualTo("version = 3.12");
    }
  }

  /**
   * Test that the migration is a no-op (and does not fail) when no python installation exists.
   */
  @Test
  void testDoesNothingWhenNoPythonInstallation() {

    // arrange
    IdeTestContext context = newContext("migration");

    // act
    new Mig202611001().run(context);

    // assert - nothing is moved and the software folder (if present) is left untouched
    assertThat(context.getIdeHome().resolve(IdeContext.FOLDER_BACKUPS)).doesNotExist();
  }
}
