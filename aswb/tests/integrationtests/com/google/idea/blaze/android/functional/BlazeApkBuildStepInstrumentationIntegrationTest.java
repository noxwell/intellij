/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.functional;

import static com.google.common.truth.Truth.assertThat;
import static com.google.idea.blaze.android.targetmapbuilder.NbAndroidInstrumentationTestTarget.android_instrumentation_test;
import static com.google.idea.blaze.android.targetmapbuilder.NbAndroidTarget.android_binary;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.tools.idea.run.ApkProvisionException;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.rules.android.deployinfo.AndroidDeployInfoOuterClass.AndroidDeployInfo;
import com.google.idea.blaze.android.BlazeAndroidIntegrationTestCase;
import com.google.idea.blaze.android.MockSdkUtil;
import com.google.idea.blaze.android.run.deployinfo.BlazeAndroidDeployInfo;
import com.google.idea.blaze.android.run.deployinfo.BlazeApkDeployInfoProtoHelper;
import com.google.idea.blaze.android.run.deployinfo.BlazeApkDeployInfoProtoHelper.GetDeployInfoException;
import com.google.idea.blaze.android.run.runner.BlazeAndroidDeviceSelector.DeviceSession;
import com.google.idea.blaze.android.run.runner.BlazeApkBuildStepInstrumentation;
import com.google.idea.blaze.android.run.runner.BlazeApkBuildStepInstrumentation.InstrumentorToTarget;
import com.google.idea.blaze.base.async.process.ExternalTask;
import com.google.idea.blaze.base.async.process.ExternalTaskProvider;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Integration tests for {@link
 * com.google.idea.blaze.android.run.runner.BlazeApkBuildStepInstrumentation}
 */
