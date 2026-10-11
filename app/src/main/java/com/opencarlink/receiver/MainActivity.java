package com.opencarlink.receiver;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView diagnostics;
    private final StringBuilder log = new StringBuilder();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshDiagnostics();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(0xFF101820);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("OpenCarlinkReceiver");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Experimental head-unit diagnostics · v0.1.0");
        subtitle.setTextColor(0xFFB7C5D3);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle, matchWrap());

        Button refresh = new Button(this);
        refresh.setText("Refresh diagnostics");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshDiagnostics(); }
        });
        root.addView(refresh, matchWrap());

        diagnostics = new TextView(this);
        diagnostics.setTextColor(0xFFE6EDF3);
        diagnostics.setTextSize(14);
        diagnostics.setTextIsSelectable(true);
        diagnostics.setPadding(0, dp(14), 0, dp(14));
        root.addView(diagnostics, matchWrap());

        TextView note = new TextView(this);
        note.setText("Protocol status: not implemented. USB/Wi-Fi detection alone does not mean ICCOA Carlink is connected.");
        note.setTextColor(0xFFFFD166);
        note.setTextSize(14);
        note.setPadding(0, dp(10), 0, dp(10));
        root.addView(note, matchWrap());
    }

    private void refreshDiagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("SYSTEM\n");
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        out.append("ABI: ");
        if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null) {
            for (int i = 0; i < Build.SUPPORTED_ABIS.length; i++) {
                if (i > 0) out.append(", ");
                out.append(Build.SUPPORTED_ABIS[i]);
            }
        } else {
            out.append(Build.CPU_ABI);
        }
        out.append("\n\nUSB\n");
        boolean usbHost = getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        out.append("USB Host feature: ").append(usbHost ? "available" : "not reported").append("\n");
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        Map<String, UsbDevice> devices = usbManager == null ? null : usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            out.append("Attached USB devices: none reported\n");
        } else {
            out.append("Attached USB devices: ").append(devices.size()).append("\n");
            for (UsbDevice device : devices.values()) {
                out.append("- ").append(device.getDeviceName())
                   .append(" VID=").append(device.getVendorId())
                   .append(" PID=").append(device.getProductId())
                   .append(" interfaces=").append(device.getInterfaceCount()).append("\n");
            }
        }

        out.append("\nNETWORK\n");
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        out.append("Wi-Fi service: ").append(wifi == null ? "unavailable" : "available").append("\n");
        out.append("Wi-Fi Direct feature: ")
           .append(getPackageManager().hasSystemFeature("android.hardware.wifi.direct") ? "reported" : "not reported")
           .append("\n");
        if (wifi != null) {
            try {
                out.append("Wi-Fi enabled: ").append(wifi.isWifiEnabled()).append("\n");
            } catch (SecurityException e) {
                out.append("Wi-Fi enabled: permission/firmware restricted\n");
            }
        }

        out.append("\nVIDEO DECODERS (H.264)\n");
        try {
            MediaCodecInfo[] codecs;
            if (Build.VERSION.SDK_INT >= 21) {
                MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                codecs = codecList.getCodecInfos();
            } else {
                int count = MediaCodecList.getCodecCount();
                codecs = new MediaCodecInfo[count];
                for (int i = 0; i < count; i++) {
                    codecs[i] = MediaCodecList.getCodecInfoAt(i);
                }
            }
            int found = 0;
            for (MediaCodecInfo codec : codecs) {
                if (codec.isEncoder()) continue;
                String[] types = codec.getSupportedTypes();
                for (String type : types) {
                    if ("video/avc".equalsIgnoreCase(type)) {
                        out.append("- ").append(codec.getName()).append("\n");
                        found++;
                        break;
                    }
                }
            }
            if (found == 0) out.append("No H.264 decoder reported by MediaCodecList\n");
        } catch (Throwable error) {
            out.append("Codec enumeration failed: ").append(error.getClass().getSimpleName()).append("\n");
        }

        out.append("\nNEXT STEP\n");
        out.append("Export or copy these diagnostics when reporting device compatibility. No Carlink protocol session is attempted by this prototype.\n");
        diagnostics.setText(out.toString());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
