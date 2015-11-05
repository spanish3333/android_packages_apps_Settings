/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.settings;

import static com.android.settingslib.TetherUtil.TETHERING_INVALID;
import static com.android.settingslib.TetherUtil.TETHERING_WIFI;
import static com.android.settingslib.TetherUtil.TETHERING_USB;
import static com.android.settingslib.TetherUtil.TETHERING_BLUETOOTH;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.settings.wifi.WifiApDialog;
import com.android.settings.wifi.WifiApEnabler;
import com.android.settingslib.TetherUtil;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Locale;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pGroupList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.net.wifi.p2p.WifiP2pManager.PersistentGroupInfoListener;
import android.provider.Settings.Global;
import android.net.wifi.WifiManager;
import android.widget.Toast;
import android.preference.CheckBoxPreference;
import android.text.Html;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
/*
 * Displays preferences for Tethering.
 */
public class TetherSettings extends SettingsPreferenceFragment
        implements PersistentGroupInfoListener, DialogInterface.OnClickListener, Preference.OnPreferenceChangeListener {
    private static final String TAG = "TetherSettings";

    private static final String USB_TETHER_SETTINGS = "usb_tether_settings";
    private static final String ENABLE_WIFI_AP = "enable_wifi_ap";
    private static final String ENABLE_BLUETOOTH_TETHERING = "enable_bluetooth_tethering";
    private static final String ENABLE_P2PGO_TETHERING = "enable_p2pgo_tethering";
    private static final String TETHER_CHOICE = "TETHER_TYPE";
    private static final boolean DBG = true;

    private static final int DIALOG_AP_SETTINGS = 1;

    private SwitchPreference mUsbTether;

    private WifiApEnabler mWifiApEnabler;
    private SwitchPreference mEnableWifiAp;

    private SwitchPreference mBluetoothTether;
    private SwitchPreference mP2pGoTether;

    private BroadcastReceiver mTetherChangeReceiver;

    private String[] mUsbRegexs;

    private String[] mWifiRegexs;

    private String[] mBluetoothRegexs;
    private AtomicReference<BluetoothPan> mBluetoothPan = new AtomicReference<BluetoothPan>();

    private static final String WIFI_AP_SSID_AND_SECURITY = "wifi_ap_ssid_and_security";
    private static final int CONFIG_SUBTEXT = R.string.wifi_tether_configure_subtext;
    private static final int CONFIG_SSID = R.string.p2p_go_tether_ssid;
    private static final int CONFIG_PASSPHRASE = R.string.p2p_go_tether_passphrase;

    private String[] mSecurityType;
    private Preference mCreateNetwork;

    private WifiApDialog mDialog;
    private WifiManager mWifiManager;
    private WifiConfiguration mWifiConfig = null;
    private UserManager mUm;

    private boolean mUsbConnected;
    private boolean mMassStorageActive;

    private boolean mBluetoothEnableForTether;

    /* One of INVALID, WIFI_TETHERING, USB_TETHERING or BLUETOOTH_TETHERING */
    private int mTetherChoice = TETHERING_INVALID;

    /* Stores the package name and the class name of the provisioning app */
    private String[] mProvisionApp;
    private static final int PROVISION_REQUEST = 0;

    private boolean mUnavailable;
    //MHS P2P Go
    private WifiP2pManager mWifiP2pManager;
    private WifiP2pManager.Channel mChannel;
    private WifiP2pGroup mConnectedGroup;
    static final int ENABLE_AUTO_GO = 1;
    static final int DISABLE_AUTO_GO = 0;
    static boolean mAutoGoState = false;
    static String p2pNetworkName = null;
    static String p2pPassphrase = null;

    private SharedPreferences mPrefs;
    private static final int CONFIG_STATE_STOPPED_RESTARTING =
                                    R.string.p2p_go_tether_stopped_restarting;

    @Override
    protected int getMetricsCategory() {
        return MetricsLogger.TETHER;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if(icicle != null) {
            mTetherChoice = icicle.getInt(TETHER_CHOICE);
        }
        addPreferencesFromResource(R.xml.tether_prefs);

        mUm = (UserManager) getSystemService(Context.USER_SERVICE);

        if (mUm.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)
                || UserHandle.myUserId() != UserHandle.USER_OWNER) {
            mUnavailable = true;
            setPreferenceScreen(new PreferenceScreen(getActivity(), null));
            return;
        }

        final Activity activity = getActivity();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(activity.getApplicationContext(), mProfileServiceListener,
                    BluetoothProfile.PAN);
        }

        mEnableWifiAp =
                (SwitchPreference) findPreference(ENABLE_WIFI_AP);
        Preference wifiApSettings = findPreference(WIFI_AP_SSID_AND_SECURITY);
        mUsbTether = (SwitchPreference) findPreference(USB_TETHER_SETTINGS);
        mBluetoothTether = (SwitchPreference) findPreference(ENABLE_BLUETOOTH_TETHERING);
        mP2pGoTether = (SwitchPreference) findPreference(ENABLE_P2PGO_TETHERING);

        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        mUsbRegexs = cm.getTetherableUsbRegexs();
        mWifiRegexs = cm.getTetherableWifiRegexs();
        mBluetoothRegexs = cm.getTetherableBluetoothRegexs();

        final boolean usbAvailable = mUsbRegexs.length != 0;
        final boolean wifiAvailable = mWifiRegexs.length != 0;
        final boolean bluetoothAvailable = mBluetoothRegexs.length != 0;

        if (!usbAvailable || Utils.isMonkeyRunning()) {
            getPreferenceScreen().removePreference(mUsbTether);
        }

        if (wifiAvailable && !Utils.isMonkeyRunning()) {
            mWifiApEnabler = new WifiApEnabler(activity, mEnableWifiAp);
            initWifiTethering();
        } else {
            getPreferenceScreen().removePreference(mEnableWifiAp);
            getPreferenceScreen().removePreference(wifiApSettings);
        }

        if (!bluetoothAvailable) {
            getPreferenceScreen().removePreference(mBluetoothTether);
        } else {
            BluetoothPan pan = mBluetoothPan.get();
            if (pan != null && pan.isTetheringOn()) {
                mBluetoothTether.setChecked(true);
            } else {
                mBluetoothTether.setChecked(false);
            }
        }

        //MHS P2P GO
        mPrefs = PreferenceManager.getDefaultSharedPreferences (activity.getApplicationContext());
        mWifiP2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (mWifiP2pManager != null) {
            mChannel = mWifiP2pManager.initialize(activity, getActivity().getMainLooper(), null);
            if (mChannel == null) {
                //Failure to set up connection
                Log.e(TAG, "Failed to set up connection with wifi p2p service");
                mWifiP2pManager = null;
                getPreferenceScreen().removePreference(mP2pGoTether);
            }
        } else {
            Log.e(TAG, "mWifiP2pManager context is null !");
        }
        mProvisionApp = getResources().getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app);
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putInt(TETHER_CHOICE, mTetherChoice);
        super.onSaveInstanceState(savedInstanceState);
    }

    private void initWifiTethering() {
        final Activity activity = getActivity();
        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mWifiConfig = mWifiManager.getWifiApConfiguration();
        mSecurityType = getResources().getStringArray(R.array.wifi_ap_security);

        mCreateNetwork = findPreference(WIFI_AP_SSID_AND_SECURITY);

        if (mWifiConfig == null) {
            final String s = activity.getString(
                    com.android.internal.R.string.wifi_tether_configure_ssid_default);
            mCreateNetwork.setSummary(String.format(activity.getString(CONFIG_SUBTEXT),
                    s, mSecurityType[WifiApDialog.OPEN_INDEX]));
        } else {
            int index = WifiApDialog.getSecurityTypeIndex(mWifiConfig);
            mCreateNetwork.setSummary(String.format(activity.getString(CONFIG_SUBTEXT),
                    mWifiConfig.SSID,
                    mSecurityType[index]));
        }
    }

    private BluetoothProfile.ServiceListener mProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mBluetoothPan.set((BluetoothPan) proxy);
        }
        public void onServiceDisconnected(int profile) {
            mBluetoothPan.set(null);
        }
    };

    @Override
    public Dialog onCreateDialog(int id) {
        if (id == DIALOG_AP_SETTINGS) {
            final Activity activity = getActivity();
            mDialog = new WifiApDialog(activity, this, mWifiConfig);
            return mDialog;
        }

        return null;
    }

    private class TetherChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.ACTION_TETHER_STATE_CHANGED)) {
                // TODO - this should understand the interface types
                ArrayList<String> available = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_AVAILABLE_TETHER);
                ArrayList<String> active = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_ACTIVE_TETHER);
                ArrayList<String> errored = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_ERRORED_TETHER);
                updateState(available.toArray(new String[available.size()]),
                        active.toArray(new String[active.size()]),
                        errored.toArray(new String[errored.size()]));
            } else if (action.equals(Intent.ACTION_MEDIA_SHARED)) {
                mMassStorageActive = true;
                updateState();
            } else if (action.equals(Intent.ACTION_MEDIA_UNSHARED)) {
                mMassStorageActive = false;
                updateState();
            } else if (action.equals(UsbManager.ACTION_USB_STATE)) {
                mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
                updateState();
            } else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                if (mBluetoothEnableForTether) {
                    switch (intent
                            .getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        case BluetoothAdapter.STATE_ON:
                            BluetoothPan bluetoothPan = mBluetoothPan.get();
                            if (bluetoothPan != null) {
                                bluetoothPan.setBluetoothTethering(true);
                                mBluetoothEnableForTether = false;
                            }
                            break;

                        case BluetoothAdapter.STATE_OFF:
                        case BluetoothAdapter.ERROR:
                            mBluetoothEnableForTether = false;
                            break;

                        default:
                            // ignore transition states
                    }
                }
                updateState();
            }
            else if (action.equals(WifiP2pManager.WIFI_P2P_AUTONOMOUS_GO_STATE)) {
              int subState = -1, reason = -1;
              int tetherStatus = 0;
              final Activity activity = getActivity();
              ConnectivityManager cm =
              (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
              subState = intent.getIntExtra(WifiP2pManager.EXTRA_P2P_AUTO_GO_STATE,
                  WifiP2pManager.WIFI_P2P_AUTONOMOUS_GO_STARTED);
              reason = intent.getIntExtra(
                  WifiP2pManager.EXTRA_P2P_AUTO_GO_STATE_CHANGE_REASON,
                  WifiP2pManager.WIFI_P2P_GO_STATE_CHANGE_REASON_RADAR_DETECTED);
              if(DBG) {
                  Log.d(TAG, "SubState: " + subState + " reason: " + reason);
              }
              switch (subState) {
                case WifiP2pManager.WIFI_P2P_AUTONOMOUS_GO_STARTED:
                  // p2p-go hotspot can temporarily go offline
                  // responding to DFS events, we don't need to
                  // tamper with tethering in that case
                  if (reason == WifiP2pManager.WIFI_P2P_GO_STATE_CHANGE_REASON_DEFAULT) {
                      mAutoGoState = true;
                      tetherStatus = cm.tether(mWifiP2pManager.getP2pTetherInterface());
                      if (tetherStatus ==
                             ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                          Toast.makeText(getActivity(), R.string.p2p_go_tether_started,
                              Toast.LENGTH_SHORT).show();
                          mAutoGoState = true;
                      } else {
                          Toast.makeText(getActivity(),
                              R.string.p2p_go_hotspot_failed,
                              Toast.LENGTH_SHORT).show();
                          if(DBG) {
                             Log.d(TAG,"P2P-GO hotspot tethering failed, reason:" + tetherStatus);
                          }
                          // Since tethering failed, there is no point in
                          // retaining the p2p autonomous group
                          stopAutoGO();
                          mAutoGoState = false;
                      }
                      if (mAutoGoState == true) {
                          if (mWifiP2pManager != null) {
                              mWifiP2pManager.requestPersistentGroupInfo(mChannel, TetherSettings.this);
                          }
                      }
                    } else if ((reason ==
                                    WifiP2pManager.WIFI_P2P_GO_STATE_CHANGE_REASON_CAC_COMPLETED)
                                    || (reason ==
                                     WifiP2pManager.WIFI_P2P_GO_STATE_CHANGE_REASON_CSA_FINISHED)) {
                       Toast.makeText(getActivity(),
                           R.string.p2p_go_tether_started,
                           Toast.LENGTH_SHORT).show();
                       mAutoGoState = true;
                    }
                    if (mWifiP2pManager != null) {
                        mWifiP2pManager.requestPersistentGroupInfo(mChannel, TetherSettings.this);
                    }
                  break;
                case WifiP2pManager.WIFI_P2P_AUTONOMOUS_GO_STOPPED:
                  /* P2P Auto GO stopped */
                  if (mP2pGoTether != null) {
                      mP2pGoTether.setChecked(false);
                      mP2pGoTether.setSummary("");
                      if (activity != null) {
                          mP2pGoTether.setSummary(
                             activity.getString(R.string.p2p_go_tether_disabled));
                      } else {
                          Log.e(TAG, "**** activity is null ****");
                      }
                  }
                  if (mWifiP2pManager != null) {
                      mWifiP2pManager.disableP2pTethering();
                  }
                  mAutoGoState = false;
                  break;
                case WifiP2pManager.WIFI_P2P_AUTONOMOUS_GO_STARTING:
                  // P2P Auto GO starting. This event can come from p2p
                  // state machine when the underlying driver is performing
                  // channel availability check (CAC) on a DFS channel
                  if(DBG) {
                     Log.d(TAG, "P2P-GO MHS Starting");
                  }
                  if (mP2pGoTether != null) {
                      mP2pGoTether.setSummary("");
                      mP2pGoTether.setSummary(
                          activity.getString(R.string.p2p_go_tether_starting));
                  }
                  break;
                case WifiP2pManager.WIFI_P2P_AUTONOMOUS_GO_STOPPED_RESTARTING:
                  /* P2P Auto GO stopped, restarting */
                  if(DBG) {
                      Log.d(TAG, "P2P-GO MHS stopped, restarting");
                  }
                  mAutoGoState = false;
                  if (mP2pGoTether != null) {
                      mP2pGoTether.setChecked(false);
                      mP2pGoTether.setEnabled(false);
                      mP2pGoTether.setSummary("");
                      if (activity != null) {
                          mP2pGoTether.setSummary(
                             activity.getString(CONFIG_STATE_STOPPED_RESTARTING));
                      } else {
                          Log.e(TAG, "*** Application context is null ***");
                      }
                  }
                  break;
                default:
                  Log.e(TAG, "Invalid subState: " + subState + " in the P2P AUTO GO state");
              }
              updateState();
            }
        }
    }

    // Function to disable tethering on the interface
    private void disableTetheringForP2P() {
      int statusCode = 0;
      ConnectivityManager cm =
      (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm != null) {
          statusCode = cm.untether(mWifiP2pManager.getP2pTetherInterface());
      }
      Log.e(TAG, "Untether status code: " + statusCode);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mUnavailable) {
            TextView emptyView = (TextView) getView().findViewById(android.R.id.empty);
            getListView().setEmptyView(emptyView);
            if (emptyView != null) {
                emptyView.setText(R.string.tethering_settings_not_available);
            }
            return;
        }
        final Activity activity = getActivity();

        mMassStorageActive = Environment.MEDIA_SHARED.equals(Environment.getExternalStorageState());
        mTetherChangeReceiver = new TetherChangeReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        Intent intent = activity.registerReceiver(mTetherChangeReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_STATE);
        activity.registerReceiver(mTetherChangeReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_SHARED);
        filter.addAction(Intent.ACTION_MEDIA_UNSHARED);
        filter.addDataScheme("file");
        activity.registerReceiver(mTetherChangeReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        activity.registerReceiver(mTetherChangeReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_AUTONOMOUS_GO_STATE);
        activity.registerReceiver(mTetherChangeReceiver, filter);


        if (intent != null) mTetherChangeReceiver.onReceive(activity, intent);
        if (mWifiApEnabler != null) {
            mEnableWifiAp.setOnPreferenceChangeListener(this);
            mWifiApEnabler.resume();
        }

        if(mAutoGoState) {
            mP2pGoTether.setChecked(true);
            setAutoP2PCallSetupInfo();
        } else {
            mP2pGoTether.setChecked(false);
        }

        updateState();
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mUnavailable) {
            return;
        }
        getActivity().unregisterReceiver(mTetherChangeReceiver);
        mTetherChangeReceiver = null;
        if (mWifiApEnabler != null) {
            mEnableWifiAp.setOnPreferenceChangeListener(null);
            mWifiApEnabler.pause();
        }
    }

    private void updateState() {
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        String[] available = cm.getTetherableIfaces();
        String[] tethered = cm.getTetheredIfaces();
        String[] errored = cm.getTetheringErroredIfaces();
        updateState(available, tethered, errored);
    }

    private void updateState(String[] available, String[] tethered,
            String[] errored) {
        updateUsbState(available, tethered, errored);
        updateBluetoothState(available, tethered, errored);
    }


    private void updateUsbState(String[] available, String[] tethered,
            String[] errored) {
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean usbAvailable = mUsbConnected && !mMassStorageActive;
        int usbError = ConnectivityManager.TETHER_ERROR_NO_ERROR;
        for (String s : available) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) {
                    if (usbError == ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                        usbError = cm.getLastTetherError(s);
                    }
                }
            }
        }
        boolean usbTethered = false;
        for (String s : tethered) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) usbTethered = true;
            }
        }
        boolean usbErrored = false;
        for (String s: errored) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) usbErrored = true;
            }
        }

        if (usbTethered) {
            mUsbTether.setSummary(R.string.usb_tethering_active_subtext);
            mUsbTether.setEnabled(true);
            mUsbTether.setChecked(true);
        } else if (usbAvailable) {
            if (usbError == ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                mUsbTether.setSummary(R.string.usb_tethering_available_subtext);
            } else {
                mUsbTether.setSummary(R.string.usb_tethering_errored_subtext);
            }
            mUsbTether.setEnabled(true);
            mUsbTether.setChecked(false);
        } else if (usbErrored) {
            mUsbTether.setSummary(R.string.usb_tethering_errored_subtext);
            mUsbTether.setEnabled(false);
            mUsbTether.setChecked(false);
        } else if (mMassStorageActive) {
            mUsbTether.setSummary(R.string.usb_tethering_storage_active_subtext);
            mUsbTether.setEnabled(false);
            mUsbTether.setChecked(false);
        } else {
            mUsbTether.setSummary(R.string.usb_tethering_unavailable_subtext);
            mUsbTether.setEnabled(false);
            mUsbTether.setChecked(false);
        }
    }

    private void updateBluetoothState(String[] available, String[] tethered,
            String[] errored) {
        boolean bluetoothErrored = false;
        for (String s: errored) {
            for (String regex : mBluetoothRegexs) {
                if (s.matches(regex)) bluetoothErrored = true;
            }
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null)
            return;
        int btState = adapter.getState();
        if (btState == BluetoothAdapter.STATE_TURNING_OFF) {
            mBluetoothTether.setEnabled(false);
            mBluetoothTether.setSummary(R.string.bluetooth_turning_off);
        } else if (btState == BluetoothAdapter.STATE_TURNING_ON) {
            mBluetoothTether.setEnabled(false);
            mBluetoothTether.setSummary(R.string.bluetooth_turning_on);
        } else {
            BluetoothPan bluetoothPan = mBluetoothPan.get();
            if (btState == BluetoothAdapter.STATE_ON && bluetoothPan != null &&
                    bluetoothPan.isTetheringOn()) {
                mBluetoothTether.setChecked(true);
                mBluetoothTether.setEnabled(true);
                int bluetoothTethered = bluetoothPan.getConnectedDevices().size();
                if (bluetoothTethered > 1) {
                    String summary = getString(
                            R.string.bluetooth_tethering_devices_connected_subtext,
                            bluetoothTethered);
                    mBluetoothTether.setSummary(summary);
                } else if (bluetoothTethered == 1) {
                    mBluetoothTether.setSummary(
                            R.string.bluetooth_tethering_device_connected_subtext);
                } else if (bluetoothErrored) {
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_errored_subtext);
                } else {
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_available_subtext);
                }
            } else {
                mBluetoothTether.setEnabled(true);
                mBluetoothTether.setChecked(false);
                mBluetoothTether.setSummary(R.string.bluetooth_tethering_off_subtext);
            }
        }
    }

    public boolean onPreferenceChange(Preference preference, Object value) {
        boolean enable = (Boolean) value;

        if (enable) {
            startProvisioningIfNecessary(TETHERING_WIFI);
        } else {
            if (TetherUtil.isProvisioningNeeded(getActivity())) {
                TetherService.cancelRecheckAlarmIfNecessary(getActivity(), TETHERING_WIFI);
            }
            mWifiApEnabler.setSoftapEnabled(false);
        }
        return false;
    }

    public static boolean isProvisioningNeededButUnavailable(Context context) {
        return (TetherUtil.isProvisioningNeeded(context)
                && !isIntentAvailable(context));
    }

    private static boolean isIntentAvailable(Context context) {
        String[] provisionApp = context.getResources().getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app);
        final PackageManager packageManager = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(provisionApp[0], provisionApp[1]);

        return (packageManager.queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY).size() > 0);
    }

    private void startProvisioningIfNecessary(int choice) {
        mTetherChoice = choice;
        if (TetherUtil.isProvisioningNeeded(getActivity())) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(mProvisionApp[0], mProvisionApp[1]);
            intent.putExtra(TETHER_CHOICE, mTetherChoice);
            startActivityForResult(intent, PROVISION_REQUEST);
        } else {
            startTethering();
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == PROVISION_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                TetherService.scheduleRecheckAlarm(getActivity(), mTetherChoice);
                startTethering();
            } else {
                //BT and USB need switch turned off on failure
                //Wifi tethering is never turned on until afterwards
                switch (mTetherChoice) {
                    case TETHERING_BLUETOOTH:
                        mBluetoothTether.setChecked(false);
                        break;
                    case TETHERING_USB:
                        mUsbTether.setChecked(false);
                        break;
                }
                mTetherChoice = TETHERING_INVALID;
            }
        }
    }

    private void startTethering() {
        switch (mTetherChoice) {
            case TETHERING_WIFI:
                mWifiApEnabler.setSoftapEnabled(true);
                break;
            case TETHERING_BLUETOOTH:
                // turn on Bluetooth first
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter.getState() == BluetoothAdapter.STATE_OFF) {
                    mBluetoothEnableForTether = true;
                    adapter.enable();
                    mBluetoothTether.setSummary(R.string.bluetooth_turning_on);
                    mBluetoothTether.setEnabled(false);
                } else {
                    BluetoothPan bluetoothPan = mBluetoothPan.get();
                    if (bluetoothPan != null) bluetoothPan.setBluetoothTethering(true);
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_available_subtext);
                }
                break;
            case TETHERING_USB:
                setUsbTethering(true);
                break;
            default:
                //should not happen
                break;
        }
    }

    private void setUsbTethering(boolean enabled) {
        ConnectivityManager cm =
            (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        mUsbTether.setChecked(false);
        if (cm.setUsbTethering(enabled) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            mUsbTether.setSummary(R.string.usb_tethering_errored_subtext);
            return;
        }
        mUsbTether.setSummary("");
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference preference) {
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        if (preference == mUsbTether) {
            boolean newState = mUsbTether.isChecked();

            if (newState) {
                startProvisioningIfNecessary(TETHERING_USB);
            } else {
                if (TetherUtil.isProvisioningNeeded(getActivity())) {
                    TetherService.cancelRecheckAlarmIfNecessary(getActivity(), TETHERING_USB);
                }
                setUsbTethering(newState);
            }
        }else if (preference == mP2pGoTether)  {
                if(mP2pGoTether.isChecked()) {
                    startAutoGO();
                }else if(mP2pGoTether.isChecked()==false) {
                    stopAutoGO();
                }
         } else if (preference == mBluetoothTether) {
            boolean bluetoothTetherState = mBluetoothTether.isChecked();

            if (bluetoothTetherState) {
                startProvisioningIfNecessary(TETHERING_BLUETOOTH);
            } else {
                if (TetherUtil.isProvisioningNeeded(getActivity())) {
                    TetherService.cancelRecheckAlarmIfNecessary(getActivity(), TETHERING_BLUETOOTH);
                }
                boolean errored = false;

                String [] tethered = cm.getTetheredIfaces();
                String bluetoothIface = findIface(tethered, mBluetoothRegexs);
                if (bluetoothIface != null &&
                        cm.untether(bluetoothIface) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    errored = true;
                }

                BluetoothPan bluetoothPan = mBluetoothPan.get();
                if (bluetoothPan != null) bluetoothPan.setBluetoothTethering(false);
                if (errored) {
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_errored_subtext);
                } else {
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_off_subtext);
                }
            }
        } else if (preference == mCreateNetwork) {
            showDialog(DIALOG_AP_SETTINGS);
        }

        return super.onPreferenceTreeClick(screen, preference);
    }

    private static String findIface(String[] ifaces, String[] regexes) {
        for (String iface : ifaces) {
            for (String regex : regexes) {
                if (iface.matches(regex)) {
                    return iface;
                }
            }
        }
        return null;
    }

    public void onClick(DialogInterface dialogInterface, int button) {
        if (button == DialogInterface.BUTTON_POSITIVE) {
            mWifiConfig = mDialog.getConfig();
            if (mWifiConfig != null) {
                /**
                 * if soft AP is stopped, bring up
                 * else restart with new config
                 * TODO: update config on a running access point when framework support is added
                 */
                if (mWifiManager.getWifiApState() == WifiManager.WIFI_AP_STATE_ENABLED) {
                    mWifiManager.setWifiApEnabled(null, false);
                    mWifiManager.setWifiApEnabled(mWifiConfig, true);
                } else {
                    mWifiManager.setWifiApConfiguration(mWifiConfig);
                }
                int index = WifiApDialog.getSecurityTypeIndex(mWifiConfig);
                mCreateNetwork.setSummary(String.format(getActivity().getString(CONFIG_SUBTEXT),
                        mWifiConfig.SSID,
                        mSecurityType[index]));
            }
        }
    }

    @Override
    public int getHelpResource() {
        return R.string.help_url_tether;
    }

    /* Starts P2P group for Mobile hotspot*/
    private void startAutoGO() {
        if (mWifiP2pManager != null ) {
            mWifiP2pManager.enableP2pTethering();
            mWifiP2pManager.createGroup(mChannel,
                    new WifiP2pManager.ActionListener() {
                    public void onSuccess() {
                    mP2pGoTether.setSummary(R.string.p2p_go_getting_network_info_subtext);
                    mP2pGoTether.setChecked(false);
                    }
                    public void onFailure(int reason) {
                    if(DBG){
                    Log.e(TAG, "Failed to start P2P-GO MHS with reason "
                        + reason + ".");
                    }
                    mAutoGoState=false;
                    mP2pGoTether.setChecked(false);
                    mP2pGoTether.setSummary(R.string.p2p_go_hotspot_failed);
                    }
                    });
        }
    }

    @Override
    public void onPersistentGroupInfoAvailable(WifiP2pGroupList groups) {
        if (mAutoGoState) {
            for (WifiP2pGroup group: groups.getGroupList()) {
                 String networkName = group.getNetworkName();
                 String passPhrase = group.getPassphrase();
                 if(DBG) {
                    Log.d(TAG,"Group Info Network Name: " + networkName);
                    Log.d(TAG,"Group Info Passphrase: " + passPhrase);
                 }
                 SharedPreferences.Editor ed = mPrefs.edit();
                 ed.putString("auto_network_name", networkName);
                 ed.putString("auto_passphrase", passPhrase);
                 p2pNetworkName=networkName;
                 p2pPassphrase=passPhrase;
                 ed.commit();
                 setAutoP2PCallSetupInfo();
        }
        // There is no point in enabling the tethering UI before this 	911
        if (mP2pGoTether != null) {
            mP2pGoTether.setEnabled(true);
            mP2pGoTether.setChecked(true);
        }
        }
    }

    private void setAutoP2PCallSetupInfo() {
        final Activity activity = getActivity();
        String networkName=p2pNetworkName;
        String passPhrase=p2pPassphrase;
        if(networkName != null && passPhrase != null) {
            mP2pGoTether.setSummary(Html.fromHtml("<font color='blue'>"
                                    +activity.getString(CONFIG_SSID)+":</font>"
                                    +networkName+"<br>"+"<font color='blue'>"
                                    +activity.getString(CONFIG_PASSPHRASE)+":</font>"
                                    +passPhrase));
        }
    }

    /* stops the Auto P2P Go group for mobile hotspot */
    private void stopAutoGO() {
        if (DBG) {
            Log.d(TAG, "Stopping Autonomous GO");
        }
        if (mWifiP2pManager != null) {
            /* Stop tethering first before removing the group */
            disableTetheringForP2P();
            mWifiP2pManager.removeGroup(mChannel,
                    new WifiP2pManager.ActionListener() {
                    public void onSuccess() {
                      if (DBG) {
                        Log.d(TAG, "Successfully stopped P2P-GO MHS");
                      }
                    }
                    public void onFailure(int reason) {
                    Log.e(TAG,  "Failed to stop P2P-GO MHS with reason " + reason + ".");
                    }
                    });

        }
    }
}
