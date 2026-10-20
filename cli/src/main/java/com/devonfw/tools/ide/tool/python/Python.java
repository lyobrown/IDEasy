package com.devonfw.tools.ide.tool.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.devonfw.tools.ide.cli.CliException;
import com.devonfw.tools.ide.common.Tag;
import com.devonfw.tools.ide.context.IdeContext;
import com.devonfw.tools.ide.io.FileAccess;
import com.devonfw.tools.ide.log.IdeLogLevel;
import com.devonfw.tools.ide.process.EnvironmentContext;
import com.devonfw.tools.ide.process.ProcessContext;
import com.devonfw.tools.ide.process.ProcessErrorHandling;
import com.devonfw.tools.ide.process.ProcessMode;
import com.devonfw.tools.ide.process.ProcessResult;
import com.devonfw.tools.ide.tool.AbstractLocalToolCommandlet;
import com.devonfw.tools.ide.tool.ToolInstallRequest;
import com.devonfw.tools.ide.tool.ToolInstallation;
import com.devonfw.tools.ide.tool.repository.ToolRepository;
import com.devonfw.tools.ide.tool.uv.Uv;
import com.devonfw.tools.ide.version.VersionIdentifier;

/**
 * {@link AbstractToolCommandlet} for <a href="https://www.python.org/">python</a>.
 * <p>
 * Python is installed as a standalone, uv-managed interpreter via {@code uv python install}. The interpreter is a shared tool (on the {@code PATH} like any
 * other IDEasy tool), while each project keeps its own virtual environment (e.g. {@code .venv}) that it creates with {@code uv venv}/{@code uv sync}. Therefore
 * IDEasy must not export {@code VIRTUAL_ENV} or {@code UV_PROJECT_ENVIRONMENT} — those point all projects at a single shared environment and break uv
 * (see <a href="https://github.com/devonfw/IDEasy/issues/2701">issue 2701</a>).
 */
public class Python extends AbstractLocalToolCommandlet {

  private static final Logger LOG = LoggerFactory.getLogger(Python.class);

  private static final VersionIdentifier PYTHON_MIN_VERSION = VersionIdentifier.of("3.8.2");

  /** The (hidden) folder used to install the interpreter with uv before it is relocated to the python installation. */
  static final String FOLDER_UV_STAGING = ".uv-staging";

  /** The folder a uv-managed interpreter uses for its console scripts on Windows. */
  private static final String FOLDER_SCRIPTS = "Scripts";

  /** The interpreter executables on Windows that must be resolvable via the {@code bin} folder. */
  private static final List<String> WINDOWS_PYTHON_EXES = List.of("python.exe", "pythonw.exe");

  /**
   * The constructor.
   *
   * @param context the {@link IdeContext}.
   */
  public Python(IdeContext context) {

    super(context, "python", Set.of(Tag.PYTHON));
  }

  @Override
  protected void performToolInstallation(ToolInstallRequest request, Path installationPath) {

    VersionIdentifier resolvedVersion = request.getRequested().getResolvedVersion();
    if (resolvedVersion.compareVersion(PYTHON_MIN_VERSION).isLess()) {
      throw new CliException("Python version must be at least " + this.PYTHON_MIN_VERSION);
    }

    FileAccess fileAccess = this.context.getFileAccess();
    if (Files.exists(installationPath)) {
      fileAccess.backup(installationPath);
    }
    // clean up an interpreter folder that a previous, interrupted installation may have left behind
    if (Files.isDirectory(installationPath)) {
      fileAccess.delete(installationPath);
    }
    Path softwarePath = installationPath.getParent();
    Path stagingPath = softwarePath.resolve(FOLDER_UV_STAGING);
    deleteNonFatal(stagingPath);
    try {
      Files.createDirectories(stagingPath);
      Uv uv = this.context.getCommandletManager().getCommandlet(Uv.class);
      uv.installPython(stagingPath, resolvedVersion, request.getProcessContext());
      fileAccess.move(findUvInterpreterDir(stagingPath), installationPath, StandardCopyOption.REPLACE_EXISTING);
      createWindowsBinSymlink(fileAccess, installationPath);
      this.context.writeVersionFile(resolvedVersion, installationPath);
      deleteNonFatal(stagingPath);
      LOG.debug("Installed {} in version {} at {}", this.tool, resolvedVersion, installationPath);
    } catch (IOException e) {
      throw new CliException("Failed to install python in version " + resolvedVersion + " at " + installationPath, e);
    }
  }

  /**
   * Deletes the given folder but only logs a warning instead of failing the installation. This is used for the uv staging folder which is pure temporary
   * debris: a leftover (e.g. because a previous installation was interrupted) is harmless and the next installation recreates it from scratch, so a failed
   * deletion (e.g. a locked file on Windows) must not block the installation.
   *
   * @param path the folder to delete
   */
  private void deleteNonFatal(Path path) {

    if (!Files.exists(path)) {
      return;
    }
    try {
      this.context.getFileAccess().delete(path);
    } catch (RuntimeException e) {
      LOG.warn("Could not delete uv staging folder {} (this is harmless, the next installation recreates it).", path, e);
    }
  }

