package com.opencarlink.receiver;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
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

import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.opencarlink.receiver.USB_PERMISSION";
    private LinearLayout root;
    private TextView diagnostics;
    private TextView usbTestStatus;
    private UsbManager usbManager;
    private boolean receiverRegistered;
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (device == null) {
                setUsbStatus("USB 权限结果异常：系统未返回设备信息。");
            } else if (!granted) {
                setUsbStatus("未获得 USB 访问权限。你可以重新点击按钮，或检查系统权限提示。");
            } else {
                setUsbStatus("已获得 USB 访问权限，正在测试打开连接……");
                testOpenAndClose(device);
            }
            refreshDiagnostics();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
        buildUi();
        refreshDiagnostics();
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(usbPermissionReceiver); } catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
        super.onDestroy();
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
        subtitle.setText("车机兼容性诊断工具 · 版本 0.1.2");
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

        Button usbTest = new Button(this);
        usbTest.setText("申请 USB 权限并测试打开连接");
        usbTest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestUsbPermissionAndTest(); }
        });
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(8);
        root.addView(usbTest, buttonParams);

        usbTestStatus = new TextView(this);
        usbTestStatus.setText("USB 连接测试：尚未执行。此测试不会向设备发送数据。");
        usbTestStatus.setTextColor(0xFFB7C5D3);
        usbTestStatus.setTextSize(13);
        usbTestStatus.setPadding(0, dp(8), 0, dp(8));
        root.addView(usbTestStatus, matchWrap());

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

    private void requestUsbPermissionAndTest() {
        if (usbManager == null) {
            setUsbStatus("USB 服务不可用。");
            return;
        }
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            setUsbStatus("未检测到 USB 设备。请确认发送端已通过 USB 连接，并点击刷新诊断信息。");
            refreshDiagnostics();
            return;
        }
        UsbDevice device = devices.values().iterator().next();
        if (usbManager.hasPermission(device)) {
            setUsbStatus("应用已经拥有 USB 权限，正在测试打开连接……");
            testOpenAndClose(device);
            refreshDiagnostics();
            return;
        }
        try {
            Intent intent = new Intent(ACTION_USB_PERMISSION);
            intent.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags);
            setUsbStatus("已请求系统授予 USB 权限，请在系统弹窗中允许。");
            usbManager.requestPermission(device, permissionIntent);
        } catch (Exception e) {
            setUsbStatus("请求 USB 权限失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e));
        }
    }

    private void testOpenAndClose(UsbDevice device) {
        UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(device);
            if (connection == null) {
                setUsbStatus("已获 USB 权限，但 openDevice() 返回空。设备可能被系统占用，或当前 USB 连接不允许打开。");
                return;
            }
            String deviceName = device.getDeviceName();
            int interfaceCount = device.getInterfaceCount();
            setUsbStatus("USB 打开测试成功，随后已关闭连接。设备节点：" + deviceName
                    + "；接口数：" + interfaceCount
                    + "。未声明接口、未读取/写入端点、未发送任何数据。");
        } catch (SecurityException e) {
            setUsbStatus("打开 USB 失败：权限异常。请重新授权后重试。");
        } catch (Exception e) {
            setUsbStatus("打开 USB 失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e));
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { }
            }
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().length() == 0 ? "无详细错误信息" : message;
    }

    private void setUsbStatus(String message) {
        if (usbTestStatus != null) usbTestStatus.setText("USB 连接测试：" + message);
    }

    private void refreshDiagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("【系统信息】\n");
        out.append("安卓版本：").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
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
        Map<String, UsbDevice> devices = usbManager == null ? null : usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            out.append("当前未检测到 USB 设备\n");
        } else {
            out.append("检测到 USB 设备数量：").append(devices.size()).append("\n");
            for (UsbDevice device : devices.values()) {
                appendUsbDevice(out, usbManager, device);
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
                for (int i = 0; i < count; i++) codecs[i] = MediaCodecList.getCodecInfoAt(i);
            }
            int found = 0;
            for (MediaCodecInfo codec : codecs) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
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

        out.append("\n【USB 测试说明】\n");
        out.append("点击上方“申请 USB 权限并测试打开连接”后，系统可能显示授权弹窗。授权后仅调用 openDevice() 并立即关闭；不会 claim 接口、读取端点或向设备发送数据。\n");
        out.append("USB 描述符和成功打开设备都不能单独证明 ICCOA 协议存在。\n");
        diagnostics.setText(out.toString());
    }

    private void appendUsbDevice(StringBuilder out, UsbManager manager, UsbDevice device) {
        out.append("\n设备：").append(device.getDeviceName()).append("\n");
        out.append("VID：").append(hex4(device.getVendorId())).append(" (").append(device.getVendorId()).append(")\n");
        out.append("PID：").append(hex4(device.getProductId())).append(" (").append(device.getProductId()).append(")\n");
        out.append("设备类/子类/协议：")
           .append(hex2(device.getDeviceClass())).append(" / ")
           .append(hex2(device.getDeviceSubclass())).append(" / ")
           .append(hex2(device.getDeviceProtocol())).append("\n");
        out.append("接口数量：").append(device.getInterfaceCount()).append("\n");
        out.append("本应用已获 USB 访问权限：")
           .append(manager.hasPermission(device) ? "是" : "否").append("\n");

        if (Build.VERSION.SDK_INT >= 21 && manager.hasPermission(device)) {
            try {
                String manufacturer = device.getManufacturerName();
                String product = device.getProductName();
                String serial = device.getSerialNumber();
                if (manufacturer != null && manufacturer.length() > 0) out.append("USB 制造商字符串：").append(manufacturer).append("\n");
                if (product != null && product.length() > 0) out.append("USB 产品字符串：").append(product).append("\n");
                if (serial != null && serial.length() > 0) out.append("USB 序列号：").append(serial).append("\n");
            } catch (SecurityException e) {
                out.append("USB 字符串描述符：系统未授权读取\n");
            } catch (Exception e) {
                out.append("USB 字符串描述符：读取失败（").append(e.getClass().getSimpleName()).append("）\n");
            }
        }

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            out.append("  接口 #").append(i)
               .append("：Class=").append(hex2(usbInterface.getInterfaceClass()))
               .append(" Subclass=").append(hex2(usbInterface.getInterfaceSubclass()))
               .append(" Protocol=").append(hex2(usbInterface.getInterfaceProtocol()))
               .append(" 端点数=").append(usbInterface.getEndpointCount()).append("\n");
            for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(j);
                out.append("    端点 #").append(j)
                   .append(" 地址=").append(hex2(endpoint.getAddress()))
                   .append(" 方向=").append(endpoint.getDirection() == UsbConstants.USB_DIR_IN ? "IN（设备到主机）" : "OUT（主机到设备）")
                   .append(" 类型=").append(endpointType(endpoint.getType()))
                   .append(" 最大包长=").append(endpoint.getMaxPacketSize())
                   .append(" 间隔=").append(endpoint.getInterval()).append("\n");
            }
        }
    }

    private String endpointType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL: return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "ISOCHRONOUS";
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "INTERRUPT";
            default: return "未知(" + type + ")";
        }
    }

    private String hex2(int value) { return String.format(Locale.US, "0x%02X", value & 0xFF); }
    private String hex4(int value) { return String.format(Locale.US, "0x%04X", value & 0xFFFF); }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
