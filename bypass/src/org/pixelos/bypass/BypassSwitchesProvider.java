/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.bypass;

import com.android.settingslib.drawer.EntryController;
import com.android.settingslib.drawer.SwitchController;
import com.android.settingslib.drawer.SwitchesProvider;

import java.util.List;

/* Injects the bypass charging switch into the battery page of Settings. */
public class BypassSwitchesProvider extends SwitchesProvider {

    private static final String CATEGORY_BATTERY = "com.android.settings.category.ia.battery";

    @Override
    protected List<SwitchController> createSwitchControllers() {
        return List.of(new BypassSwitchController());
    }

    private final class BypassSwitchController extends SwitchController {

        @Override
        public String getSwitchKey() {
            return "bypass_charging";
        }

        @Override
        protected EntryController.MetaData getMetaData() {
            return new EntryController.MetaData(CATEGORY_BATTERY)
                    .setTitle(R.string.bypass_title)
                    .setSummary(R.string.bypass_summary);
        }

        @Override
        protected boolean isChecked() {
            return Bypass.isEnabled(getContext());
        }

        @Override
        protected boolean onCheckedChanged(boolean checked) {
            return Bypass.setEnabled(getContext(), checked);
        }

        @Override
        protected String getErrorMessage(boolean attemptedChecked) {
            return null;
        }
    }
}