  /**
   * Determines the interpreter directory that uv created in the given staging folder. On Windows the interpreter lives in a {@code python} sub-folder, on all
   * other platforms in a {@code bin} sub-folder.
   *
   * @param stagingPath the folder uv installed the interpreter into
   * @return the directory to be relocated into the python installation
   */
  private Path findUvInterpreterDir(Path stagingPath) {

    try (Stream<Path> children = Files.list(stagingPath)) {
      List<Path> candidates = children.filter(child -> !Files.isSymbolicLink(child)).filter(Files::isDirectory)
          .filter(child -> !child.getFileName().toString().startsWith(".")).filter(this::isUvInterpreterRoot).toList();
      if (candidates.size() != 1) {
        throw new CliException("Unexpected interpreter layout in uv staging folder " + stagingPath
            + ": expected exactly one interpreter directory but found " + candidates.size());
      }
      return candidates.get(0);
    } catch (IOException e) {
      throw new CliException("Failed to determine the uv-managed interpreter in " + stagingPath, e);
    }
  }

  /**
   * @param path the {@link Path} to check
   * @return {@code true} if the given {@code Path} is the root of a uv-managed interpreter (it contains a {@code bin} folder on Unix or a {@code Lib} folder on
   *     Windows)
   */
  private boolean isUvInterpreterRoot(Path path) {

    return Files.isDirectory(path.resolve(IdeContext.FOLDER_BIN)) || Files.isDirectory(path.resolve("Lib"));
  }

  /**
   * Reconciles the layout of a uv-managed interpreter with IDEasy's convention that a tool's executables live in a single {@code bin} folder. On Windows
   * the interpreter puts {@code python.exe} (with the adjacent DLLs) at its root while the console scripts ({@code pip.exe}, {@code uv tool} launchers) are
   * placed in {@code Scripts}. To expose both under one {@code bin} folder (like the previous virtualenv-based layout) this links the interpreter executables
   * into {@code Scripts} and {@code bin} into {@code Scripts}. The executables are symbolic (not hard) links so that the process image resolves to the real
   * path at the installation root, where the loader finds the required DLLs.
   *
   * @param fileAccess the {@link FileAccess} utility for file operations.
   * @param installationPath the path where Python is installed.
   */
  private void createWindowsBinSymlink(FileAccess fileAccess, Path installationPath) {

    if (!this.context.getSystemInfo().isWindows()) {
      return;
    }
    Path scriptsPath = installationPath.resolve(FOLDER_SCRIPTS);
    for (String exe : WINDOWS_PYTHON_EXES) {
      Path exeSource = installationPath.resolve(exe);
      if (Files.exists(exeSource)) {
        fileAccess.symlink(exeSource, scriptsPath.resolve(exe));
      }
    }
    fileAccess.symlink(scriptsPath, installationPath.resolve(IdeContext.FOLDER_BIN));
  }

  @Override
  public void setEnvironment(EnvironmentContext environmentContext, ToolInstallation toolInstallation, boolean additionalInstallation) {

    // Python is a shared interpreter: each project manages its own virtual environment (see class JavaDoc), so no VIRTUAL_ENV/UV_PROJECT_ENVIRONMENT is set.
    super.setEnvironment(environmentContext, toolInstallation, additionalInstallation);
  }

  @Override
  protected boolean isIgnoreSoftwareRepo() {

    return true;
  }

  @Override
  protected boolean isIgnoreMissingSoftwareVersionFile() {

    // https://github.com/devonfw/IDEasy/issues/2190
    return true;
  }

  @Override
  protected VersionIdentifier computeInstalledVersionFromLocalSoftwareFolder() {

    Path toolPath = getToolPath();
    VersionIdentifier version = readVersionFromInterpreter(toolPath);
    if (version != null) {
      LOG.debug("Determined version {} of python from the installation at {}.", version, toolPath);
    }
    return version;
  }

  /**
   * @param installationPath the {@link Path} to the python installation.
   * @return the {@link VersionIdentifier} reported by the installed python interpreter or {@code null} if it could not be determined.
   */
  private VersionIdentifier readVersionFromInterpreter(Path installationPath) {

    Path binPath = this.context.getFileAccess().getBinPath(installationPath);
    Path binaryPath = this.context.getPath().findBinary(binPath.resolve(getBinaryName()));
    if (!Files.exists(binaryPath)) {
      LOG.debug("Python binary does not exist in {}.", binPath);
      return null;
    }
    ProcessContext pc = this.context.newProcess().errorHandling(ProcessErrorHandling.NONE).withPathEntry(binPath);
    ProcessResult result = runTool(pc, ProcessMode.DEFAULT_CAPTURE, List.of("--version"));
    String output = result.getSingleOutput(IdeLogLevel.DEBUG);
    if (output == null) {
      return null;
    }
    String version = output.trim();
    int lastSpace = version.lastIndexOf(' ');
    if (lastSpace >= 0) {
      version = version.substring(lastSpace + 1);
    }
    if (!version.isEmpty() && Character.isDigit(version.charAt(0))) {
      return VersionIdentifier.of(version);
    }
    LOG.debug("Could not parse version from output '{}' of {}.", output, binPath);
    return null;
  }

  @Override
  public ToolRepository getToolRepository() {

    return this.context.getPythonRepository();
  }

}
