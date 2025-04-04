/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.ide.browsers.BrowserLauncher;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.serviceContainer.NonInjectable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.flutter.FlutterBundle;
import io.flutter.FlutterUtils;
import io.flutter.actions.RefreshToolWindowAction;
import io.flutter.bazel.WorkspaceCache;
import io.flutter.devtools.DevToolsIdeFeature;
import io.flutter.devtools.DevToolsUrl;
import io.flutter.jxbrowser.FailureType;
import io.flutter.jxbrowser.InstallationFailedReason;
import io.flutter.jxbrowser.JxBrowserManager;
import io.flutter.jxbrowser.JxBrowserStatus;
import io.flutter.run.FlutterDevice;
import io.flutter.run.daemon.DevToolsInstance;
import io.flutter.run.daemon.DevToolsService;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.sdk.FlutterSdk;
import io.flutter.sdk.FlutterSdkVersion;
import io.flutter.settings.FlutterSettings;
import io.flutter.toolwindow.FlutterViewToolWindowManagerListener;
import io.flutter.utils.AsyncUtils;
import io.flutter.utils.EventStream;
import io.flutter.utils.JxBrowserUtils;
import io.flutter.utils.LabelInput;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@com.intellij.openapi.components.State(
  name = "FlutterView",
  storages = {@Storage("$WORKSPACE_FILE$")}
)
public class FlutterView implements PersistentStateComponent<FlutterViewState>, Disposable {
  private static final Logger LOG = Logger.getInstance(FlutterView.class);

  public static final String TOOL_WINDOW_ID = "Flutter Inspector";

  public static final String WIDGET_TAB_LABEL = "Widgets";
  public static final String RENDER_TAB_LABEL = "Render Tree";
  public static final String PERFORMANCE_TAB_LABEL = "Performance";
  protected static final String INSTALLATION_IN_PROGRESS_LABEL = "Installing JxBrowser and DevTools...";
  protected static final String INSTALLATION_TIMED_OUT_LABEL =
    "Waiting for JxBrowser installation timed out. Restart your IDE to try again.";
  protected static final String INSTALLATION_WAIT_FAILED = "The JxBrowser installation failed unexpectedly. Restart your IDE to try again.";
  protected static final String DEVTOOLS_FAILED_LABEL = "Setting up DevTools failed.";
  protected static final int INSTALLATION_WAIT_LIMIT_SECONDS = 2000;

  protected final EventStream<Boolean> shouldAutoHorizontalScroll = new EventStream<>(FlutterViewState.AUTO_SCROLL_DEFAULT);
  protected final EventStream<Boolean> highlightNodesShownInBothTrees =
    new EventStream<>(FlutterViewState.HIGHLIGHT_NODES_SHOWN_IN_BOTH_TREES_DEFAULT);

  @NotNull
  private final FlutterViewState state = new FlutterViewState();

  @NotNull
  private final Project myProject;

  private Content emptyContent;

  private FlutterViewToolWindowManagerListener toolWindowListener;
  private int devToolsInstallCount = 0;
  private final JxBrowserUtils jxBrowserUtils;
  private final JxBrowserManager jxBrowserManager;

  public FlutterView(@NotNull Project project) {
    this(project, JxBrowserManager.getInstance(), new JxBrowserUtils());
  }

  @VisibleForTesting
  @NonInjectable
  protected FlutterView(@NotNull Project project, @NotNull JxBrowserManager jxBrowserManager, JxBrowserUtils jxBrowserUtils) {
    myProject = project;
    this.jxBrowserUtils = jxBrowserUtils;
    this.jxBrowserManager = jxBrowserManager;

    shouldAutoHorizontalScroll.listen(state::setShouldAutoScroll);
    highlightNodesShownInBothTrees.listen(state::setHighlightNodesShownInBothTrees);
  }

  @Override
  public void dispose() {
    Disposer.dispose(this);
  }

  @NotNull
  @Override
  public FlutterViewState getState() {
    return state;
  }

  @NotNull
  public Project getProject() {
    return myProject;
  }

  @Override
  public void loadState(@NotNull FlutterViewState state) {
    this.state.copyFrom(state);

    shouldAutoHorizontalScroll.setValue(this.state.getShouldAutoScroll());
    highlightNodesShownInBothTrees.setValue(this.state.getHighlightNodesShownInBothTrees());
  }

  void initToolWindow(ToolWindow window) {
    if (window.isDisposed()) return;

    updateForEmptyContent(window);
  }

