package com.devonfw.tools.ide.tool.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.devonfw.tools.ide.context.AbstractIdeContextTest;
import com.devonfw.tools.ide.context.IdeContext;
import com.devonfw.tools.ide.context.IdeTestContext;
import com.devonfw.tools.ide.environment.EnvironmentVariablesType;
import com.devonfw.tools.ide.environment.VariableLine;
import com.devonfw.tools.ide.environment.VariableSource;
import com.devonfw.tools.ide.os.SystemInfoMock;
import com.devonfw.tools.ide.os.WindowsPathSyntax;
import com.devonfw.tools.ide.process.EnvironmentVariableCollectorContext;
import com.devonfw.tools.ide.tool.ToolInstallation;
import com.devonfw.tools.ide.version.VersionIdentifier;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * Test of {@link Python}.
 */
@WireMockTest
public class PythonTest extends AbstractIdeContextTest {

  private static final String PROJECT_UV = "uv";


  /**
   * Test that a stale uv staging folder left behind by a previously interrupted installation (e.g. if the process was killed during
   * {@code uv python install}) is removed before the next installation, see <a href="https://github.com/devonfw/IDEasy/issues/2313">#2313</a>.
   */
  @Test
  public void testInstallRemovesStaleUvStagingFolder(WireMockRuntimeInfo wireMockRuntimeInfo) {

    // arrange
    IdeTestContext context = newContext(PROJECT_UV, wireMockRuntimeInfo);
    context.setSystemInfo(SystemInfoMock.MAC_X64);
    Path staleMarker = context.getSoftwarePath().resolve(Python.FOLDER_UV_STAGING).resolve("stale.txt");
    context.getFileAccess().writeFileContent("stale", staleMarker, true);
    Python python = context.getCommandletManager().getCommandlet(Python.class);

    // act
    python.install();

    // assert
    assertThat(context.getSoftwarePath().resolve(Python.FOLDER_UV_STAGING)).doesNotExist();
    assertThat(context.getSoftwarePath().resolve("python").resolve("bin").resolve("python")).exists();
    assertThat(context).logAtSuccess().hasMessageContaining("Successfully installed python");
  }

  @Test
  public void testInstallOnIntelMacResolvesVersionFromUvNotIdeUrls(WireMockRuntimeInfo wireMockRuntimeInfo) {

    // arrange
    IdeTestContext context = newContext(PROJECT_UV, wireMockRuntimeInfo);
    context.setSystemInfo(SystemInfoMock.MAC_X64);
    Python python = context.getCommandletManager().getCommandlet(Python.class);

    // act
    python.install();

    // assert
    assertThat(context.getSoftwarePath().resolve("python").resolve(".ide.software.version")).hasContent("3.14.6");
    assertThat(context).logAtSuccess().hasMessageContaining("Successfully installed python");
  }


  /**
   * Test that a missing {@code .ide.software.version} file is restored while the existing installation with all its packages is preserved.
   *
   * @param wireMockRuntimeInfo the {@link WireMockRuntimeInfo}.
   * @throws IOException on error.
   * @see <a href="https://github.com/devonfw/IDEasy/issues/2190">issue 2190</a>
   */
  @Test
  public void testInstallRestoresMissingVersionFileAndPreservesPackages(WireMockRuntimeInfo wireMockRuntimeInfo) throws IOException {

    // arrange
    IdeTestContext context = newContext(PROJECT_UV, wireMockRuntimeInfo);
    context.setSystemInfo(SystemInfoMock.LINUX_X64);
    Python python = context.getCommandletManager().getCommandlet(Python.class);
    python.install();
    Path pythonPath = context.getSoftwarePath().resolve("python");
    Path versionFile = pythonPath.resolve(IdeContext.FILE_SOFTWARE_VERSION);
    Path userPackage = pythonPath.resolve("lib").resolve("site-packages").resolve("mylib").resolve("__init__.py");
    Files.createDirectories(userPackage.getParent());
    Files.writeString(userPackage, "# installed via pip");
    // simulate that the user or some tool has removed our version file from the interpreter installation
    Files.delete(versionFile);
    // the version is still determined from the installation itself (e.g. for "ide get-version python")
    assertThat(python.getInstalledVersion()).isEqualTo(VersionIdentifier.of("3.14.6"));

    // act
    python.install();

    // assert
    assertThat(versionFile).exists().hasContent("3.14.6");
    assertThat(userPackage).exists();
    assertThat(python.getInstalledVersion()).isEqualTo(VersionIdentifier.of("3.14.6"));
    assertThat(context).logAtWarning().hasMessageContaining("Version file is missing");
    assertThat(context).logAtWarning().hasNoMessageContaining("Deleting corrupted installation");
  }


  /**
   * Test that {@link Python#setEnvironment} does not export a project environment (no {@code VIRTUAL_ENV} or {@code UV_PROJECT_ENVIRONMENT}) since python is a
   * shared interpreter and each project must manage its own virtual environment, see
   * <a href="https://github.com/devonfw/IDEasy/issues/2701">issue 2701</a>.
   */
  @Test
  public void testSetEnvironment() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    Python python = new Python(context);
    Path rootDir = context.getSoftwarePath().resolve("python");
    ToolInstallation toolInstallation = new ToolInstallation(rootDir, rootDir, rootDir.resolve("bin"), VersionIdentifier.of("3.12.0"), true);
    Map<String, VariableLine> variables = new HashMap<>();
    EnvironmentVariableCollectorContext environmentContext = new EnvironmentVariableCollectorContext(variables,
        new VariableSource(EnvironmentVariablesType.WORKSPACE, null), WindowsPathSyntax.MSYS);

    // act
    python.setEnvironment(environmentContext, toolInstallation, false);

    // assert - the shared interpreter is on the PATH only, the environment variables that would bind it to a project must not be set
    assertThat(variables).doesNotContainKey("VIRTUAL_ENV");
    assertThat(variables).doesNotContainKey("UV_PROJECT_ENVIRONMENT");
    assertThat(variables).containsKey("PYTHON_HOME");
  }
}
