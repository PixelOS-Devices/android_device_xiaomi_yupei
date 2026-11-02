/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.xiaomi.touchservice.R;

import java.util.List;

public class StylusSettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new StylusSettingsFragment())
                    .commit();
        }
    }

    public static class StylusSettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            // the gestures are read before the user unlocks too
            getPreferenceManager().setStorageDeviceProtected();
            setPreferencesFromResource(R.xml.stylus_settings, rootKey);

            ListPreference notesApp = findPreference(PenGestures.NOTES_APP);
            PackageManager pm = requireContext().getPackageManager();
            List<ResolveInfo> apps = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            apps.sort(new ResolveInfo.DisplayNameComparator(pm));

            CharSequence[] names = new CharSequence[apps.size()];
            CharSequence[] packages = new CharSequence[apps.size()];
            for (int i = 0; i < apps.size(); i++) {
                names[i] = apps.get(i).loadLabel(pm);
                packages[i] = apps.get(i).activityInfo.packageName;
            }
            notesApp.setEntries(names);
            notesApp.setEntryValues(packages);
        }
    }
}
