package com.devonfw.tools.ide.tool.ide;

import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import com.devonfw.tools.ide.cli.CliArguments;
import com.devonfw.tools.ide.cli.CliException;
import com.devonfw.tools.ide.context.AbstractIdeContextTest;
import com.devonfw.tools.ide.context.IdeTestContext;
import com.devonfw.tools.ide.context.ProcessContextTestImpl;
import com.devonfw.tools.ide.os.SystemInfoMock;
import com.devonfw.tools.ide.process.ProcessMode;
import com.devonfw.tools.ide.tool.intellij.Intellij;
import com.devonfw.tools.ide.tool.vscode.Vscode;

/**
 * Test of the {@code --project} flag and the {@link IdeToolCommandlet#getOpenPath() open path} foundation of
 * {@link IdeToolCommandlet}.
 */
class IdeToolCommandletProjectTest extends AbstractIdeContextTest {

  /**
   * Tests that {@link IdeToolCommandlet#getOpenPath()} returns an explicitly configured absolute {@code --project} path.
   */
  @Test
  void testGetOpenPathAbsoluteProject() {

    // arrange
    IdeTestContext context = newContext("intellij");
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    Path external = Path.of("/external/some-project");
    intellij.project.setValue(external);
    // act
    Path openPath = intellij.getOpenPath();
    // assert
    assertThat(openPath).isEqualTo(external);
    assertThat(openPath).isNotEqualTo(context.getWorkspacePath());
  }

  /**
   * Tests that a relative {@code --project} path is resolved against the current working directory.
   */
  @Test
  void testGetOpenPathRelativeProject() {

    // arrange
    IdeTestContext context = newContext("intellij");
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    Path cwd = context.getCwd();
    Path expected = cwd.resolve("some-project").normalize();
    intellij.project.setValue(Path.of("some-project"));
    // act
    Path openPath = intellij.getOpenPath();
    // assert
    assertThat(openPath).isEqualTo(expected);
  }

  /**
   * Tests that without a {@code --project} flag {@link IdeToolCommandlet#getOpenPath()} falls back to the
   * {@link com.devonfw.tools.ide.context.IdeContext#getWorkspacePath() workspace path} (regression guard).
   */
  @Test
  void testGetOpenPathWithoutProjectIsWorkspacePath() {

    // arrange
    IdeTestContext context = newContext("intellij");
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    // act
    Path openPath = intellij.getOpenPath();
    // assert
    assertThat(openPath).isEqualTo(context.getWorkspacePath());
  }

  /**
   * Tests that a {@code --project} path that does not exist results in a {@link CliException} so the IDE is not started.
   */
  @Test
  void testGetOpenPathMissingFolderThrows() {

    // arrange
    IdeTestContext context = newContext("intellij");
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    intellij.project.setValue(Path.of("does-not-exist-folder-12345"));
    // act & assert
    assertThatThrownBy(() -> intellij.runTool(ProcessMode.BACKGROUND, null, new ArrayList<>()))
        .isInstanceOf(CliException.class);
  }

  /**
   * Tests that a {@code --project} path pointing to a file (not a folder) results in a {@link CliException}.
   */
  @Test
  void testGetOpenPathNotAFolderThrows() {

    // arrange
    IdeTestContext context = newContext("intellij");
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    Path file = context.getWorkspacePath().resolve("a-file.txt");
    context.getFileAccess().writeFileContent("content", file);
    intellij.project.setValue(file);
    // act & assert
    assertThatThrownBy(() -> intellij.runTool(ProcessMode.BACKGROUND, null, new ArrayList<>()))
        .isInstanceOf(CliException.class);
  }

  /**
   * Tests that the {@code --project} flag is consumed by IDEasy and not passed through to the IDE binary. The flag arrives in the multi-valued passthrough
   * {@code args} of the tool commandlet, so IDEasy extracts and remembers it instead of forwarding it to the IDE. As of {@code #2492} the IntelliJ launch
   * arguments also point at the selected folder ({@link IdeToolCommandlet#getOpenPath()}) instead of the managed workspace.
   */
  @Test
  void testIntellijRunWithProjectFlagConsumedByIdeasy() {

    // arrange
    IdeTestContext context = newContext("intellij");
    context.setSystemInfo(SystemInfoMock.of("linux"));
    Path external = context.getIdeHome().resolve("external-project").normalize();
    context.getFileAccess().mkdirs(external);
    // act
    CliArguments args = new CliArguments("intellij", "--project", external.toString());
    args.next();
    int exitCode = context.run(args);
    // assert
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    assertThat(exitCode).isEqualTo(0);
    // IDEasy consumed the flag and remembered the folder
    assertThat(intellij.project.getValue()).isEqualTo(external);
    assertThat(intellij.getOpenPath()).isEqualTo(external);
    // the --project flag token was stripped from the arguments passed to the IDE binary, and the IDE launch now targets the selected folder (not the managed
    // workspace)
    String launchedArgs = context.getFileAccess().readFileContent(intellij.getToolBinPath().resolve("intellijtest")).trim();
    assertThat(launchedArgs).doesNotContain("--project");
    assertThat(launchedArgs).contains(external.toString());
    assertThat(launchedArgs).doesNotContain(context.getWorkspacePath().toString());
  }

