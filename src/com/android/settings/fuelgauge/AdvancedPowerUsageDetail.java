/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settings.fuelgauge;

import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.Utils;
import com.android.settings.applications.appinfo.AppButtonsPreferenceController;
import com.android.settings.applications.appinfo.ButtonActionDialogFragment;
import com.android.settings.core.InstrumentedPreferenceFragment;
import com.android.settings.core.SubSettingLauncher;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.widget.EntityHeaderController;
import com.android.settingslib.applications.AppUtils;
import com.android.settingslib.applications.ApplicationsState;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.utils.StringUtil;
import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.RadioButtonPreference;

import java.util.ArrayList;
import java.util.List;

/**
 * Power usage detail fragment for each app, this fragment contains
 *
 * 1. Detail battery usage information for app(i.e. usage time, usage amount)
 * 2. Battery related controls for app(i.e uninstall, force stop)
 */
public class AdvancedPowerUsageDetail extends DashboardFragment implements
        ButtonActionDialogFragment.AppButtonsDialogListener,
        RadioButtonPreference.OnClickListener {

    public static final String TAG = "AdvancedPowerDetail";
    public static final String EXTRA_UID = "extra_uid";
    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";
    public static final String EXTRA_FOREGROUND_TIME = "extra_foreground_time";
    public static final String EXTRA_BACKGROUND_TIME = "extra_background_time";
    public static final String EXTRA_LABEL = "extra_label";
    public static final String EXTRA_ICON_ID = "extra_icon_id";
    public static final String EXTRA_POWER_USAGE_PERCENT = "extra_power_usage_percent";
    public static final String EXTRA_POWER_USAGE_AMOUNT = "extra_power_usage_amount";

    private static final String KEY_PREF_HEADER = "header_view";
    private static final String KEY_PREF_UNRESTRICTED = "unrestricted_pref";
    private static final String KEY_PREF_OPTIMIZED = "optimized_pref";
    private static final String KEY_PREF_RESTRICTED = "restricted_pref";
    private static final String KEY_FOOTER_PREFERENCE = "app_usage_footer_preference";

    private static final int REQUEST_UNINSTALL = 0;
    private static final int REQUEST_REMOVE_DEVICE_ADMIN = 1;

    @VisibleForTesting
    LayoutPreference mHeaderPreference;
    @VisibleForTesting
    ApplicationsState mState;
    @VisibleForTesting
    ApplicationsState.AppEntry mAppEntry;
    @VisibleForTesting
    BatteryUtils mBatteryUtils;
    @VisibleForTesting
    BatteryOptimizeUtils mBatteryOptimizeUtils;

    @VisibleForTesting
    Preference mFooterPreference;
    @VisibleForTesting
    RadioButtonPreference mRestrictedPreference;
    @VisibleForTesting
    RadioButtonPreference mOptimizePreference;
    @VisibleForTesting
    RadioButtonPreference mUnrestrictedPreference;
    private AppButtonsPreferenceController mAppButtonsPreferenceController;
    private UnrestrictedPreferenceController mUnrestrictedPreferenceController;
    private OptimizedPreferenceController mOptimizedPreferenceController;
    private RestrictedPreferenceController mRestrictedPreferenceController;

    private String mPackageName;

    /**
     * Launches battery details page for an individual battery consumer.
     */
    public static void startBatteryDetailPage(Activity caller,
            InstrumentedPreferenceFragment fragment, BatteryEntry entry, String usagePercent) {
        final Bundle args = new Bundle();
        final long foregroundTimeMs = entry.getTimeInForegroundMs();
        final long backgroundTimeMs = entry.getTimeInBackgroundMs();
        final String packageName = entry.getDefaultPackageName();
        if (packageName == null) {
            // populate data for system app
            args.putString(EXTRA_LABEL, entry.getLabel());
            args.putInt(EXTRA_ICON_ID, entry.iconId);
            args.putString(EXTRA_PACKAGE_NAME, null);
        } else {
            // populate data for normal app
            args.putString(EXTRA_PACKAGE_NAME, packageName);
        }

        args.putInt(EXTRA_UID, entry.getUid());
        args.putLong(EXTRA_BACKGROUND_TIME, backgroundTimeMs);
        args.putLong(EXTRA_FOREGROUND_TIME, foregroundTimeMs);
        args.putString(EXTRA_POWER_USAGE_PERCENT, usagePercent);
        args.putInt(EXTRA_POWER_USAGE_AMOUNT, (int) entry.getConsumedPower());

        new SubSettingLauncher(caller)
                .setDestination(AdvancedPowerUsageDetail.class.getName())
                .setTitleRes(R.string.battery_details_title)
                .setArguments(args)
                .setSourceMetricsCategory(fragment.getMetricsCategory())
                .setUserHandle(new UserHandle(getUserIdToLaunchAdvancePowerUsageDetail(entry)))
                .launch();
    }

    private static @UserIdInt int getUserIdToLaunchAdvancePowerUsageDetail(
            BatteryEntry batteryEntry) {
        if (batteryEntry.isUserEntry()) {
            return ActivityManager.getCurrentUser();
        }
        return UserHandle.getUserId(batteryEntry.getUid());
    }

    public static void startBatteryDetailPage(Activity caller,
            InstrumentedPreferenceFragment fragment, String packageName) {
        final Bundle args = new Bundle(3);
        final PackageManager packageManager = caller.getPackageManager();
        args.putString(EXTRA_PACKAGE_NAME, packageName);
        args.putString(EXTRA_POWER_USAGE_PERCENT, Utils.formatPercentage(0));
        try {
            args.putInt(EXTRA_UID, packageManager.getPackageUid(packageName, 0 /* no flag */));
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Cannot find package: " + packageName, e);
        }

        new SubSettingLauncher(caller)
                .setDestination(AdvancedPowerUsageDetail.class.getName())
                .setTitleRes(R.string.battery_details_title)
                .setArguments(args)
                .setSourceMetricsCategory(fragment.getMetricsCategory())
                .launch();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        mState = ApplicationsState.getInstance(getActivity().getApplication());
        mBatteryUtils = BatteryUtils.getInstance(getContext());
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mPackageName = getArguments().getString(EXTRA_PACKAGE_NAME);
        mFooterPreference = findPreference(KEY_FOOTER_PREFERENCE);
        mHeaderPreference = (LayoutPreference) findPreference(KEY_PREF_HEADER);

        mUnrestrictedPreference  = findPreference(KEY_PREF_UNRESTRICTED);
        mOptimizePreference  = findPreference(KEY_PREF_OPTIMIZED);
        mRestrictedPreference  = findPreference(KEY_PREF_RESTRICTED);
        mUnrestrictedPreference.setOnClickListener(this);
        mOptimizePreference.setOnClickListener(this);
        mRestrictedPreference.setOnClickListener(this);

        mBatteryOptimizeUtils = new BatteryOptimizeUtils(
                getContext(), getArguments().getInt(EXTRA_UID), mPackageName);

        if (mPackageName != null) {
            mAppEntry = mState.getEntry(mPackageName, UserHandle.myUserId());
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        initHeader();
        initPreference();
    }

    @VisibleForTesting
    void initHeader() {
        final View appSnippet = mHeaderPreference.findViewById(R.id.entity_header);
        final Activity context = getActivity();
        final Bundle bundle = getArguments();
        EntityHeaderController controller = EntityHeaderController
                .newInstance(context, this, appSnippet)
                .setRecyclerView(getListView(), getSettingsLifecycle())
                .setButtonActions(EntityHeaderController.ActionType.ACTION_NONE,
                        EntityHeaderController.ActionType.ACTION_NONE);

        if (mAppEntry == null) {
            controller.setLabel(bundle.getString(EXTRA_LABEL));

            final int iconId = bundle.getInt(EXTRA_ICON_ID, 0);
            if (iconId == 0) {
                controller.setIcon(context.getPackageManager().getDefaultActivityIcon());
            } else {
                controller.setIcon(context.getDrawable(bundle.getInt(EXTRA_ICON_ID)));
            }
        } else {
            mState.ensureIcon(mAppEntry);
            controller.setLabel(mAppEntry);
            controller.setIcon(mAppEntry);
            controller.setIsInstantApp(AppUtils.isInstant(mAppEntry.info));
        }

        final long foregroundTimeMs = bundle.getLong(EXTRA_FOREGROUND_TIME);
        final long backgroundTimeMs = bundle.getLong(EXTRA_BACKGROUND_TIME);
        final long totalTimeMs = foregroundTimeMs + backgroundTimeMs;
        //TODO(b/178197718) Refine the layout
        controller.setSummary(TextUtils.expandTemplate(
                getText(R.string.battery_total_and_background_usage),
                StringUtil.formatElapsedTime(
                        getContext(),
                        totalTimeMs,
                        /* withSeconds */ false,
                        /* collapseTimeUnit */ false),
                StringUtil.formatElapsedTime(
                        getContext(),
                        backgroundTimeMs,
                        /* withSeconds */ false,
                        /* collapseTimeUnit */ false)));

        controller.done(context, true /* rebindActions */);
    }

    @VisibleForTesting
    void initPreference() {
        final Context context = getContext();
        final String stateString;
        final String footerString;
        //TODO(b/178197718) Update strings
        if (!mBatteryOptimizeUtils.isValidPackageName()) {
            //Present optimized only string when the package name is invalid.
            stateString = context.getString(R.string.manager_battery_usage_optimized_title);
            footerString = context.getString(
                    R.string.manager_battery_usage_footer_limited, stateString);
        } else if (mBatteryOptimizeUtils.isSystemOrDefaultApp()) {
            //Present unrestricted only string when the package is system or default active app.
            stateString = context.getString(R.string.manager_battery_usage_unrestricted_title);
            footerString = context.getString(
                    R.string.manager_battery_usage_footer_limited, stateString);
        } else {
            //Present default string to normal app.
            footerString = context.getString(R.string.manager_battery_usage_footer);

        }
        mFooterPreference.setTitle(Html.fromHtml(footerString, Html.FROM_HTML_MODE_COMPACT));
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.FUELGAUGE_POWER_USAGE_DETAIL;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.power_usage_detail;
    }

    @Override
    protected List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        final List<AbstractPreferenceController> controllers = new ArrayList<>();
        final Bundle bundle = getArguments();
        final int uid = bundle.getInt(EXTRA_UID, 0);
        final String packageName = bundle.getString(EXTRA_PACKAGE_NAME);

        mAppButtonsPreferenceController = new AppButtonsPreferenceController(
                (SettingsActivity) getActivity(), this, getSettingsLifecycle(), packageName, mState,
                REQUEST_UNINSTALL, REQUEST_REMOVE_DEVICE_ADMIN);
        controllers.add(mAppButtonsPreferenceController);
        mUnrestrictedPreferenceController =
                new UnrestrictedPreferenceController(context, uid, packageName);
        mOptimizedPreferenceController =
                new OptimizedPreferenceController(context, uid, packageName);
        mRestrictedPreferenceController =
                new RestrictedPreferenceController(context, uid, packageName);
        controllers.add(mUnrestrictedPreferenceController);
        controllers.add(mOptimizedPreferenceController);
        controllers.add(mRestrictedPreferenceController);

        return controllers;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mAppButtonsPreferenceController != null) {
            mAppButtonsPreferenceController.handleActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void handleDialogClick(int id) {
        if (mAppButtonsPreferenceController != null) {
            mAppButtonsPreferenceController.handleDialogClick(id);
        }
    }

    @Override
    public void onRadioButtonClicked(RadioButtonPreference selected) {
        updatePreferenceState(mUnrestrictedPreference, selected.getKey());
        updatePreferenceState(mOptimizePreference, selected.getKey());
        updatePreferenceState(mRestrictedPreference, selected.getKey());
    }

    private void updatePreferenceState(RadioButtonPreference preference, String selectedKey) {
        preference.setChecked(selectedKey.equals(preference.getKey()));
    }
}
