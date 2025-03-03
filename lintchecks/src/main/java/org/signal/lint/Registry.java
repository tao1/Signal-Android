package org.signal.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.client.api.Vendor;
import com.android.tools.lint.detector.api.ApiKt;
import com.android.tools.lint.detector.api.Issue;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public final class Registry extends IssueRegistry {

  @Nullable @Override public Vendor getVendor() {
    return new Vendor("signal", "signal", "https://github.com/signalapp/Signal-Android/issues", "https://support.signal.org/");
  }

  @Override
  public List<Issue> getIssues() {
    return Arrays.asList(SignalLogDetector.LOG_NOT_SIGNAL,
                         SignalLogDetector.LOG_NOT_APP,
                         SignalLogDetector.INLINE_TAG,
                         VersionCodeDetector.VERSION_CODE_USAGE,
                         AlertDialogBuilderDetector.ALERT_DIALOG_BUILDER_USAGE);
  }

  @Override
  public int getApi() {
    return ApiKt.CURRENT_API;
  }
}
