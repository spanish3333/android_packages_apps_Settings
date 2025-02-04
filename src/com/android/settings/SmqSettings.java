/*
Copyright (c) 2015, The Linux Foundation. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    * Neither the name of The Linux Foundation nor the names of its
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.android.settings;

import android.content.Context;
import android.content.SharedPreferences;

public class SmqSettings {

    /**
     * The application context.
     */
    private Context mContext;
    private SharedPreferences mSmqPreferences;

    /**
     * SMQ preferences key.
     */
    public static final String SMQ_KEY_VALUE = "app_status";

    /**
     * Shared preferences name.
     */
    public static final String SMQ_PREFS_NAME = "smqpreferences";

    public SmqSettings(final Context context) {
        mContext = context;
        new DBReadAsyncTask(mContext).execute();
        mSmqPreferences = mContext.getSharedPreferences(
                SMQ_PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void onResume() {
        new DBReadAsyncTask(mContext).execute();
    }

    public boolean isShowSmqSettings() {
        final int iShowSmq = mSmqPreferences.getInt(SMQ_KEY_VALUE, 0);
        final boolean showSmq = iShowSmq > 0 ? true : false;
        return showSmq;
    }

}