  private void addBrowserInspectorViewContent(FlutterApp app,
                                              ToolWindow toolWindow,
                                              boolean isEmbedded,
                                              DevToolsIdeFeature ideFeature,
                                              DevToolsInstance devToolsInstance) {
    assert (SwingUtilities.isEventDispatchThread());

    final ContentManager contentManager = toolWindow.getContentManager();

    final FlutterDevice device = app.device();
    final List<FlutterDevice> existingDevices = new ArrayList<>();
    final String tabName = device.getUniqueName(existingDevices);

    if (emptyContent != null) {
      contentManager.removeContent(emptyContent, true);
      emptyContent = null;
    }

    final String browserUrl = app.getConnector().getBrowserUrl();
    FlutterSdk flutterSdk = FlutterSdk.getFlutterSdk(app.getProject());
    FlutterSdkVersion flutterSdkVersion = flutterSdk == null ? null : flutterSdk.getVersion();

    if (isEmbedded) {
      final DevToolsUrl devToolsUrl = new DevToolsUrl.Builder()
        .setDevToolsHost(devToolsInstance.host())
        .setDevToolsPort(devToolsInstance.port())
        .setVmServiceUri(browserUrl)
        .setPage("inspector")
        .setEmbed(true)
        .setFlutterSdkVersion(flutterSdkVersion)
        .setWorkspaceCache(WorkspaceCache.getInstance(app.getProject()))
        .setIdeFeature(ideFeature)
        .build();

      //noinspection CodeBlock2Expr

      Runnable task = () -> {
        embeddedBrowserOptional().ifPresent(
          embeddedBrowser -> ApplicationManager.getApplication().invokeLater(() -> {
            embeddedBrowser.openPanel(toolWindow, tabName, devToolsUrl, (String error) -> {
              // If the embedded browser doesn't work, offer a link to open in the regular browser.
              final List<LabelInput> inputs = Arrays.asList(
                new LabelInput("The embedded browser failed to load. Error: " + error),
                openDevToolsLabel(app, toolWindow, ideFeature)
              );
              presentClickableLabel(toolWindow, inputs);
            });
          }));
      };
      final ProgressManager progressManager = ProgressManager.getInstanceOrNull();
      if (progressManager != null) {
        progressManager.runProcess(task, new EmptyProgressIndicator());
      }

      toolWindow.setTitleActions(List.of(new RefreshToolWindowAction(TOOL_WINDOW_ID)));
    }
    else {
      BrowserLauncher.getInstance().browse(
        new DevToolsUrl.Builder()
          .setDevToolsHost(devToolsInstance.host())
          .setDevToolsPort(devToolsInstance.port())
          .setVmServiceUri(browserUrl)
          .setPage("inspector")
          .setFlutterSdkVersion(flutterSdkVersion)
          .setWorkspaceCache(WorkspaceCache.getInstance(app.getProject()))
          .setIdeFeature(ideFeature)
          .build()
          .getUrlString(),
        null
      );
      presentLabel(toolWindow, "DevTools inspector has been opened in the browser.");
    }
  }

  private Optional<EmbeddedBrowser> embeddedBrowserOptional() {
    if (myProject.isDisposed()) {
      return Optional.empty();
    }
    return Optional.ofNullable(FlutterUtils.embeddedBrowser(myProject));
  }

  /**
   * Called when a debug connection starts.
   */
  public void debugActive(@NotNull FlutterViewMessages.FlutterDebugEvent event) {
    final FlutterApp app = event.app;
    if (app.getFlutterDebugProcess() == null) {
      return;
    }
    ApplicationManager.getApplication().invokeLater(() -> debugActiveHelper(app));
  }

  protected void handleJxBrowserInstalled(FlutterApp app, ToolWindow toolWindow, DevToolsIdeFeature ideFeature) {
    presentDevTools(app, toolWindow, true, ideFeature);
  }

  private void presentDevTools(FlutterApp app, ToolWindow toolWindow, boolean isEmbedded, DevToolsIdeFeature ideFeature) {
    verifyEventDispatchThread();

    devToolsInstallCount += 1;
    presentLabel(toolWindow, getInstallingDevtoolsLabel());

    openInspectorWithDevTools(app, toolWindow, isEmbedded, ideFeature);

    setUpToolWindowListener(app, toolWindow, isEmbedded, ideFeature);
  }

  @VisibleForTesting
  protected void verifyEventDispatchThread() {
    assert (SwingUtilities.isEventDispatchThread());
  }

