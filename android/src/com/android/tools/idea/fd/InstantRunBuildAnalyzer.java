/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.fd;

import com.android.tools.fd.client.InstantRunArtifact;
import com.android.tools.fd.client.InstantRunBuildInfo;
import com.android.tools.idea.diagnostics.crash.CrashReporter;
import com.android.tools.idea.run.ApkInfo;
import com.android.tools.idea.run.LaunchOptions;
import com.android.tools.idea.run.tasks.*;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.internal.statistic.utils.StatisticsUploadAssistant;
import com.intellij.openapi.project.Project;
import com.intellij.util.SmartList;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.android.tools.fd.client.InstantRunArtifactType.*;

/**
 * {@link InstantRunBuildAnalyzer} analyzes the result of a gradle instant run build, and provides the list of deploy tasks
 * to update the state of the app on the device.
 */
public class InstantRunBuildAnalyzer {
  private final Project myProject;
  private final InstantRunContext myContext;
  private final ProcessHandler myCurrentSession;
  private final InstantRunBuildInfo myBuildInfo;

  public InstantRunBuildAnalyzer(@NotNull Project project, @NotNull InstantRunContext context, @Nullable ProcessHandler currentSession) {
    myProject = project;
    myContext = context;
    myCurrentSession = currentSession;

    myBuildInfo = myContext.getInstantRunBuildInfo();
    if (myBuildInfo == null) {
      throw new IllegalArgumentException("Instant Run Build Information must be available post build");
    }

    if (!myBuildInfo.isCompatibleFormat()) {
      throw new IllegalStateException("This version of Android Studio is incompatible with the Gradle Plugin used. " +
                                         "Try disabling Instant Run (or updating either the IDE or the Gradle plugin to " +
                                         "the latest version)");
    }
  }

  /**
   * Returns whether the existing process handler (corresponding to the run session) can be reused based on this build's artifacts.
   * For instance, we can reuse the existing session if the current session is active, and the results indicate that the changes can be
   * hot swapped.
   */
  public boolean canReuseProcessHandler() {
    if (myCurrentSession == null || myCurrentSession.isProcessTerminated()) {
      return false;
    }

    BuildSelection buildSelection = myContext.getBuildSelection();
    assert buildSelection != null : "Build must have completed before results are analyzed";
    return buildSelection.getBuildMode() == BuildMode.HOT && (myBuildInfo.hasNoChanges() || myBuildInfo.canHotswap());
  }

  /**
   * Returns the list of deploy tasks that will update the instant run state on the device.
   */
  @NotNull
  public List<LaunchTask> getDeployTasks(@Nullable LaunchOptions launchOptions) throws ExecutionException {
    LaunchTask updateStateTask = new UpdateInstantRunStateTask(myContext);

    DeployType deployType = getDeployType();
    switch (deployType) {
      case NO_CHANGES:
        return ImmutableList.of(new NoChangesTask(myProject, myContext), updateStateTask);
      case HOTSWAP:
      case WARMSWAP:
        return ImmutableList
          .of(new HotSwapTask(myProject, myContext, deployType == DeployType.WARMSWAP), updateStateTask);
      case SPLITAPK:
        return ImmutableList.of(new SplitApkDeployTask(myProject, myContext), updateStateTask);
      case DEX:
        if (!canReuseProcessHandler()) {
          throw new IllegalStateException(
            "Cannot hotswap changes - the process has died since the build was started. Please Run or Debug again to recover from this issue.");
        }
        // fall through
      case FULLAPK:
        Preconditions.checkNotNull(launchOptions); // launchOptions can be null only under NO_CHANGES or HOTSWAP scenarios
        DeployApkTask deployApkTask = new DeployApkTask(myProject, launchOptions, getApks(myBuildInfo, myContext), myContext);
        return ImmutableList.of(deployApkTask, updateStateTask);
      case LEGACY:
      default:
        // https://code.google.com/p/android/issues/detail?id=232515
        // We don't know as yet how this happened, so we collect some information
        if (StatisticsUploadAssistant.isSendAllowed()) {
          CrashReporter.getInstance().submit(getIrDebugSignals(deployType));
        }
        throw new IllegalStateException(AndroidBundle.message("instant.run.build.error"));
    }
  }

