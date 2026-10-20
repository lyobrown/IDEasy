package com.devonfw.tools.ide.tool.uv;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.devonfw.tools.ide.common.Tag;
import com.devonfw.tools.ide.context.IdeContext;
import com.devonfw.tools.ide.json.JsonMapping;
import com.devonfw.tools.ide.process.EnvironmentContext;
import com.devonfw.tools.ide.process.ProcessContext;
import com.devonfw.tools.ide.process.ProcessMode;
import com.devonfw.tools.ide.process.ProcessResult;
import com.devonfw.tools.ide.tool.AbstractLocalToolCommandlet;
import com.devonfw.tools.ide.tool.ToolInstallation;
import com.devonfw.tools.ide.tool.python.PythonUvListEntry;
import com.devonfw.tools.ide.version.VersionIdentifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link AbstractToolCommandlet} for <a href="https://docs.astral.sh/uv/">uv</a>.
 */
public class Uv extends AbstractLocalToolCommandlet {


  /**
   * The constructor.
   *
   * @param context the {@link IdeContext}.
   */
  public Uv(IdeContext context) {

    super(context, "uv", Set.of(Tag.PYTHON));
  }

  /**
   * Installs the given {@code Python} version as a standalone, uv-managed interpreter into the given directory.
   * <p>
   * The {@code --no-bin} flag prevents uv from dropping an executable into the user's {@code ~/.local/bin}; IDEasy manages the interpreter location
   * itself (see {@link com.devonfw.tools.ide.tool.python.Python}).
   *
   * @param installDir the directory uv shall install the interpreter into (uv creates a {@code cpython-*} sub-directory in it)
   * @param resolvedVersion the {@link VersionIdentifier} of the {@code Python} version to install
   * @param processContext the {@link ProcessContext} used to execute the {@code uv} command
   */
  public void installPython(Path installDir, VersionIdentifier resolvedVersion, ProcessContext processContext) {

    List<String> args = new ArrayList<>();
    args.add("python");
    args.add("install");
    args.add(resolvedVersion.toString());
    args.add("--install-dir");
    args.add(installDir.toString());
    args.add("--no-bin");
    ProcessResult result = runTool(processContext, ProcessMode.DEFAULT_CAPTURE, args);
    assert result.isSuccessful();
  }

  private static final ObjectMapper MAPPER = JsonMapping.create();

  /**
   * Parses the JSON output of {@code uv python list --output-format json} into a list of {@link PythonUvListEntry entries}.
   *
   * @param jsonLines the captured standard output lines of the {@code uv} command.
   * @return the {@link List} of parsed {@link PythonUvListEntry entries}.
   */
  public List<PythonUvListEntry> parsePythonListJson(List<String> jsonLines) {

    String json = String.join("\n", jsonLines).trim();
    List<PythonUvListEntry> entries = new ArrayList<>();
    if (json.isEmpty()) {
      return entries;
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      if (root.isArray()) {
        for (JsonNode node : root) {
          JsonNode versionNode = node.get("version");
          if ((versionNode != null) && !versionNode.isNull()) {
            JsonNode implNode = node.get("implementation");
            String implementation = (implNode != null && !implNode.isNull()) ? implNode.asText() : null;
            entries.add(new PythonUvListEntry(versionNode.asText(), implementation));
          }
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse JSON output of 'uv python list'.", e);
    }
    return entries;
  }

  @Override
  public void setEnvironment(EnvironmentContext environmentContext, ToolInstallation toolInstallation, boolean additionalInstallation) {

    super.setEnvironment(environmentContext, toolInstallation, additionalInstallation);
    Path softwarePath = this.context.getSoftwarePath();
    if (softwarePath == null) {
      return;
    }
    Path pythonPath = softwarePath.resolve("python");
    environmentContext.withEnvVar("UV_TOOL_DIR", pythonPath.resolve("tools").toString());
    environmentContext.withEnvVar("UV_TOOL_BIN_DIR", pythonPath.resolve("bin").toString());
    environmentContext.withPathEntry(pythonPath.resolve("bin"));
  }
}