  @VisibleForTesting
  protected void setUpToolWindowListener(FlutterApp app, ToolWindow toolWindow, boolean isEmbedded, DevToolsIdeFeature ideFeature) {
    if (this.toolWindowListener == null) {
      this.toolWindowListener = new FlutterViewToolWindowManagerListener(myProject, toolWindow);
    }
    this.toolWindowListener.updateOnWindowOpen(() -> {
      devToolsInstallCount += 1;
      presentLabel(toolWindow, getInstallingDevtoolsLabel());
      openInspectorWithDevTools(app, toolWindow, isEmbedded, ideFeature, true);
    });
  }

  private String getInstallingDevtoolsLabel() {
    return "<html><body style=\"text-align: center;\">" +
           FlutterBundle.message("flutter.devtools.installing", devToolsInstallCount) + "</body></html>";
  }

  @VisibleForTesting
  protected void openInspectorWithDevTools(FlutterApp app, ToolWindow toolWindow, boolean isEmbedded, DevToolsIdeFeature ideFeature) {
    openInspectorWithDevTools(app, toolWindow, isEmbedded, ideFeature, false);
  }

  private void openInspectorWithDevTools(FlutterApp app,
                                         ToolWindow toolWindow,
                                         boolean isEmbedded,
                                         DevToolsIdeFeature ideFeature,
                                         boolean forceDevToolsRestart) {
    AsyncUtils.whenCompleteUiThread(
      forceDevToolsRestart
      ? DevToolsService.getInstance(myProject).getDevToolsInstanceWithForcedRestart()
      : DevToolsService.getInstance(myProject).getDevToolsInstance(),
      (instance, error) -> {
        // Skip displaying if the project has been closed.
        if (!myProject.isOpen()) {
          return;
        }

        // TODO(helinx): Restart DevTools server if there's an error.
        if (error != null) {
          LOG.error(error);
          presentLabel(toolWindow, DEVTOOLS_FAILED_LABEL);
          return;
        }

        if (instance == null) {
          presentLabel(toolWindow, DEVTOOLS_FAILED_LABEL);
          return;
        }

        addBrowserInspectorViewContent(app, toolWindow, isEmbedded, ideFeature, instance);
      }
    );
  }

  private LabelInput openDevToolsLabel(FlutterApp app, ToolWindow toolWindow, DevToolsIdeFeature ideFeature) {
    return new LabelInput("Open DevTools in the browser?", (linkLabel, data) -> {
      presentDevTools(app, toolWindow, false, ideFeature);
    });
  }

  protected void handleJxBrowserInstallationInProgress(FlutterApp app, ToolWindow toolWindow,
                                                       DevToolsIdeFeature ideFeature) {
    presentOpenDevToolsOptionWithMessage(app, toolWindow, INSTALLATION_IN_PROGRESS_LABEL, ideFeature);

    if (jxBrowserManager.getStatus().equals(JxBrowserStatus.INSTALLED)) {
      handleJxBrowserInstalled(app, toolWindow, ideFeature);
    }
    else {
      startJxBrowserInstallationWaitingThread(app, toolWindow, ideFeature);
    }
  }

