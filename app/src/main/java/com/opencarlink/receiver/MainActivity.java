package com.opencarlink.receiver;

import com.opencarlink.receiver.protocol.AoaTransportHeaderProbe;
import com.opencarlink.receiver.protocol.AoaRecordReader;
import com.opencarlink.receiver.protocol.AoaUsbExactLengthSource;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.util.Enumeration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.opencarlink.receiver.USB_PERMISSION";
    private static final String ACTION_AOA_PERMISSION = "com.opencarlink.receiver.AOA_PERMISSION";
    private static final String ACTION_AOA_DATA_PERMISSION = "com.opencarlink.receiver.AOA_DATA_PERMISSION";
    private static final String ACTION_AOA_FRAME_PERMISSION = "com.opencarlink.receiver.AOA_FRAME_PERMISSION";
    private static final String ACTION_AOA_SOCKET_PROBE_PERMISSION = "com.opencarlink.receiver.AOA_SOCKET_PROBE_PERMISSION";
    private static final int AOA_VENDOR_ID = 0x18D1;
    private static final int AOA_PRODUCT_ID = 0x2D00;
    private LinearLayout root;
    private TextView diagnostics;
    private TextView diagnosticEventLog;
    private CheckBox phoneDebugCheck;
    private CheckBox phoneUnlockedCheck;
    private boolean diagnosticSessionActive;
    private String diagnosticSessionId = "未开始";
    private final StringBuilder diagnosticLog = new StringBuilder();
    private static final String PREFS_DIAGNOSTICS = "usb_aoa_diagnostics";
    private static final String PREF_EVENT_LOG = "event_log";
    private TextView usbTestStatus;
    private EditText aoaManufacturerInput, aoaModelInput, aoaDescriptionInput, aoaVersionInput, aoaUriInput;
    private EditText carModIdInput, carIdInput, carShortNameInput, usbPinCodeInput, carProtocolVersionInput, carVendorDataInput;
    private UsbManager usbManager;
    private boolean receiverRegistered;
    // USB Bulk IN is a single stream; never allow multiple diagnostic workers to consume it concurrently.
    private final AtomicBoolean usbReadOperationActive = new AtomicBoolean(false);
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                boolean attached = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action);
                UsbDevice hotplugDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                String deviceSummary = hotplugDevice == null ? "设备信息不可用"
                        : "VID:PID=" + hex4(hotplugDevice.getVendorId()) + ":" + hex4(hotplugDevice.getProductId())
                        + "，接口数=" + hotplugDevice.getInterfaceCount();
                recordEvent(attached ? "USB_ATTACH" : "USB_DETACH",
                        (attached ? "系统广播：USB 设备已接入；" : "系统广播：USB 设备已断开；")
                                + deviceSummary + "。诊断会话保持开启，以便捕获 AOA 重新枚举后的设备。");
                setUsbStatus(attached ? "检测到 USB 设备接入：" + deviceSummary
                        : "检测到 USB 设备断开：" + deviceSummary + "。如果刚刚切换 AOA，这可能是预期的重新枚举。");
                refreshDiagnostics();
                return;
            }
            if (!ACTION_USB_PERMISSION.equals(action) && !ACTION_AOA_PERMISSION.equals(action) && !ACTION_AOA_DATA_PERMISSION.equals(action) && !ACTION_AOA_FRAME_PERMISSION.equals(action) && !ACTION_AOA_SOCKET_PROBE_PERMISSION.equals(action)) return;
            boolean aoaRequest = ACTION_AOA_PERMISSION.equals(action);
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            recordEvent("USB_PERMISSION", "系统权限回调：action=" + action + "，granted=" + granted
                    + (device == null ? "" : "，设备=" + hex4(device.getVendorId()) + ":" + hex4(device.getProductId())));
            if (device == null) {
                setUsbStatus("USB 权限结果异常：系统未返回设备信息。");
            } else if (!granted) {
                setUsbStatus("未获得 USB 访问权限。你可以重新点击对应按钮，或检查系统权限提示。");
            } else if (ACTION_AOA_FRAME_PERMISSION.equals(action)) {
                setUsbStatus("已获得 AOA 设备 USB 权限，正在按 AOA 记录格式进行只读解析……");
                testAoaFramedRead(device);
            } else if (ACTION_AOA_SOCKET_PROBE_PERMISSION.equals(action)) {
                setUsbStatus("已获得 AOA 设备 USB 权限，启动实验性套接字生命周期验证……");
                testAoaSocketLifecycleProbe(device);
            } else if (ACTION_AOA_DATA_PERMISSION.equals(action)) {
                setUsbStatus("已获得 AOA 设备 USB 权限，正在进行原始 Bulk IN 抓包……");
                testAoaBulkRead(device);
            } else if (aoaRequest) {
                setUsbStatus("已获得 USB 权限，开始执行标准 AOA 握手……");
                performAoaHandshake(device);
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
        loadDiagnosticLog();
        refreshDiagnostics();
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_AOA_PERMISSION);
        filter.addAction(ACTION_AOA_DATA_PERMISSION);
        filter.addAction(ACTION_AOA_FRAME_PERMISSION);
        filter.addAction(ACTION_AOA_SOCKET_PROBE_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
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
        subtitle.setText("车机兼容性诊断工具 · 版本 0.2.6-dev.3");
        subtitle.setTextColor(0xFFB7C5D3);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle, matchWrap());

        TextView precheckTitle = new TextView(this);
        precheckTitle.setText("连接前置检查（S25 手机端）");
        precheckTitle.setTextColor(0xFFFFFFFF);
        precheckTitle.setTextSize(17);
        precheckTitle.setPadding(0, dp(6), 0, dp(4));
        root.addView(precheckTitle, matchWrap());

        TextView precheckHint = new TextView(this);
        precheckHint.setText("请在 S25 上手动检查。此应用运行在 S9+，无法远程读取 S25 的 USB 调试开关；勾选代表你已确认，并非系统自动检测结果。");
        precheckHint.setTextColor(0xFFFFD166);
        precheckHint.setTextSize(12);
        root.addView(precheckHint, matchWrap());

        phoneDebugCheck = new CheckBox(this);
        phoneDebugCheck.setText("S25 已开启 USB 调试");
        phoneDebugCheck.setTextColor(0xFFFFFFFF);
        root.addView(phoneDebugCheck, matchWrap());

        phoneUnlockedCheck = new CheckBox(this);
        phoneUnlockedCheck.setText("S25 已解锁并保持亮屏");
        phoneUnlockedCheck.setTextColor(0xFFFFFFFF);
        root.addView(phoneUnlockedCheck, matchWrap());

        Button startSession = new Button(this);
        startSession.setText("确认前置条件并开始诊断会话");
        startSession.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startDiagnosticSession(); }
        });
        LinearLayout.LayoutParams sessionParams = matchWrap();
        sessionParams.topMargin = dp(4);
        root.addView(startSession, sessionParams);

        Button refresh = new Button(this);
        refresh.setText("刷新诊断信息");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                refreshDiagnostics();
                logCurrentUsbDiscovery();
            }
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

        Button aoaButton = new Button(this);
        aoaButton.setText("发送 ICCOA AOA 身份并切换手机模式");
        aoaButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestUsbPermissionAndStartAoa(); }
        });
        LinearLayout.LayoutParams aoaParams = matchWrap();
        aoaParams.topMargin = dp(8);
        root.addView(aoaButton, aoaParams);


        TextView identityTitle = new TextView(this);
        identityTitle.setText("AOA 配件身份（匹配三星手机端）");
        identityTitle.setTextColor(0xFFFFFFFF);
        identityTitle.setTextSize(16);
        identityTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(identityTitle, matchWrap());
        TextView identityHint = new TextView(this);
        identityHint.setText("已从三星车联 APK 确认手机端过滤条件为 ICCOA / CarLink / 1.0.0。第 6 项序列号并非普通文本：手机端会按 6 段分号分隔字段解析。下方使用的是测试占位值，不是官方车机标识或真实配对码。");
        identityHint.setTextColor(0xFFFFD166);
        identityHint.setTextSize(12);
        root.addView(identityHint, matchWrap());
        aoaManufacturerInput = addAoaInput("制造商（manufacturer）", "ICCOA");
        aoaModelInput = addAoaInput("型号（model）", "CarLink");
        aoaDescriptionInput = addAoaInput("描述（description）", "ICCOA CarLink");
        aoaVersionInput = addAoaInput("版本（version）", "1.0.0");
        aoaUriInput = addAoaInput("说明网址（URI）", "http://www.iccoa.cn/");
        carModIdInput = addAoaInput("车机型号标识（mod_id，最多 12 字符）", "OCRMODEL0001");
        carIdInput = addAoaInput("车机标识（car_id，最多 8 字符）", "OCRID001");
        carShortNameInput = addAoaInput("车机简称（car_short_name，最多 16 个 UTF-8 字节）", "OpenCarlink");
        usbPinCodeInput = addAoaInput("USB 连接码（usb_pin_code，最多 16 字符）", "123456");
        carProtocolVersionInput = addAoaInput("协议版本（protocol_version）", "1.5");
        carVendorDataInput = addAoaInput("厂商自定义数据（car_vendor_custom_data，最多 16 字符）", "OpenCarlink");
        Button resetIdentity = new Button(this);
        resetIdentity.setText("恢复默认 AOA 身份");
        resetIdentity.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                aoaManufacturerInput.setText("ICCOA");
                aoaModelInput.setText("CarLink");
                aoaDescriptionInput.setText("ICCOA CarLink");
                aoaVersionInput.setText("1.0.0");
                aoaUriInput.setText("http://www.iccoa.cn/");
                carModIdInput.setText("OCRMODEL0001");
                carIdInput.setText("OCRID001");
                carShortNameInput.setText("OpenCarlink");
                usbPinCodeInput.setText("123456");
                carProtocolVersionInput.setText("1.5");
                carVendorDataInput.setText("OpenCarlink");
                setUsbStatus("已恢复官方 USB 配件匹配字段，并重置为本项目自拟的 6 段测试前导信息；这些车机 ID/连接码不是官方值。");
            }
        });
        root.addView(resetIdentity, matchWrap());

        Button aoaReadButton = new Button(this);
        aoaReadButton.setText("AOA 按帧读取并解析（只读）");
        aoaReadButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestAoaFramedPermission(); }
        });
        LinearLayout.LayoutParams aoaReadParams = matchWrap();
        aoaReadParams.topMargin = dp(8);
        root.addView(aoaReadButton, aoaReadParams);

        Button rawAoaReadButton = new Button(this);
        rawAoaReadButton.setText("原始 Bulk IN 抓包（诊断）");
        rawAoaReadButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestAoaDataPermission(); }
        });
        LinearLayout.LayoutParams rawAoaReadParams = matchWrap();
        rawAoaReadParams.topMargin = dp(4);
        root.addView(rawAoaReadButton, rawAoaReadParams);

        Button socketProbeButton = new Button(this);
        socketProbeButton.setText("USB AOA 连续记录读取（只读，不发送控制帧）");
        socketProbeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestAoaFramedPermission(); }
        });
        LinearLayout.LayoutParams socketProbeParams = matchWrap();
        socketProbeParams.topMargin = dp(8);
        root.addView(socketProbeButton, socketProbeParams);

        TextView socketProbeHint = new TextView(this);
        socketProbeHint.setText("USB 专项只读模式：读取 AOA Bulk IN 上的完整外层记录，记录通道 ID、声明长度和有限 HEX 样本；不创建 TCP 监听、不发送 Bulk OUT 控制帧、不处理 Wi-Fi、媒体或认证数据。需要逐次 USB 原始返回块及完整 HEX 时，请使用上方“原始 Bulk IN 抓包（诊断）”。");
        socketProbeHint.setTextColor(0xFFFFD166);
        socketProbeHint.setTextSize(12);
        root.addView(socketProbeHint, matchWrap());

        TextView aoaWarning = new TextView(this);
        aoaWarning.setText("注意：AOA 会向连接的 Android 设备发送标准 USB 控制请求，并可能导致对端 USB 重新枚举、断开当前调试连接。仅在你明确要测试时点击；此功能不发送 ICCOA 业务数据。");
        aoaWarning.setTextColor(0xFFFFD166);
        aoaWarning.setTextSize(12);
        aoaWarning.setPadding(0, dp(4), 0, dp(4));
        root.addView(aoaWarning, matchWrap());

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

        TextView eventTitle = new TextView(this);
        eventTitle.setText("【USB/AOA 阶段日志】");
        eventTitle.setTextColor(0xFFFFFFFF);
        eventTitle.setTextSize(16);
        eventTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(eventTitle, matchWrap());

        TextView eventHint = new TextView(this);
        eventHint.setText("记录阶段、USB 权限、AOA 请求结果与身份字段字节长度。不会在导出日志中包含六段序列号或连接码原文。");
        eventHint.setTextColor(0xFFB7C5D3);
        eventHint.setTextSize(12);
        root.addView(eventHint, matchWrap());

        Button exportLog = new Button(this);
        exportLog.setText("导出/分享脱敏诊断日志");
        exportLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exportDiagnosticLog(); }
        });
        root.addView(exportLog, matchWrap());

        Button exportTrace = new Button(this);
        exportTrace.setText("导出原始 ICCOA 通信追踪日志");
        exportTrace.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exportCommunicationTrace(); }
        });
        root.addView(exportTrace, matchWrap());

        Button clearLog = new Button(this);
        clearLog.setText("清除本机诊断日志");
        clearLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("清除诊断日志")
                        .setMessage("这会删除本机保存的阶段日志，且无法撤销。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清除", (dialog, which) -> clearDiagnosticLog())
                        .show();
            }
        });
        root.addView(clearLog, matchWrap());

        diagnosticEventLog = new TextView(this);
        diagnosticEventLog.setTextColor(0xFFE6EDF3);
        diagnosticEventLog.setTextSize(12);
        diagnosticEventLog.setTextIsSelectable(true);
        diagnosticEventLog.setPadding(0, dp(8), 0, dp(14));
        diagnosticEventLog.setText("尚无阶段日志。先勾选前置条件并开始诊断会话。");
        root.addView(diagnosticEventLog, matchWrap());

        TextView note = new TextView(this);
        note.setText("协议状态：尚未实现。检测到 USB 或 Wi-Fi 并不代表已连接 ICCOA Carlink。");
        note.setTextColor(0xFFFFD166);
        note.setTextSize(14);
        note.setPadding(0, dp(10), 0, dp(10));
        root.addView(note, matchWrap());
    }


    private void startDiagnosticSession() {
        if (phoneDebugCheck == null || phoneUnlockedCheck == null
                || !phoneDebugCheck.isChecked() || !phoneUnlockedCheck.isChecked()) {
            recordEvent("PRECHECK_FAILED", "开始诊断失败：用户未确认 S25 已开启 USB 调试且已解锁亮屏。");
            setUsbStatus("请先勾选 S25 的 USB 调试和解锁亮屏两项，再开始诊断。");
            return;
        }
        diagnosticSessionId = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        diagnosticSessionActive = true;
        recordEvent("SESSION", "开始诊断会话 session=" + diagnosticSessionId);
        recordEvent("PRECHECK", "用户手动确认 S25 已开启 USB 调试、已解锁并保持亮屏。S9+ 无法自动读取 S25 的这些状态。");
        setUsbStatus("诊断会话已开始。先运行普通 USB 打开测试，再按需执行 AOA；AOA 可能导致手机 USB 重新枚举。");
        logCurrentUsbDiscovery();
    }

    private boolean ensureDiagnosticPrecheck(String operation) {
        if (!diagnosticSessionActive) {
            recordEvent("PRECHECK_BLOCK", operation + " 被阻止：尚未开始诊断会话。");
            setUsbStatus("请先确认前置条件并开始诊断会话。");
            return false;
        }
        if (phoneDebugCheck == null || phoneUnlockedCheck == null
                || !phoneDebugCheck.isChecked() || !phoneUnlockedCheck.isChecked()) {
            diagnosticSessionActive = false;
            recordEvent("PRECHECK_BLOCK", operation + " 被阻止：前置条件勾选已取消。");
            setUsbStatus("前置条件未通过：请重新确认 S25 USB 调试和解锁亮屏状态。");
            return false;
        }
        return true;
    }

    private void loadDiagnosticLog() {
        String saved = getSharedPreferences(PREFS_DIAGNOSTICS, MODE_PRIVATE).getString(PREF_EVENT_LOG, "");
        diagnosticLog.setLength(0);
        if (saved != null) diagnosticLog.append(saved);
        if (diagnosticEventLog != null) diagnosticEventLog.setText(
                diagnosticLog.length() == 0 ? "尚无阶段日志。" : diagnosticLog.toString());
    }

    private void recordEvent(String stage, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String entry = timestamp + " [" + stage + "] "
                + (message == null ? "" : message.replace('\n', ' '));
        diagnosticLog.append(entry).append('\n');
        appendTraceLine("EVENT " + entry);
        while (diagnosticLog.length() > 48000) {
            int cut = diagnosticLog.indexOf("\n", 3000);
            if (cut < 0) { diagnosticLog.setLength(0); break; }
            diagnosticLog.delete(0, cut + 1);
        }
        getSharedPreferences(PREFS_DIAGNOSTICS, MODE_PRIVATE).edit()
                .putString(PREF_EVENT_LOG, diagnosticLog.toString()).apply();
        if (diagnosticEventLog != null) diagnosticEventLog.setText(diagnosticLog.toString());
    }

    private File traceFile() {
        return new File(getFilesDir(), "iccoa-communication-trace.log");
    }

    private synchronized void appendTraceLine(String line) {
        try {
            File file = traceFile();
            if (file.exists() && file.length() > 2 * 1024 * 1024) {
                FileOutputStream reset = new FileOutputStream(file, false);
                reset.write(("[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                        + "] TRACE_ROTATED previous log exceeded 2 MiB\n").getBytes("UTF-8"));
                reset.close();
            }
            FileOutputStream out = new FileOutputStream(file, true);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            out.write((timestamp + " " + line + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
            // Diagnostics must never crash the USB test if local trace storage fails.
        }
    }

    private String readTraceLog() {
        try {
            File file = traceFile();
            if (!file.exists()) return "尚无通信追踪记录。请先运行一次 AOA/Bulk IN 测试。";
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "读取通信追踪文件失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e);
        }
    }

    private String bytesToHex(byte[] data, int length) {
        StringBuilder hex = new StringBuilder(Math.max(0, length) * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) hex.append(' ');
            hex.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return hex.toString();
    }

    private void exportCommunicationTrace() {
        recordEvent("TRACE_EXPORT", "用户请求导出本机通信追踪日志（包含逐次 Bulk IN 原始 HEX）。");
        String text = "OpenCarlinkReceiver ICCOA 通信追踪日志\n"
                + "应用版本：0.2.5\n"
                + "设备：" + Build.MANUFACTURER + " " + Build.MODEL
                + "；Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）\n"
                + "说明：包含阶段事件、USB 接口/端点信息和逐次 Bulk IN 原始 HEX；不会主动记录六段 AOA 序列号原文或 usb_pin_code。原始 USB 数据仍可能含协议标识或其他设备信息，分享前请检查。\n\n"
                + readTraceLog();
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "OpenCarlinkReceiver ICCOA 通信追踪日志");
        share.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(share, "分享通信追踪日志"));
        } catch (Exception e) {
            setUsbStatus("无法打开系统分享界面：" + e.getClass().getSimpleName());
        }
    }

    private void logCurrentUsbDiscovery() {
        Map<String, UsbDevice> devices = usbManager == null ? null : usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            recordEvent("USB_DISCOVERY", "未检测到 USB 设备。");
            return;
        }
        recordEvent("USB_DISCOVERY", "检测到 " + devices.size() + " 个 USB 设备。");
        for (UsbDevice device : devices.values()) {
            recordEvent("USB_DEVICE", "VID:PID=" + hex4(device.getVendorId()) + ":" + hex4(device.getProductId())
                    + "，接口数=" + device.getInterfaceCount()
                    + "，本应用已获 USB 权限=" + (usbManager.hasPermission(device) ? "是" : "否"));
        }
    }

    private void exportDiagnosticLog() {
        recordEvent("EXPORT", "用户请求导出脱敏阶段日志。");
        String text = "OpenCarlinkReceiver USB/AOA 诊断日志\n"
                + "应用版本：0.2.5\n"
                + "车机设备：" + Build.MANUFACTURER + " " + Build.MODEL
                + "；Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）\n"
                + "当前会话：" + diagnosticSessionId + "\n"
                + "说明：日志记录人工确认、VID/PID、阶段、返回值和字段字节长度；不主动导出 USB 序列号、六段前导原文或连接码。\n\n"
                + diagnosticLog.toString();
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "OpenCarlinkReceiver USB/AOA 诊断日志");
        share.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(share, "分享诊断日志"));
        } catch (Exception e) {
            setUsbStatus("无法打开系统分享界面：" + e.getClass().getSimpleName());
        }
    }

    private void clearDiagnosticLog() {
        setUsbStatus("已清除本机保存的诊断日志。重新测试前请开始新会话。");
        diagnosticLog.setLength(0);
        diagnosticSessionActive = false;
        diagnosticSessionId = "未开始";
        getSharedPreferences(PREFS_DIAGNOSTICS, MODE_PRIVATE).edit().remove(PREF_EVENT_LOG).apply();
        try { File file = traceFile(); if (file.exists()) file.delete(); } catch (Exception ignored) { }
        if (diagnosticEventLog != null) diagnosticEventLog.setText("尚无阶段日志。");
    }

    private String summarizeBulkReadResult(String result) {
        if (result == null) return "AOA Bulk IN 测试无结果文本。";
        return result.replaceAll("，首批数据 HEX（最多约 512 字节）=.*", "，已省略原始 HEX 样本");
    }

    private EditText addAoaInput(String labelText, String defaultValue) {
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(0xFFB7C5D3);
        label.setTextSize(12);
        label.setPadding(0, dp(4), 0, 0);
        root.addView(label, matchWrap());
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(defaultValue);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF7F8C99);
        input.setTextSize(13);
        input.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(input, matchWrap());
        return input;
    }

    private void requestUsbPermissionAndTest() {
        recordEvent("USB_TEST", "用户发起普通 USB open/close 测试，不进行数据传输。");
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

    private void requestUsbPermissionAndStartAoa() {
        if (!ensureDiagnosticPrecheck("AOA 握手")) return;
        recordEvent("AOA_NEGOTIATION", "用户发起 AOA 握手。AOA 可能导致手机 USB 重新枚举并使当前 USB/ADB 连接中断。");
        if (usbManager == null) {
            setUsbStatus("USB 服务不可用。");
            return;
        }
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            setUsbStatus("未检测到 USB 设备。请连接发送端后刷新诊断信息。");
            return;
        }
        UsbDevice device = devices.values().iterator().next();
        if (device.getVendorId() == AOA_VENDOR_ID && isAoaAccessoryProductId(device.getProductId())) {
            setUsbStatus("设备看起来已经处于 Google AOA 配件模式（18D1:2Dxx）。请刷新诊断信息；无需再次发送切换请求。");
            refreshDiagnostics();
            return;
        }
        if (usbManager.hasPermission(device)) {
            setUsbStatus("已拥有 USB 权限，正在对 " + hex4(device.getVendorId()) + ":" + hex4(device.getProductId()) + " 执行标准 AOA 握手……");
            performAoaHandshake(device);
            refreshDiagnostics();
            return;
        }
        try {
            Intent intent = new Intent(ACTION_AOA_PERMISSION);
            intent.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 1, intent, flags);
            setUsbStatus("已请求 AOA 测试所需的 USB 权限。授权后会发送标准 AOA 控制请求，并可能使对端重新枚举。");
            usbManager.requestPermission(device, permissionIntent);
        } catch (Exception e) {
            setUsbStatus("请求 AOA 测试权限失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e));
        }
    }

    private boolean isAoaAccessoryProductId(int productId) {
        // AOA + accessory interface, optionally with ADB and/or AOA audio.
        return productId == 0x2D00 || productId == 0x2D01
                || productId == 0x2D04 || productId == 0x2D05;
    }

    private UsbDevice findAoaDevice() {
        if (usbManager == null) return null;
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null) return null;
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == AOA_VENDOR_ID && isAoaAccessoryProductId(device.getProductId())) {
                return device;
            }
        }
        return null;
    }

    private void requestAoaFramedPermission() {
        if (!ensureDiagnosticPrecheck("AOA 记录解析测试")) return;
        recordEvent("AOA_FRAMED_READ", "开始 AOA 外层记录解析测试；只读，不创建套接字、不发送业务数据。");
        UsbDevice device = findAoaDevice();
        if (device == null) {
            setUsbStatus("未找到 AOA 配件接口。请先执行 AOA 模式切换，再刷新诊断信息。");
            refreshDiagnostics();
            return;
        }
        if (usbManager.hasPermission(device)) {
            testAoaFramedRead(device);
            return;
        }
        try {
            Intent intent = new Intent(ACTION_AOA_FRAME_PERMISSION);
            intent.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 3, intent, flags);
            usbManager.requestPermission(device, permissionIntent);
            setUsbStatus("正在申请 AOA 配件 USB 权限；授权后只解析外层记录，不会发送业务数据。");
        } catch (Exception e) {
            setUsbStatus("申请 AOA 记录解析权限失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e));
        }
    }

    private void requestAoaDataPermission() {
        if (!ensureDiagnosticPrecheck("AOA Bulk IN 只读测试")) return;
        recordEvent("AOA_BULK_IN", "开始只读 Bulk IN 测试。");
        UsbDevice device = findAoaDevice();
        if (device == null) {
            setUsbStatus("未找到 AOA 配件接口（支持 18D1:2D00、2D01、2D04、2D05）。请先执行 AOA 模式切换，再刷新诊断信息。");
            refreshDiagnostics();
            return;
        }
        if (usbManager.hasPermission(device)) {
            setUsbStatus("已拥有 AOA 配件 " + hex4(device.getVendorId()) + ":" + hex4(device.getProductId())
                    + " 的 USB 权限，开始只读 Bulk IN 测试……");
            testAoaBulkRead(device);
            refreshDiagnostics();
            return;
        }
        try {
            Intent intent = new Intent(ACTION_AOA_DATA_PERMISSION);
            intent.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 2, intent, flags);
            setUsbStatus("正在申请 AOA 配件 " + hex4(device.getVendorId()) + ":" + hex4(device.getProductId())
                    + " 的 USB 权限。只进行 Bulk IN 读取，不向设备写入业务数据。");
            usbManager.requestPermission(device, permissionIntent);
        } catch (Exception e) {
            setUsbStatus("申请 AOA 配件权限失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e));
        }
    }

    /** Logs interface names and IPv4 addresses only, to diagnose missing TCP accepts. */
    private void appendNetworkInterfaceSnapshot(String phase) {
        appendTraceLine("NETWORK_SNAPSHOT_BEGIN phase=" + phase);
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                appendTraceLine("NETWORK_SNAPSHOT unavailable=true");
                return;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                boolean up;
                try { up = networkInterface.isUp(); }
                catch (Exception ignored) { up = false; }
                StringBuilder line = new StringBuilder("NETWORK_INTERFACE name=")
                        .append(networkInterface.getName())
                        .append(" up=").append(up)
                        .append(" loopback=").append(networkInterface.isLoopback());
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address) {
                        line.append(" ipv4=").append(address.getHostAddress());
                    }
                }
                appendTraceLine(line.toString());
            }
        } catch (Exception e) {
            appendTraceLine("NETWORK_SNAPSHOT_ERROR type=" + e.getClass().getSimpleName());
        } finally {
            appendTraceLine("NETWORK_SNAPSHOT_END phase=" + phase);
        }
    }

    private void confirmAoaSocketLifecycleProbe() {
        if (!ensureDiagnosticPrecheck("实验性 RTSP/UIBC 套接字生命周期验证")) return;
        new AlertDialog.Builder(this)
                .setTitle("启动实验性通道验证？")
                .setMessage("该测试会读取 AOA 创建套接字请求，并仅对端口 7236（RTSP）和 4321（UIBC）尝试建立本地监听。客户端接入后会向 USB OUT 发送一条 18 字节的候选通道响应（socketType 3/4，messageType 2）。不会发送认证载荷或转发媒体数据，但可能改变 S25 当前连接状态。测试最长约 35 秒，结束后关闭套接字。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续测试", (dialog, which) -> requestAoaSocketProbePermission())
                .show();
    }

    private void requestAoaSocketProbePermission() {
        UsbDevice device = findAoaDevice();
        if (device == null) {
            setUsbStatus("未找到 AOA 配件设备。请先执行 AOA 模式切换并刷新设备。");
            return;
        }
        if (usbManager.hasPermission(device)) {
            testAoaSocketLifecycleProbe(device);
            return;
        }
        try {
            Intent intent = new Intent(ACTION_AOA_SOCKET_PROBE_PERMISSION);
            intent.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 4, intent, flags);
            usbManager.requestPermission(device, permissionIntent);
            setUsbStatus("正在申请 AOA 配件 USB 权限；授权后会启动实验性套接字生命周期验证。");
        } catch (Exception e) {
            setUsbStatus("申请实验模式 USB 权限失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e));
        }
    }

    /**
     * Experimental probe based on observed RTSP/UIBC requests. It does not
     * implement media forwarding or authentication.
     */
    private void testAoaSocketLifecycleProbe(final UsbDevice device) {
        if (!usbReadOperationActive.compareAndSet(false, true)) {
            appendTraceLine("USB_READ_GUARD_BLOCK operation=SOCKET_PROBE reason=another_usb_reader_active");
            setUsbStatus("已有 USB 诊断读取正在运行。请等待其结束后再启动其他 USB 测试，避免多个线程争用 Bulk IN 端点。");
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                UsbDeviceConnection connection = null;
                UsbInterface claimedInterface = null;
                String result;
                List<ServerSocket> servers = new ArrayList<ServerSocket>();
                List<Socket> clients = new ArrayList<Socket>();
                AtomicInteger nextChannelId = new AtomicInteger(16);
                AtomicInteger accepts = new AtomicInteger(0);
                AtomicInteger writes = new AtomicInteger(0);
                try {
                    if (device.getVendorId() != AOA_VENDOR_ID || !isAoaAccessoryProductId(device.getProductId())) {
                        result = "实验模式取消：设备 VID/PID 已变化，请重新刷新。";
                    } else {
                        connection = usbManager.openDevice(device);
                        if (connection == null) {
                            result = "实验模式失败：openDevice() 返回空。";
                        } else {
                            UsbEndpoint inEndpoint = null;
                            UsbEndpoint outEndpoint = null;
                            for (int i = 0; i < device.getInterfaceCount(); i++) {
                                UsbInterface candidate = device.getInterface(i);
                                UsbEndpoint candidateIn = null;
                                UsbEndpoint candidateOut = null;
                                for (int j = 0; j < candidate.getEndpointCount(); j++) {
                                    UsbEndpoint endpoint = candidate.getEndpoint(j);
                                    if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                                    if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) candidateIn = endpoint;
                                    if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) candidateOut = endpoint;
                                }
                                if (candidateIn != null && candidateOut != null) {
                                    claimedInterface = candidate;
                                    inEndpoint = candidateIn;
                                    outEndpoint = candidateOut;
                                    break;
                                }
                            }
                            if (inEndpoint == null || outEndpoint == null || claimedInterface == null) {
                                result = "实验模式失败：未找到同时具有 Bulk IN/OUT 的 USB 接口。";
                            } else if (!connection.claimInterface(claimedInterface, true)) {
                                result = "实验模式失败：claimInterface() 未能声明接口。";
                            } else {
                                final UsbEndpoint responseEndpoint = outEndpoint;
                                final UsbDeviceConnection activeConnection = connection;
                                final Object writeLock = new Object();
                                AoaUsbExactLengthSource source = new AoaUsbExactLengthSource(connection, inEndpoint, 500);
                                long started = android.os.SystemClock.elapsedRealtime();
                                int records = 0;
                                int listenerBinds = 0;
                                ServerSocket rtspServer = null;
                                ServerSocket uibcServer = null;
                                appendNetworkInterfaceSnapshot("before_probe");
                                appendTraceLine("AOA_SOCKET_PROBE_START vid=" + hex4(device.getVendorId())
                                        + " pid=" + hex4(device.getProductId())
                                        + " interface=" + claimedInterface.getId()
                                        + " in_endpoint=" + hex2(inEndpoint.getAddress())
                                        + " out_endpoint=" + hex2(outEndpoint.getAddress())
                                        + " duration_cap_ms=35000 response_write=enabled media_forwarding=disabled auth_payload=disabled");

                                while (android.os.SystemClock.elapsedRealtime() - started < 35000 && records < 20) {
                                    AoaRecordReader.Record record = AoaRecordReader.readOne(source, 1024 * 1024);
                                    if (record == null) continue;
                                    records++;
                                    if (record.createSocket == null) {
                                        appendTraceLine("AOA_SOCKET_PROBE_RECORD ignored channel_id=" + record.header.channelId
                                                + " body_length=" + record.logicalBodyLength + " reason=not_create_socket");
                                        continue;
                                    }
                                    com.opencarlink.receiver.protocol.AoaCreateSocketRecord request = record.createSocket;
                                    appendTraceLine("AOA_SOCKET_REQUEST port=" + request.port
                                            + " requested_channel_id=" + request.channelId
                                            + " socket_type=" + request.socketType
                                            + " message_type=" + request.channelMessageType
                                            + " outer_channel_id=" + record.header.channelId);

                                    if (record.header.channelId != 1 || request.channelId != -1
                                            || !request.requestsServerSocket()
                                            || (request.port != 7236 && request.port != 4321)) {
                                        appendTraceLine("AOA_SOCKET_REQUEST_IGNORED reason=outside_confirmed_probe_scope");
                                        continue;
                                    }

                                    ServerSocket server = request.port == 7236 ? rtspServer : uibcServer;
                                    if (server != null) {
                                        appendTraceLine("TCP_LISTENER_DUPLICATE port=" + request.port + " ignored=true");
                                        continue;
                                    }
                                    try {
                                        server = new ServerSocket();
                                        server.setReuseAddress(false);
                                        server.bind(new InetSocketAddress(request.port));
                                        server.setSoTimeout(25000);
                                        servers.add(server);
                                        if (request.port == 7236) rtspServer = server; else uibcServer = server;
                                        listenerBinds++;
                                        appendTraceLine("TCP_LISTENER_BIND port=" + request.port + " success=true timeout_ms=25000");
                                    } catch (Exception bindError) {
                                        if (server != null) try { server.close(); } catch (Exception ignored) { }
                                        appendTraceLine("TCP_LISTENER_BIND port=" + request.port + " success=false error="
                                                + bindError.getClass().getSimpleName() + " message=" + safeMessage(bindError));
                                        continue;
                                    }

                                    final ServerSocket acceptServer = server;
                                    final int requestedPort = request.port;
                                    final int candidateSocketType = requestedPort == 7236
                                            ? com.opencarlink.receiver.protocol.AoaCreateSocketFrameEncoder.SOCKET_TYPE_RTSP
                                            : com.opencarlink.receiver.protocol.AoaCreateSocketFrameEncoder.SOCKET_TYPE_UIBC;
                                    Thread acceptWorker = new Thread(new Runnable() {
                                        @Override public void run() {
                                            Socket client = null;
                                            try {
                                                client = acceptServer.accept();
                                                synchronized (clients) { clients.add(client); }
                                                final int assignedChannelId = nextChannelId.getAndIncrement();
                                                accepts.incrementAndGet();
                                                appendTraceLine("TCP_ACCEPT port=" + requestedPort
                                                        + " success=true assigned_channel_id=" + assignedChannelId);
                                                byte[] frame = com.opencarlink.receiver.protocol.AoaCreateSocketFrameEncoder.encode(
                                                        requestedPort, assignedChannelId, candidateSocketType, 2);
                                                int sent;
                                                synchronized (writeLock) {
                                                    sent = activeConnection.bulkTransfer(responseEndpoint, frame, frame.length, 1000);
                                                }
                                                boolean sentOk = sent == frame.length;
                                                if (sentOk) writes.incrementAndGet();
                                                appendTraceLine("AOA_SOCKET_RESPONSE_WRITE port=" + requestedPort
                                                        + " assigned_channel_id=" + assignedChannelId
                                                        + " socket_type=" + candidateSocketType
                                                        + " candidate_message_type=2 expected_bytes=" + frame.length
                                                        + " actual_bytes=" + sent + " success=" + sentOk);
                                                Thread.sleep(5000);
                                            } catch (SocketTimeoutException timeout) {
                                                appendTraceLine("TCP_ACCEPT port=" + requestedPort + " success=false result=timeout");
                                            } catch (Exception acceptError) {
                                                appendTraceLine("TCP_ACCEPT port=" + requestedPort + " success=false error="
                                                        + acceptError.getClass().getSimpleName() + " message=" + safeMessage(acceptError));
                                            } finally {
                                                if (client != null) {
                                                    synchronized (clients) { clients.remove(client); }
                                                    try { client.close(); } catch (Exception ignored) { }
                                                }
                                            }
                                        }
                                    }, "AOA-TCP-Accept-" + request.port);
                                    acceptWorker.setDaemon(true);
                                    acceptWorker.start();
                                }
                                appendNetworkInterfaceSnapshot("after_probe");
                                appendTraceLine("AOA_SOCKET_PROBE_END records=" + records
                                        + " listener_binds=" + listenerBinds
                                        + " accepts=" + accepts.get() + " response_writes=" + writes.get()
                                        + " elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - started)
                                        + " media_forwarding=disabled auth_payload=disabled");
                                result = "实验性套接字验证结束。收到记录=" + records + "，监听绑定=" + listenerBinds
                                        + "，accept=" + accepts.get() + "，控制记录写入成功=" + writes.get()
                                        + "。未转发媒体或认证数据；详情见导出的通信追踪日志。";
                            }
                        }
                    }
                } catch (Exception e) {
                    appendTraceLine("AOA_SOCKET_PROBE_ERROR type=" + e.getClass().getSimpleName()
                            + " message=" + safeMessage(e));
                    result = "实验模式异常：" + e.getClass().getSimpleName() + "：" + safeMessage(e);
                } finally {
                    synchronized (clients) {
                        for (Socket client : clients) try { client.close(); } catch (Exception ignored) { }
                        clients.clear();
                    }
                    for (ServerSocket server : servers) try { server.close(); } catch (Exception ignored) { }
                    if (connection != null) {
                        if (claimedInterface != null) {
                            try { connection.releaseInterface(claimedInterface); } catch (Exception ignored) { }
                        }
                        try { connection.close(); } catch (Exception ignored) { }
                    }
                    usbReadOperationActive.set(false);
                }
                final String finalResult = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        recordEvent("AOA_SOCKET_PROBE", summarizeBulkReadResult(finalResult));
                        setUsbStatus(finalResult);
                        refreshDiagnostics();
                    }
                });
            }
        }, "AOA-SocketLifecycle-Probe");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Read-only diagnostic probe for the official receiver's observed 8-byte
     * AOA transport prefix. This intentionally reports both endian candidates
     * and does not assign field names or infer a body length.
     */
    private String probeAoaTransportHeader(byte[] data, int length) {
        if (!AoaTransportHeaderProbe.isAvailable(data, length)) return "unavailable";
        return "raw8=" + bytesToHex(data, 8)
                + ",u32be=[" + AoaTransportHeaderProbe.wordBigEndian(data, 0)
                + "," + AoaTransportHeaderProbe.wordBigEndian(data, 4) + "]"
                + ",u32le=[" + AoaTransportHeaderProbe.wordLittleEndian(data, 0)
                + "," + AoaTransportHeaderProbe.wordLittleEndian(data, 4) + "]";
    }

    /**
     * Read and decode complete outer AOA records. This path intentionally stops
     * at framing/channel metadata; it never opens sockets or sends protocol data.
     */
    private void testAoaFramedRead(final UsbDevice device) {
        if (!usbReadOperationActive.compareAndSet(false, true)) {
            appendTraceLine("USB_READ_GUARD_BLOCK operation=FRAMED_READ reason=another_usb_reader_active");
            setUsbStatus("已有 USB 诊断读取正在运行。请等待其结束后再启动其他 USB 测试，避免多个线程争用 Bulk IN 端点。");
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                UsbDeviceConnection connection = null;
                UsbInterface claimedInterface = null;
                String result;
                try {
                    if (device.getVendorId() != AOA_VENDOR_ID || !isAoaAccessoryProductId(device.getProductId())) {
                        result = "按帧解析已取消：设备 VID/PID 已变化，请刷新诊断信息后重试。";
                    } else {
                        connection = usbManager.openDevice(device);
                        if (connection == null) {
                            result = "按帧解析失败：openDevice() 返回空。";
                        } else {
                            UsbEndpoint inEndpoint = null;
                            for (int i = 0; i < device.getInterfaceCount(); i++) {
                                UsbInterface candidate = device.getInterface(i);
                                for (int j = 0; j < candidate.getEndpointCount(); j++) {
                                    UsbEndpoint endpoint = candidate.getEndpoint(j);
                                    if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                                            && endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                                        claimedInterface = candidate;
                                        inEndpoint = endpoint;
                                        break;
                                    }
                                }
                                if (inEndpoint != null) break;
                            }
                            if (inEndpoint == null || claimedInterface == null) {
                                result = "按帧解析失败：设备没有暴露 Bulk IN 端点。";
                            } else if (!connection.claimInterface(claimedInterface, true)) {
                                result = "按帧解析失败：claimInterface() 未能声明接口。";
                            } else {
                                final int localBodyLimit = 1024 * 1024;
                                AoaUsbExactLengthSource source =
                                        new AoaUsbExactLengthSource(connection, inEndpoint, 500);
                                long startedAtMs = android.os.SystemClock.elapsedRealtime();
                                int records = 0;
                                int totalBodyBytes = 0;
                                appendTraceLine("AOA_FRAMED_START vid=" + hex4(device.getVendorId())
                                        + " pid=" + hex4(device.getProductId())
                                        + " interface=" + claimedInterface.getId()
                                        + " endpoint=" + hex2(inEndpoint.getAddress())
                                        + " timeout_ms=500 local_body_cap=" + localBodyLimit);
                                StringBuilder summary = new StringBuilder();
                                int idleTimeouts = 0;
                                long lastIdleLogAtMs = startedAtMs;
                                // A 500 ms no-data timeout is normal on an idle Bulk IN endpoint.
                                // Keep listening so records that arrive later in the session are not missed.
                                while (records < 50
                                        && android.os.SystemClock.elapsedRealtime() - startedAtMs < 10000) {
                                    AoaRecordReader.Record record = AoaRecordReader.readOne(source, localBodyLimit);
                                    if (record == null) {
                                        idleTimeouts++;
                                        long nowMs = android.os.SystemClock.elapsedRealtime();
                                        if (nowMs - lastIdleLogAtMs >= 2000) {
                                            appendTraceLine("AOA_FRAMED_IDLE timeout_count=" + idleTimeouts
                                                    + " elapsed_ms=" + (nowMs - startedAtMs));
                                            lastIdleLogAtMs = nowMs;
                                        }
                                        continue;
                                    }
                                    records++;
                                    totalBodyBytes += record.logicalBodyLength;
                                    int sampleLength = Math.min(record.logicalBodyLength, 64);
                                    String line = "AOA_RECORD index=" + records
                                            + " channel_id=" + record.header.channelId
                                            + " declared_body_length=" + record.logicalBodyLength
                                            + " body_sample_hex=" + bytesToHex(record.body, sampleLength);
                                    if (record.logicalBodyLength >= 4) {
                                        line += " body_u32be_offset0=" + AoaTransportHeaderProbe.wordBigEndian(record.body, 0);
                                    }
                                    if (record.logicalBodyLength >= 8) {
                                        line += " body_u32be_offset4=" + AoaTransportHeaderProbe.wordBigEndian(record.body, 4);
                                    }
                                    // The create-socket field labels come from static inspection of the
                                    // official receiver parser; this remains diagnostic-only and opens nothing.
                                    if (record.createSocket != null) {
                                        line += " decoded_create_socket={port:" + record.createSocket.port
                                                + ",channel_id:" + record.createSocket.channelId
                                                + ",socket_type:" + record.createSocket.socketType
                                                + ",channel_message_type:" + record.createSocket.channelMessageType + "}";
                                    }
                                    appendTraceLine(line);
                                    if (summary.length() > 0) summary.append("\n");
                                    summary.append("#").append(records).append(" channel=")
                                            .append(record.header.channelId).append(" body=")
                                            .append(record.logicalBodyLength).append("B");
                                    if (record.logicalBodyLength > sampleLength) summary.append(" sample=").append(sampleLength).append("B");
                                }
                                appendTraceLine("AOA_FRAMED_END records=" + records
                                        + " idle_timeouts=" + idleTimeouts
                                        + " body_bytes=" + totalBodyBytes
                                        + " elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - startedAtMs)
                                        + " socket_creation=disabled business_writes=disabled");
                                result = "AOA 外层记录解析结束。完整记录=" + records
                                        + "，正文总字节=" + totalBodyBytes + "。\n"
                                        + (summary.length() == 0 ? "未收到完整记录。" : summary.toString())
                                        + "\n本测试只验证传输头/正文边界；不会创建套接字或发送认证/业务数据。";
                            }
                        }
                    }
                } catch (Exception e) {
                    appendTraceLine("AOA_FRAMED_ERROR type=" + e.getClass().getSimpleName()
                            + " message=" + safeMessage(e));
                    result = "AOA 按帧解析异常：" + e.getClass().getSimpleName() + "：" + safeMessage(e);
                } finally {
                    if (connection != null) {
                        if (claimedInterface != null) {
                            try { connection.releaseInterface(claimedInterface); } catch (Exception ignored) { }
                        }
                        try { connection.close(); } catch (Exception ignored) { }
                    }
                    usbReadOperationActive.set(false);
                }
                final String finalResult = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        recordEvent("AOA_FRAMED_READ", summarizeBulkReadResult(finalResult));
                        setUsbStatus(finalResult);
                        refreshDiagnostics();
                    }
                });
            }
        }, "AOA-FramedRead-Diagnostic");
        worker.setDaemon(true);
        worker.start();
    }

    private void testAoaBulkRead(final UsbDevice device) {
        if (!usbReadOperationActive.compareAndSet(false, true)) {
            appendTraceLine("USB_READ_GUARD_BLOCK operation=BULK_READ reason=another_usb_reader_active");
            setUsbStatus("已有 USB 诊断读取正在运行。请等待其结束后再启动其他 USB 测试，避免多个线程争用 Bulk IN 端点。");
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                UsbDeviceConnection connection = null;
                UsbInterface claimedInterface = null;
                String result;
                try {
                    if (device.getVendorId() != AOA_VENDOR_ID || !isAoaAccessoryProductId(device.getProductId())) {
                        result = "只读测试已取消：设备 VID/PID 已变化或不再包含 AOA 配件接口，请刷新诊断信息后重试。";
                    } else {
                        connection = usbManager.openDevice(device);
                        if (connection == null) {
                            result = "AOA Bulk IN 测试失败：openDevice() 返回空。";
                        } else {
                            UsbEndpoint inEndpoint = null;
                            for (int i = 0; i < device.getInterfaceCount(); i++) {
                                UsbInterface candidate = device.getInterface(i);
                                for (int j = 0; j < candidate.getEndpointCount(); j++) {
                                    UsbEndpoint endpoint = candidate.getEndpoint(j);
                                    if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                                            && endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                                        claimedInterface = candidate;
                                        inEndpoint = endpoint;
                                        break;
                                    }
                                }
                                if (inEndpoint != null) break;
                            }
                            if (inEndpoint == null || claimedInterface == null) {
                                result = "AOA Bulk IN 测试失败：设备没有暴露 Bulk IN 端点。";
                            } else if (!connection.claimInterface(claimedInterface, true)) {
                                result = "AOA Bulk IN 测试失败：claimInterface() 未能声明接口。";
                            } else {
                                byte[] buffer = new byte[Math.max(1024, inEndpoint.getMaxPacketSize())];
                                int totalBytes = 0;
                                int successfulReads = 0;
                                int timeoutOrNoData = 0;
                                int errors = 0;
                                // AOA Bulk IN is the outer transport layer; UCar framing belongs after AOA header handling and channel dispatch.
                                StringBuilder sampleHex = new StringBuilder();
                                long firstDataAtMs = -1;
                                long startedAtMs = android.os.SystemClock.elapsedRealtime();
                                long deadline = startedAtMs + 15000;
                                appendTraceLine("BULK_IN_START vid=" + hex4(device.getVendorId())
                                        + " pid=" + hex4(device.getProductId())
                                        + " interface=" + claimedInterface.getId()
                                        + " endpoint=" + hex2(inEndpoint.getAddress())
                                        + " endpoint_type=BULK max_packet=" + inEndpoint.getMaxPacketSize()
                                        + " timeout_ms=5000");
                                for (int epIndex = 0; epIndex < claimedInterface.getEndpointCount(); epIndex++) {
                                    UsbEndpoint ep = claimedInterface.getEndpoint(epIndex);
                                    appendTraceLine("USB_ENDPOINT interface=" + claimedInterface.getId()
                                            + " index=" + epIndex + " address=" + hex2(ep.getAddress())
                                            + " direction=" + (ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT")
                                            + " type=" + ep.getType() + " max_packet=" + ep.getMaxPacketSize());
                                }
                                while (android.os.SystemClock.elapsedRealtime() < deadline
                                        && successfulReads < 100 && totalBytes < 262144) {
                                    int count = connection.bulkTransfer(inEndpoint, buffer, buffer.length, 500);
                                    long elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAtMs;
                                    if (count > 0) {
                                        if (firstDataAtMs < 0) firstDataAtMs = android.os.SystemClock.elapsedRealtime();
                                        totalBytes += count;
                                        successfulReads++;
                                        byte[] receivedChunk = new byte[count];
                                        System.arraycopy(buffer, 0, receivedChunk, 0, count);
                                        appendTraceLine("BULK_IN_PACKET index=" + successfulReads
                                                + " elapsed_ms=" + elapsedMs
                                                + " requested_length=" + buffer.length
                                                + " returned_length=" + count
                                                + " transport_header_probe=" + probeAoaTransportHeader(buffer, count)
                                                + " hex=" + bytesToHex(buffer, count));
                                        int sampleLimit = Math.min(count, Math.max(0, (512 - sampleHex.length() / 3)));
                                        for (int k = 0; k < sampleLimit; k++) {
                                            if (sampleHex.length() > 0) sampleHex.append(' ');
                                            sampleHex.append(String.format(Locale.US, "%02X", buffer[k] & 0xFF));
                                        }
                                    } else if (count == 0 || count == -1) {
                                        timeoutOrNoData++;
                                        appendTraceLine("BULK_IN_NO_DATA elapsed_ms=" + elapsedMs
                                                + " return=" + count + " timeout_ms=500");
                                    } else {
                                        errors++;
                                        appendTraceLine("BULK_IN_ERROR elapsed_ms=" + elapsedMs + " return=" + count);
                                        break;
                                    }
                                }
                                appendTraceLine("BULK_IN_END duration_ms="
                                        + (android.os.SystemClock.elapsedRealtime() - startedAtMs)
                                        + " reads=" + successfulReads + " bytes=" + totalBytes
                                        + " no_data=" + timeoutOrNoData + " errors=" + errors
                                        + " ucar_parser=not_applied_at_raw_bulk_in"
                                        + " note=aoa_transport_header_and_channel_dispatch_must_be_resolved");
                                result = "AOA Bulk IN 只读测试结束（最长约 15 秒）。接口=" + claimedInterface.getId()
                                        + "，端点=" + hex2(inEndpoint.getAddress())
                                        + "，成功读取次数=" + successfulReads
                                        + "，收到字节=" + totalBytes
                                        + "，无数据/超时次数=" + timeoutOrNoData
                                        + "，其他错误次数=" + errors
                                        + "。UCar 帧解析未在原始 Bulk IN 层启用：官方协议先经过 AOA 传输头和通道分发。";
                            }
                        }
                    }
                } catch (Exception e) {
                    result = "AOA Bulk IN 只读测试异常：" + e.getClass().getSimpleName() + "：" + safeMessage(e);
                } finally {
                    if (connection != null) {
                        if (claimedInterface != null) {
                            try { connection.releaseInterface(claimedInterface); } catch (Exception ignored) { }
                        }
                        try { connection.close(); } catch (Exception ignored) { }
                    }
                    usbReadOperationActive.set(false);
                }
                final String finalResult = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        recordEvent("AOA_BULK_IN", summarizeBulkReadResult(finalResult));
                        setUsbStatus(finalResult);
                        refreshDiagnostics();
                    }
                });
            }
        }, "AOA-BulkIn-Diagnostic");
        worker.setDaemon(true);
        worker.start();
    }

    private void performAoaHandshake(UsbDevice device) {
        recordEvent("AOA_NEGOTIATION", "开始握手，候选设备=" + hex4(device.getVendorId()) + ":" + hex4(device.getProductId()));
        UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(device);
            if (connection == null) {
                setUsbStatus("AOA 握手未执行：openDevice() 返回空。");
                return;
            }
            byte[] versionBuffer = new byte[2];
            int versionLength = connection.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR,
                    51, 0, 0, versionBuffer, versionBuffer.length, 2000);
            if (versionLength < 2) {
                setUsbStatus("AOA 握手停止：设备未返回有效 AOA 协议版本（请求 51，返回长度 " + versionLength + "）。当前设备可能不支持标准 AOA，或此 USB 状态不接受该请求。未发送切换请求。");
                return;
            }
            int aoaVersion = (versionBuffer[0] & 0xFF) | ((versionBuffer[1] & 0xFF) << 8);
            recordEvent("AOA_NEGOTIATION", "请求 51 成功，AOA 协议版本=" + aoaVersion);
            if (aoaVersion < 1 || aoaVersion > 2) {
                setUsbStatus("AOA 握手停止：返回了无法识别的 AOA 版本 " + aoaVersion + "。未发送切换请求。");
                return;
            }

            final String serialPreamble;
            try {
                serialPreamble = buildAoaSerialNumber();
            } catch (IllegalArgumentException invalid) {
                setUsbStatus("AOA 前导信息无效：" + invalid.getMessage() + "。尚未请求切换模式。");
                return;
            }
            final String[] identity = new String[] {
                    aoaManufacturerInput == null ? "ICCOA" : aoaManufacturerInput.getText().toString().trim(),
                    aoaModelInput == null ? "CarLink" : aoaModelInput.getText().toString().trim(),
                    aoaDescriptionInput == null ? "ICCOA CarLink" : aoaDescriptionInput.getText().toString().trim(),
                    aoaVersionInput == null ? "1.0.0" : aoaVersionInput.getText().toString().trim(),
                    aoaUriInput == null ? "http://www.iccoa.cn/" : aoaUriInput.getText().toString().trim(),
                    serialPreamble
            };
            for (int i = 0; i < identity.length; i++) {
                byte[] encoded = identity[i].getBytes("UTF-8");
                if (encoded.length >= 255) {
                    setUsbStatus("AOA 身份字段 " + i + " 加上必需的 NUL 结束符后将超过 255 字节，已中止切换。请缩短内容后重试。");
                    return;
                }
            }
            for (int i = 0; i < identity.length; i++) {
                byte[] value = identity[i].getBytes("UTF-8");
                // AOA request 52 strings are NUL-terminated UTF-8 according to the AOA specification.
                byte[] terminatedValue = java.util.Arrays.copyOf(value, value.length + 1);
                int sent = connection.controlTransfer(
                        UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                        52, 0, i, terminatedValue, terminatedValue.length, 2000);
                if (sent != terminatedValue.length) {
                    recordEvent("AOA_IDENTITY", "字段 index=" + i + " 发送失败：返回=" + sent + "，预期=" + terminatedValue.length
                            + " 字节（含 NUL 结束符）；字段原文未记录。");
                    setUsbStatus("AOA 握手中止：AOA 版本 " + aoaVersion + "，发送身份字符串 index=" + i
                            + " 失败（返回 " + sent + "，预期 " + terminatedValue.length + "）。未请求切换模式。");
                    return;
                }
                recordEvent("AOA_IDENTITY", "字段 index=" + i + " 发送成功，UTF-8 内容字节数=" + value.length
                        + "，控制传输字节数=" + terminatedValue.length + "（含 NUL 结束符）；字段原文未记录。");
            }
            int startResult = connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                    53, 0, 0, null, 0, 2000);
            recordEvent("AOA_NEGOTIATION", "请求 53 START_ACCESSORY_MODE 返回值=" + startResult
                    + (startResult < 0 ? "（失败）" : "（USB 层已接受请求；尚不能证明重新枚举或 ICCOA 认证成功）"));
            if (startResult < 0) {
                setUsbStatus("AOA 版本 " + aoaVersion + "：身份字符串已发送，但 START_ACCESSORY_MODE 请求失败（" + startResult + "）。请刷新诊断信息。");
            } else {
                setUsbStatus("AOA 版本 " + aoaVersion + "：已发送官方 ICCOA 匹配字段和 6 段 AOA 序列号前导信息，并请求 START_ACCESSORY_MODE。前导格式=mod_id;car_id;car_short_name;usb_pin_code;protocol_version;car_vendor_custom_data。测试 ID/连接码是占位值；请等待手机重新枚举并观察是否启动三星车联。此步骤仍未实现 ICCOA 业务协议。");
            }
        } catch (Exception e) {
            setUsbStatus("AOA 握手异常：" + e.getClass().getSimpleName() + "：" + safeMessage(e));
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Builds AOA_SERIAL_NUMBER in the six-part format found in the official ICCOA APK:
     * mod_id;car_id;car_short_name;usb_pin_code;protocol_version;car_vendor_custom_data.
     * Values shown in the UI are diagnostic placeholders, not OEM provisioning data.
     */
    private String buildAoaSerialNumber() {
        String modId = encodeGenericField("mod_id", carModIdInput, 12);
        String carId = encodeGenericField("car_id", carIdInput, 8);
        String shortName = encodeCarShortName(carShortNameInput == null ? "" : carShortNameInput.getText().toString().trim());
        String pin = encodeGenericField("usb_pin_code", usbPinCodeInput, 16);
        String protocol = encodeProtocolVersion(carProtocolVersionInput == null ? "" : carProtocolVersionInput.getText().toString().trim());
        String vendorData = encodeGenericField("car_vendor_custom_data", carVendorDataInput, 16);
        return String.format(Locale.US, "%s;%s;%s;%s;%s;%s",
                modId, carId, shortName, pin, protocol, vendorData);
    }

    private String encodeGenericField(String fieldName, EditText input, int maxChars) {
        String value = input == null ? "" : input.getText().toString().trim();
        if (value.length() == 0) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        if (value.indexOf(';') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(fieldName + " 不能包含分号或 NUL 字符");
        }
        // Mirrors the official builder's substring cap for generic string fields.
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    private String encodeCarShortName(String value) {
        if (value.length() == 0) {
            throw new IllegalArgumentException("car_short_name 不能为空");
        }
        if (value.indexOf(';') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("car_short_name 不能包含分号或 NUL 字符");
        }
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16) return value;
        StringBuilder prefix = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = unit.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            // The official format reserves three UTF-8 bytes for the ellipsis when truncating.
            if (bytes + unitBytes > 13) break;
            prefix.append(unit);
            bytes += unitBytes;
            offset += Character.charCount(codePoint);
        }
        return prefix.append("…").toString();
    }

    private String encodeProtocolVersion(String value) {
        if (value.length() == 0 || value.indexOf(';') >= 0) {
            throw new IllegalArgumentException("protocol_version 不能为空且不能包含分号");
        }
        String[] parts = value.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("protocol_version 请按 major.minor 填写，例如 1.5");
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (major < 0 || major > 255 || minor < 0 || minor > 255) {
                throw new IllegalArgumentException("协议版本的 major/minor 必须在 0 到 255 之间");
            }
            // The official builder serializes the first two numeric components as major.minor.
            return String.format(Locale.US, "%d.%d", major, minor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("protocol_version 需要是数字版本，例如 1.5");
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
        if (message != null) {
            String stage = message.toLowerCase(Locale.ROOT).contains("aoa") ? "AOA_STATUS" : "USB_STATUS";
            recordEvent(stage, summarizeBulkReadResult(message));
        }
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
        out.append("“申请 USB 权限并测试打开连接”仅打开并关闭设备，不声明接口或传输数据。\n");
        out.append("“尝试 AOA 模式切换”会执行标准 AOA 请求 51/52/53，并可能让对端 USB 重新枚举。\n");
        out.append("“AOA 按帧读取并解析（只读）”按 8 字节传输头和声明长度读取完整正文，正文长度本地上限为 1 MiB；仅记录通道和有限 HEX 样本，不创建套接字、不发送业务数据。\n");
        out.append("“原始 Bulk IN 抓包（诊断）”读取原始端点返回块，不保证每次返回等于一条逻辑记录。\n");
        out.append("官方 APK 静态检查发现 AOA 连接管理、消息头解析、ProtocolParser、CRC16、认证/证书协议和多个 channel ID 相关组件；具体帧字段和消息编号仍需从字节码进一步确认。\n");
        out.append("AOA 模式及只读接收结果均不能单独证明 ICCOA 业务协议已实现。\n");
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
