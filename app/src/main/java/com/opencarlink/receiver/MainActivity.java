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
        subtitle.setText("车机兼容性诊断工具 · 版本 0.1.0");
        subtitle.setTextColor(0xFFB7C5D3);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle, matchWrap());

        Button refresh = new Button(this);
        refresh.setText("刷新诊断信息");
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
        note.setText("协议状态：尚未实现。检测到 USB 或 Wi-Fi 并不代表已连接 ICCOA Carlink。");
        note.setTextColor(0xFFFFD166);
        note.setTextSize(14);
        note.setPadding(0, dp(10), 0, dp(10));
        root.addView(note, matchWrap());
    }

    private void refreshDiagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("【系统信息】\n");
        out.append("安卓版本：").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("设备型号：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        out.append("处理器架构：");
        if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null) {
            for (int i = 0; i < Build.SUPPORTED_ABIS.length; i++) {
                if (i > 0) out.append(", ");
                out.append(Build.SUPPORTED_ABIS[i]);
            }
        } else {
            out.append(Build.CPU_ABI);
        }
        out.append("\n\n【USB 连接】\n");
        boolean usbHost = getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        out.append("USB 主机功能：").append(usbHost ? "支持" : "系统未报告").append("\n");
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        Map<String, UsbDevice> devices = usbManager == null ? null : usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            out.append("当前未检测到 USB 设备\n");
        } else {
            out.append("检测到 USB 设备数量：").append(devices.size()).append("\n");
            for (UsbDevice device : devices.values()) {
                out.append("- ").append(device.getDeviceName())
                   .append(" VID=").append(device.getVendorId())
                   .append(" PID=").append(device.getProductId())
                   .append(" 接口数量=").append(device.getInterfaceCount()).append("\n");
            }
        }

        out.append("\n【无线网络】\n");
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        out.append("Wi-Fi 服务：").append(wifi == null ? "不可用" : "支持").append("\n");
        out.append("Wi-Fi Direct 功能：")
           .append(getPackageManager().hasSystemFeature("android.hardware.wifi.direct") ? "系统已报告" : "系统未报告")
           .append("\n");
        if (wifi != null) {
            try {
                out.append("Wi-Fi 已开启：").append(wifi.isWifiEnabled()).append("\n");
            } catch (SecurityException e) {
                out.append("Wi-Fi 已开启：权限或固件限制，无法读取\n");
            }
        }

        out.append("\n【视频解码能力（H.264）】\n");
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
            if (found == 0) out.append("系统未报告可用的 H.264 解码器\n");
        } catch (Throwable error) {
            out.append("读取解码器信息失败：").append(error.getClass().getSimpleName()).append("\n");
        }

        out.append("\n【下一步】\n");
        out.append("如需反馈设备兼容性，请复制以上诊断信息。目前版本尚未尝试建立 Carlink 协议连接。\n");
        diagnostics.setText(out.toString());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