  @NotNull
  private Map<String, String> getIrDebugSignals(@NotNull DeployType deployType) {
    Map<String, String> m = new HashMap<>();

    m.put("deployType", deployType.toString());
    m.put("canReuseProcessHandler", Boolean.toString(canReuseProcessHandler()));
    m.put("androidGradlePluginVersion", myContext.getGradlePluginVersion().toString());

    BuildSelection selection = myContext.getBuildSelection();
    if (selection != null) {
      m.put("buildSelection.mode", selection.getBuildMode().toString());
      m.put("buildSelection.why", selection.why.toString());
    }

    InstantRunBuildInfo buildInfo = myContext.getInstantRunBuildInfo();
    if (buildInfo != null) {
      m.put("buildinfo.buildMode", buildInfo.getBuildMode());
      m.put("buildinfo.verifierStatus", buildInfo.getVerifierStatus());
      m.put("buildinfo.format", Integer.toString(buildInfo.getFormat()));

      List<InstantRunArtifact> artifacts = buildInfo.getArtifacts();
      m.put("buildinfo.nArtifacts", Integer.toString(artifacts.size()));
      for (int i = 0; i < artifacts.size(); i++) {
        InstantRunArtifact artifact = artifacts.get(i);
        String prefix = "buildInfo.artifact[" + i + "]";

        m.put(prefix + ".type", artifact.type.toString());
        m.put(prefix + ".file", artifact.file.getName());
      }
    }

    return m;
  }

  @NotNull
  public LaunchTask getNotificationTask() {
    DeployType deployType = getDeployType();
    BuildSelection buildSelection = myContext.getBuildSelection();

    InstantRunNotificationProvider notificationProvider =
      new InstantRunNotificationProvider(buildSelection, deployType, myBuildInfo.getVerifierStatus());

    return new InstantRunNotificationTask(myProject, myContext, notificationProvider, buildSelection.brokenForSecondaryUser);
  }

  @NotNull
  private DeployType getDeployType() {
    if (canReuseProcessHandler()) { // is this needed? do we make sure we do a cold swap when there is no process handler?
      if (myBuildInfo.hasNoChanges()) {
        return DeployType.NO_CHANGES;
      }
      else if (myBuildInfo.canHotswap()) {
        return InstantRunSettings.isRestartActivity() ? DeployType.WARMSWAP : DeployType.HOTSWAP;
      }
    }

    List<InstantRunArtifact> artifacts = myBuildInfo.getArtifacts();
    if (artifacts.isEmpty()) {
      return DeployType.NO_CHANGES;
    }

    if (myBuildInfo.hasOneOf(SPLIT) || myBuildInfo.hasOneOf(SPLIT_MAIN)) {
      return DeployType.SPLITAPK;
    }

    if (myBuildInfo.hasOneOf(DEX, RESOURCES)) {
      return DeployType.DEX;
    }

    return DeployType.FULLAPK;
  }

  private static Collection<ApkInfo> getApks(@NotNull InstantRunBuildInfo buildInfo, @NotNull InstantRunContext context)
    throws ExecutionException {
    List<ApkInfo> apks = new SmartList<>();

    for (InstantRunArtifact artifact : buildInfo.getArtifacts()) {
      if (artifact.type != MAIN) {
        String msg = "Expected to only find apks, but got : " + artifact.type + "\n";
        BuildSelection buildSelection = context.getBuildSelection();
        assert buildSelection != null : "Build must have completed before apks are obtained";
        if (buildSelection.getBuildMode() == BuildMode.HOT) {
          msg += "Could not use hot-swap artifacts when there is no existing session.";
        }
        else {
          msg += "Unexpected artifacts for build mode: " + buildSelection.getBuildMode();
        }
        InstantRunManager.LOG.error(msg);
        throw new ExecutionException(msg);
      }

      apks.add(new ApkInfo(artifact.file, context.getApplicationId()));
    }

    return apks;
  }
}