  /**
   * Tests that the {@code --project} flag is consumed by IDEasy for VS Code too. The shared {@link IdeToolCommandlet#runTool(List)}
   * path extracts the flag (proven end-to-end by {@link #testIntellijRunWithProjectFlagConsumedByIdeasy()}); this verifies the VS Code CLI path
   * remembers the folder via {@link IdeToolCommandlet#getOpenPath()}.
   */
  @Test
  void testVscodeRunWithProjectFlagConsumedByIdeasy() {

    // arrange
    IdeTestContext context = newContext("vscode");
    context.setSystemInfo(SystemInfoMock.of("linux"));
    Path external = context.getIdeHome().resolve("external-project").normalize();
    context.getFileAccess().mkdirs(external);
    // act
    CliArguments args = new CliArguments("vscode", "--project", external.toString());
    args.next();
    int exitCode = context.run(args);
    // assert
    Vscode vscode = context.getCommandletManager().getCommandlet(Vscode.class);
    assertThat(exitCode).isEqualTo(0);
    // IDEasy consumed the flag through the VS Code CLI path and remembered the folder
    assertThat(vscode.project.getValue()).isEqualTo(external);
    assertThat(vscode.getOpenPath()).isEqualTo(external);
  }

  /**
   * Tests that an explicit {@code --project} launch skips the workspace configuration entirely (template merge <em>and</em> extra-SDK sync), i.e. the
   * {@code "Configuring workspace …"} step does not run, so no settings files are written into the managed workspace for an external launch path.
   */
  @Test
  void testExternalLaunchSkipsWorkspaceConfiguration() {

    // arrange
    IdeTestContext context = newContext("intellij");
    context.setSystemInfo(SystemInfoMock.of("linux"));
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    intellij.install(); // make the IDE binary available so the launch path under test can run
    Path external = context.getIdeHome().resolve("external-project").normalize();
    context.getFileAccess().mkdirs(external);
    intellij.project.setValue(external); // simulate an explicit --project folder
    context.getTestStartContext().getEntries().clear(); // ignore the merge that happened during install
    // act - invoke the launch seam directly (the same 3-arg runTool the CLI launch path lands on) to isolate it from the
    // ensure-install phase, whose own postInstall merge is intentionally left unchanged for external launches
    intellij.runTool(new ProcessContextTestImpl(context), ProcessMode.BACKGROUND, new ArrayList<>());
    // assert
    // the workspace configuration (merge + extra-SDK sync) must be skipped for an explicit external launch path
    assertThat(context).log().hasNoMessageContaining("Configuring workspace");
  }

  /**
   * Tests that without a {@code --project} flag the launch still runs the workspace configuration (regression guard): the {@code "Configuring workspace …"}
   * step is logged as today.
   */
  @Test
  void testDefaultLaunchRunsWorkspaceConfiguration() {

    // arrange
    IdeTestContext context = newContext("intellij");
    context.setSystemInfo(SystemInfoMock.of("linux"));
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    intellij.install(); // make the IDE binary available so the launch path under test can run
    context.getTestStartContext().getEntries().clear(); // ignore the merge that happened during install
    // act - invoke the launch seam directly without a --project folder (bypassing the ensure-install phase)
    intellij.runTool(new ProcessContextTestImpl(context), ProcessMode.BACKGROUND, new ArrayList<>());
    // assert
    assertThat(context).log().hasMessageContaining("Configuring workspace");
  }

  /**
   * Tests the full CLI launch path ({@code ide intellij --project <path>}) with an already-installed IDE: the workspace configuration must not run, i.e. no
   * {@code "Configuring workspace …"} step. This covers the {@code postInstall} call site that is reached by the launch's ensure-install step
   * ({@code toolAlreadyInstalled -> postInstall -> configureWorkspace}), in addition to the {@link #runTool} launch seam.
   */
  @Test
  void testExternalLaunchViaCliSkipsWorkspaceConfiguration() {

    // arrange
    IdeTestContext context = newContext("intellij");
    context.setSystemInfo(SystemInfoMock.of("linux"));
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    intellij.install(); // pre-install so the launch takes the ensure-install (toolAlreadyInstalled -> postInstall) path
    Path external = context.getIdeHome().resolve("external-project").normalize();
    context.getFileAccess().mkdirs(external);
    context.getTestStartContext().getEntries().clear(); // ignore the merge that happened during install
    // act - a real CLI launch exactly like `ide intellij --project <path>`
    int exitCode = context.run(new CliArguments("intellij", "--project", external.toString()));
    // assert
    assertThat(exitCode).isEqualTo(0);
    assertThat(intellij.getOpenPath()).isEqualTo(external);
    // the managed workspace must not be configured for an external launch (covers both the runTool seam and the postInstall call site)
    assertThat(context).log().hasNoMessageContaining("Configuring workspace");
  }

  /**
   * Tests that {@code ide install} still configures (merges) the workspace, i.e. the external-launch skip does not leak into the install /
   * {@code postInstall} path which must keep merging into the managed workspace as today.
   */
  @Test
  void testInstallStillConfiguresWorkspace() {

    // arrange
    IdeTestContext context = newContext("intellij");
    context.setSystemInfo(SystemInfoMock.of("linux"));
    Intellij intellij = context.getCommandletManager().getCommandlet(Intellij.class);
    // act
    intellij.install();
    // assert
    assertThat(context).log().hasMessageContaining("Configuring workspace");
  }
}