  protected void startJxBrowserInstallationWaitingThread(FlutterApp app, ToolWindow toolWindow,
                                                         DevToolsIdeFeature ideFeature) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      waitForJxBrowserInstallation(app, toolWindow, ideFeature);
    });
  }

  protected void waitForJxBrowserInstallation(FlutterApp app, ToolWindow toolWindow,
                                              DevToolsIdeFeature ideFeature) {
    try {
      final JxBrowserStatus newStatus = jxBrowserManager.waitForInstallation(INSTALLATION_WAIT_LIMIT_SECONDS);

      handleUpdatedJxBrowserStatusOnEventThread(app, toolWindow, newStatus, ideFeature);
    }
    catch (TimeoutException e) {
      presentOpenDevToolsOptionWithMessage(app, toolWindow, INSTALLATION_TIMED_OUT_LABEL, ideFeature);
    }
  }

  protected void handleUpdatedJxBrowserStatusOnEventThread(
    FlutterApp app,
    ToolWindow toolWindow,
    JxBrowserStatus jxBrowserStatus,
    DevToolsIdeFeature ideFeature) {
    AsyncUtils.invokeLater(() -> handleUpdatedJxBrowserStatus(app, toolWindow, jxBrowserStatus, ideFeature));
  }

  protected void handleUpdatedJxBrowserStatus(
    FlutterApp app,
    ToolWindow toolWindow,
    JxBrowserStatus jxBrowserStatus,
    DevToolsIdeFeature ideFeature) {
    if (Objects.equals(jxBrowserStatus, JxBrowserStatus.INSTALLED)) {
      handleJxBrowserInstalled(app, toolWindow, ideFeature);
    }
    else if (jxBrowserStatus.equals(JxBrowserStatus.INSTALLATION_FAILED)) {
      handleJxBrowserInstallationFailed(app, toolWindow, ideFeature);
    }
    else {
      // newStatus can be null if installation is interrupted or stopped for another reason.
      presentOpenDevToolsOptionWithMessage(app, toolWindow, INSTALLATION_WAIT_FAILED, ideFeature);
    }
  }

  protected void handleJxBrowserInstallationFailed(FlutterApp app, ToolWindow toolWindow,
                                                   DevToolsIdeFeature ideFeature) {
    final List<LabelInput> inputs = new ArrayList<>();
    final LabelInput openDevToolsLabel = openDevToolsLabel(app, toolWindow, ideFeature);

    final InstallationFailedReason latestFailureReason = jxBrowserManager.getLatestFailureReason();

    if (!jxBrowserUtils.licenseIsSet()) {
      // If the license isn't available, allow the user to open the equivalent page in a non-embedded browser window.
      inputs.add(new LabelInput("The JxBrowser license could not be found."));
      inputs.add(openDevToolsLabel);
    }
    else if (latestFailureReason != null && Objects.equals(latestFailureReason.failureType, FailureType.SYSTEM_INCOMPATIBLE)) {
      // If we know the system is incompatible, skip retry link and offer to open in browser.
      inputs.add(new LabelInput(latestFailureReason.detail));
      inputs.add(openDevToolsLabel);
    }
    else {
      // Allow the user to manually restart or open the equivalent page in a non-embedded browser window.
      inputs.add(new LabelInput("JxBrowser installation failed."));
      inputs.add(new LabelInput("Retry installation?", (linkLabel, data) -> {
        jxBrowserManager.retryFromFailed(app.getProject());
        handleJxBrowserInstallationInProgress(app, toolWindow, ideFeature);
      }));
      inputs.add(openDevToolsLabel);
    }

    presentClickableLabel(toolWindow, inputs);
  }

  protected void presentLabel(ToolWindow toolWindow, String text) {
    final JBLabel label = new JBLabel(text, SwingConstants.CENTER);
    label.setForeground(UIUtil.getLabelDisabledForeground());
    replacePanelLabel(toolWindow, label);
  }

  protected void presentClickableLabel(ToolWindow toolWindow, List<LabelInput> labels) {
    final JPanel panel = new JPanel(new GridLayout(0, 1));

    for (LabelInput input : labels) {
      if (input.listener == null) {
        final JLabel descriptionLabel = new JLabel("<html>" + input.text + "</html>");
        descriptionLabel.setBorder(JBUI.Borders.empty(5));
        descriptionLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(descriptionLabel, BorderLayout.NORTH);
      }
      else {
        final LinkLabel<String> linkLabel = new LinkLabel<>("<html>" + input.text + "</html>", null);
        linkLabel.setBorder(JBUI.Borders.empty(5));
        linkLabel.setListener(input.listener, null);
        linkLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(linkLabel, BorderLayout.SOUTH);
      }
    }

    final JPanel center = new JPanel(new VerticalFlowLayout(VerticalFlowLayout.CENTER));
    center.add(panel);
    replacePanelLabel(toolWindow, center);
  }

  protected void presentOpenDevToolsOptionWithMessage(FlutterApp app,
                                                      ToolWindow toolWindow,
                                                      String message,
                                                      DevToolsIdeFeature ideFeature) {
    final List<LabelInput> inputs = new ArrayList<>();
    inputs.add(new LabelInput(message));
    inputs.add(openDevToolsLabel(app, toolWindow, ideFeature));
    presentClickableLabel(toolWindow, inputs);
  }

  private void replacePanelLabel(ToolWindow toolWindow, JComponent label) {
    ApplicationManager.getApplication().invokeLater(() -> {
      final ContentManager contentManager = toolWindow.getContentManager();
      if (contentManager.isDisposed()) {
        return;
      }

      final JPanel panel = new JPanel(new BorderLayout());
      panel.add(label, BorderLayout.CENTER);
      final Content content = contentManager.getFactory().createContent(panel, null, false);
      contentManager.removeAllContents(true);
      contentManager.addContent(content);
    });
  }

  private void debugActiveHelper(FlutterApp app) {
    final ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(myProject);
    if (!(toolWindowManager instanceof ToolWindowManagerEx)) {
      return;
    }

    final ToolWindow toolWindow = toolWindowManager.getToolWindow(FlutterView.TOOL_WINDOW_ID);
    if (toolWindow == null) {
      return;
    }

    AtomicReference<DevToolsIdeFeature> ideFeature = new AtomicReference<>(null);
    if (toolWindow.isAvailable()) {
      ideFeature.set(updateToolWindowVisibility(toolWindow));
    }
    else {
      toolWindow.setAvailable(true, () -> {
        ideFeature.set(updateToolWindowVisibility(toolWindow));
      });
    }

    if (emptyContent != null) {
      final ContentManager contentManager = toolWindow.getContentManager();
      contentManager.removeContent(emptyContent, true);
      emptyContent = null;
    }

    if (toolWindow.isVisible()) {
      displayEmbeddedBrowser(app, toolWindow, ideFeature.get());
    }
    else {
      if (toolWindowListener == null) {
        toolWindowListener = new FlutterViewToolWindowManagerListener(myProject, toolWindow);
      }
      // If the window isn't visible yet, only executed embedded browser steps when it becomes visible.
      toolWindowListener.updateOnWindowFirstVisible(() -> {
        displayEmbeddedBrowser(app, toolWindow, DevToolsIdeFeature.TOOL_WINDOW);
      });
    }
  }

  private void displayEmbeddedBrowser(FlutterApp app, ToolWindow toolWindow, DevToolsIdeFeature ideFeature) {
    if (FlutterSettings.getInstance().isEnableJcefBrowser()) {
      presentDevTools(app, toolWindow, true, ideFeature);
    }
    else {
      displayEmbeddedBrowserIfJxBrowser(app, toolWindow, ideFeature);
    }
  }

  private void displayEmbeddedBrowserIfJxBrowser(FlutterApp app, ToolWindow toolWindow,
                                                 DevToolsIdeFeature ideFeature) {
    final JxBrowserManager manager = jxBrowserManager;
    final JxBrowserStatus jxBrowserStatus = manager.getStatus();

    if (jxBrowserStatus.equals(JxBrowserStatus.INSTALLED)) {
      handleJxBrowserInstalled(app, toolWindow, ideFeature);
    }
    else if (jxBrowserStatus.equals(JxBrowserStatus.INSTALLATION_IN_PROGRESS)) {
      handleJxBrowserInstallationInProgress(app, toolWindow, ideFeature);
    }
    else if (jxBrowserStatus.equals(JxBrowserStatus.INSTALLATION_FAILED)) {
      handleJxBrowserInstallationFailed(app, toolWindow, ideFeature);
    }
    else if (jxBrowserStatus.equals(JxBrowserStatus.NOT_INSTALLED) || jxBrowserStatus.equals(JxBrowserStatus.INSTALLATION_SKIPPED)) {
      manager.setUp(myProject.getName());
      handleJxBrowserInstallationInProgress(app, toolWindow, ideFeature);
    }
  }

  private void updateForEmptyContent(ToolWindow toolWindow) {
    // There's a possible race here where the tool window gets disposed while we're displaying contents.
    if (toolWindow.isDisposed()) {
      return;
    }

    // Display a 'No running applications' message.
    final ContentManager contentManager = toolWindow.getContentManager();
    final JPanel panel = new JPanel(new BorderLayout());
    final JBLabel label = new JBLabel("No running applications", SwingConstants.CENTER);
    label.setForeground(UIUtil.getLabelDisabledForeground());
    panel.add(label, BorderLayout.CENTER);
    emptyContent = contentManager.getFactory().createContent(panel, null, false);
    contentManager.addContent(emptyContent);
  }

  // Returns true if the toolWindow was initially closed but opened automatically on app launch.
  private DevToolsIdeFeature updateToolWindowVisibility(ToolWindow flutterToolWindow) {
    if (flutterToolWindow.isVisible()) {
      return DevToolsIdeFeature.TOOL_WINDOW_RELOAD;
    }

    if (FlutterSettings.getInstance().isOpenInspectorOnAppLaunch()) {
      flutterToolWindow.show(null);
      return DevToolsIdeFeature.ON_DEBUG_AUTOMATIC;
    }

    return null;
  }
}