@RunWith(JUnit4.class)
public class BlazeApkBuildStepInstrumentationIntegrationTest
    extends BlazeAndroidIntegrationTestCase {

  @Before
  public void setup() {
    setProjectView(
        "directories:",
        "  java/com/foo/app",
        "targets:",
        "  //java/com/foo/app:instrumentation_test",
        "android_sdk_platform: android-27");
    MockSdkUtil.registerSdk(workspace, "27");

    workspace.createFile(
        new WorkspacePath("java/com/foo/app/MainActivity.java"),
        "package com.foo.app",
        "import android.app.Activity;",
        "public class MainActivity extends Activity {}");

    workspace.createFile(
        new WorkspacePath("java/com/foo/app/Test.java"),
        "package com.foo.app",
        "public class Test {}");

    setTargetMap(
        android_binary("//java/com/foo/app:app").src("MainActivity.java"),
        android_binary("//java/com/foo/app:test_app")
            .src("Test.java")
            .instruments("//java/com/foo/app:app"),
        android_instrumentation_test("//java/com/foo/app:instrumentation_test")
            .test_app("//java/com/foo/app:test_app"));
    runFullBlazeSync();
  }

  @Test
  public void deployInfoBuiltCorrectly() throws GetDeployInfoException, ApkProvisionException {
    Label testTarget = Label.create("//java/com/foo/app:instrumentation_test");
    Label instrumentorTarget = Label.create("//java/com/foo/app:test_app");
    Label appTarget = Label.create("//java/com/foo/app:app");
    BlazeContext context = new BlazeContext();
    ImmutableList<String> blazeFlags = ImmutableList.of("some_blaze_flag", "some_other_flag");

    // Setup interceptor for fake running of blaze commands and capture details.
    ExternalTaskInterceptor externalTaskInterceptor = new ExternalTaskInterceptor();
    registerApplicationService(ExternalTaskProvider.class, externalTaskInterceptor);

    // Return fake deploy info proto and mocked deploy info data object.
    BlazeApkDeployInfoProtoHelper helper = mock(BlazeApkDeployInfoProtoHelper.class);

    AndroidDeployInfo fakeInstrumentorProto = AndroidDeployInfo.newBuilder().build();
    AndroidDeployInfo fakeAppProto = AndroidDeployInfo.newBuilder().build();
    BlazeAndroidDeployInfo mockDeployInfo = mock(BlazeAndroidDeployInfo.class);
    when(helper.readDeployInfoProtoForTarget(
            eq(instrumentorTarget), any(BuildResultHelper.class), any()))
        .thenReturn(fakeInstrumentorProto);
    when(helper.readDeployInfoProtoForTarget(eq(appTarget), any(BuildResultHelper.class), any()))
        .thenReturn(fakeAppProto);
    when(helper.extractInstrumentationTestDeployInfoAndInvalidateManifests(
            eq(getProject()),
            eq(new File(getExecRoot())),
            eq(fakeInstrumentorProto),
            eq(fakeAppProto)))
        .thenReturn(mockDeployInfo);

    // Perform
    BlazeApkBuildStepInstrumentation buildStep =
        new BlazeApkBuildStepInstrumentation(getProject(), testTarget, blazeFlags, helper);
    buildStep.build(context, new DeviceSession(null, null, null));

    // Verify
    assertThat(buildStep.getDeployInfo()).isNotNull();
    assertThat(buildStep.getDeployInfo()).isEqualTo(mockDeployInfo);
    assertThat(externalTaskInterceptor.context).isEqualTo(context);
    assertThat(externalTaskInterceptor.command).contains(instrumentorTarget.toString());
    assertThat(externalTaskInterceptor.command).contains(appTarget.toString());
    assertThat(externalTaskInterceptor.command).contains("--output_groups=+android_deploy_info");
    assertThat(externalTaskInterceptor.command).containsAllIn(blazeFlags);
  }

  @Test
  public void exceptionDuringDeployInfoExtraction()
      throws GetDeployInfoException, ApkProvisionException {
    Label testTarget = Label.create("//java/com/foo/app:instrumentation_test");
    Label instrumentorTarget = Label.create("//java/com/foo/app:test_app");
    Label appTarget = Label.create("//java/com/foo/app:app");
    BlazeContext context = new BlazeContext();

    // Make blaze command invocation always pass.
    registerApplicationService(ExternalTaskProvider.class, builder -> scopes -> 0);

    // Return fake deploy info proto and mocked deploy info data object.
    BlazeApkDeployInfoProtoHelper helper = mock(BlazeApkDeployInfoProtoHelper.class);

    AndroidDeployInfo fakeInstrumentorProto = AndroidDeployInfo.newBuilder().build();
    AndroidDeployInfo fakeAppProto = AndroidDeployInfo.newBuilder().build();
    when(helper.readDeployInfoProtoForTarget(
            eq(instrumentorTarget), any(BuildResultHelper.class), any()))
        .thenReturn(fakeInstrumentorProto);
    when(helper.readDeployInfoProtoForTarget(eq(appTarget), any(BuildResultHelper.class), any()))
        .thenReturn(fakeAppProto);
    when(helper.extractInstrumentationTestDeployInfoAndInvalidateManifests(
            any(), any(), any(), any()))
        .thenThrow(new GetDeployInfoException("Fake Exception"));

    // Perform
    BlazeApkBuildStepInstrumentation buildStep =
        new BlazeApkBuildStepInstrumentation(getProject(), testTarget, ImmutableList.of(), helper);
    buildStep.build(context, new DeviceSession(null, null, null));

    // Verify
    assertThat(context.hasErrors()).isTrue();
  }

  @Test
  public void badDeployInfo() throws GetDeployInfoException, ApkProvisionException {
    Label testTarget = Label.create("//java/com/foo/app:instrumentation_test");
    Label instrumentorTarget = Label.create("//java/com/foo/app:test_app");
    Label appTarget = Label.create("//java/com/foo/app:app");
    BlazeContext context = new BlazeContext();

    // Return a non-zero value to indicate blaze command run failure.
    registerApplicationService(ExternalTaskProvider.class, builder -> scopes -> 1337);

    // Return fake deploy info proto and mocked deploy info data object.
    BlazeApkDeployInfoProtoHelper helper = mock(BlazeApkDeployInfoProtoHelper.class);

    AndroidDeployInfo fakeInstrumentorProto = AndroidDeployInfo.newBuilder().build();
    AndroidDeployInfo fakeAppProto = AndroidDeployInfo.newBuilder().build();
    BlazeAndroidDeployInfo mockDeployInfo = mock(BlazeAndroidDeployInfo.class);
    when(helper.readDeployInfoProtoForTarget(
            eq(instrumentorTarget), any(BuildResultHelper.class), any()))
        .thenReturn(fakeInstrumentorProto);
    when(helper.readDeployInfoProtoForTarget(eq(appTarget), any(BuildResultHelper.class), any()))
        .thenReturn(fakeAppProto);
    when(helper.extractInstrumentationTestDeployInfoAndInvalidateManifests(
            eq(getProject()),
            eq(new File(getExecRoot())),
            eq(fakeInstrumentorProto),
            eq(fakeAppProto)))
        .thenReturn(mockDeployInfo);

    // Perform
    BlazeApkBuildStepInstrumentation buildStep =
        new BlazeApkBuildStepInstrumentation(getProject(), testTarget, ImmutableList.of(), helper);
    buildStep.build(context, new DeviceSession(null, null, null));

    // Verify
    assertThat(context.hasErrors()).isTrue();
  }

  /** Saves the latest blaze command and context for later verification. */
  private static class ExternalTaskInterceptor implements ExternalTaskProvider {
    ImmutableList<String> command;
    BlazeContext context;

    @Override
    public ExternalTask build(ExternalTask.Builder builder) {
      command = builder.command.build();
      context = builder.context;
      return scopes -> 0;
    }
  }

  @Test
  public void findInstrumentorAndTestTargets() {
    BlazeApkBuildStepInstrumentation buildStep =
        new BlazeApkBuildStepInstrumentation(
            getProject(),
            Label.create("//java/com/foo/app:instrumentation_test"),
            ImmutableList.of());

    InstrumentorToTarget pair =
        buildStep.getInstrumentorToTargetPair(
            new BlazeContext(),
            BlazeProjectDataManager.getInstance(getProject()).getBlazeProjectData());
    assertThat(pair.instrumentor).isEqualTo(Label.create("//java/com/foo/app:test_app"));
    assertThat(pair.target).isEqualTo(Label.create("//java/com/foo/app:app"));
  }
}
