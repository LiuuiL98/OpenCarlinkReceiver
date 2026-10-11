package com.opencarlink.receiver;

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
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.opencarlink.receiver.USB_PERMISSION";
    private static final String ACTION_AOA_PERMISSION = "com.opencarlink.receiver.AOA_PERMISSION";
    private static final String ACTION_AOA_DATA_PERMISSION = "com.opencarlink.receiver.AOA_DATA_PERMISSION";
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
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!ACTION_USB_PERMISSION.equals(action) && !ACTION_AOA_PERMISSION.equals(action) && !ACTION_AOA_DATA_PERMISSION.equals(action)) return;
            boolean aoaRequest = ACTION_AOA_PERMISSION.equals(action);
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            recordEvent("USB_PERMISSION", "系统权限回调：action=" + action + "，granted=" + granted
                    + (device == null ? "" : "，设备=" + hex4(device.getVendorId()) + ":" + hex4(device.getProductId())));
            if (device == null) {
                setUsbStatus("USB 权限结果异常：系统未返回设备信息。");
            } else if (!granted) {
                setUsbStatus("未获得 USB 访问权限。你可以重新点击对应按钮，或检查系统权限提示。");
            } else if (ACTION_AOA_DATA_PERMISSION.equals(action)) {
                setUsbStatus("已获得 AOA 设备 USB 权限，正在进行只读 Bulk IN 接收测试……");
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
        subtitle.setText("车机兼容性诊断工具 · 版本 0.2.2");
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
        aoaReadButton.setText("申请 AOA 设备权限并测试接收");
        aoaReadButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestAoaDataPermission(); }
        });
        LinearLayout.LayoutParams aoaReadParams = matchWrap();
        aoaReadParams.topMargin = dp(8);
        root.addView(aoaReadButton, aoaReadParams);

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
        diagnosticLog.append(timestamp).append(" [").append(stage).append("] ")
                .append(message == null ? "" : message.replace('\n', ' ')).append('\n');
        while (diagnosticLog.length() > 48000) {
            int cut = diagnosticLog.indexOf("\n", 3000);
            if (cut < 0) { diagnosticLog.setLength(0); break; }
            diagnosticLog.delete(0, cut + 1);
        }
        getSharedPreferences(PREFS_DIAGNOSTICS, MODE_PRIVATE).edit()
                .putString(PREF_EVENT_LOG, diagnosticLog.toString()).apply();
        if (diagnosticEventLog != null) diagnosticEventLog.setText(diagnosticLog.toString());
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
                + "应用版本：0.2.2\n"
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
        diagnosticLog.setLength(0);
        diagnosticSessionActive = false;
        diagnosticSessionId = "未开始";
        getSharedPreferences(PREFS_DIAGNOSTICS, MODE_PRIVATE).edit().remove(PREF_EVENT_LOG).apply();
        if (diagnosticEventLog != null) diagnosticEventLog.setText("尚无阶段日志。");
        setUsbStatus("已清除本机保存的诊断日志。重新测试前请开始新会话。");
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
        if (device.getVendorId() == 0x18D1 && (device.getProductId() == 0x2D00
                || device.getProductId() == 0x2D01 || device.getProductId() == 0x2D04)) {
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

    private UsbDevice findAoaDevice() {
        if (usbManager == null) return null;
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null) return null;
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == AOA_VENDOR_ID && device.getProductId() == AOA_PRODUCT_ID) {
                return device;
            }
        }
        return null;
    }

    private void requestAoaDataPermission() {
        if (!ensureDiagnosticPrecheck("AOA Bulk IN 只读测试")) return;
        recordEvent("AOA_BULK_IN", "开始只读 Bulk IN 测试。");
        UsbDevice device = findAoaDevice();
        if (device == null) {
            setUsbStatus("未找到 AOA 配件设备 18D1:2D00。请先连接 S26，必要时执行 AOA 模式切换，再刷新诊断信息。");
            refreshDiagnostics();
            return;
        }
        if (usbManager.hasPermission(device)) {
            setUsbStatus("已拥有 18D1:2D00 的 USB 权限，开始只读 Bulk IN 测试……");
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
            setUsbStatus("正在单独申请 AOA 配件 18D1:2D00 的 USB 权限。授权后只进行 Bulk IN 读取，不会向设备写入数据。若系统已授权，点击按钮会直接开始测试。");
            usbManager.requestPermission(device, permissionIntent);
        } catch (Exception e) {
            setUsbStatus("申请 AOA 配件权限失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e));
        }
    }

    private void testAoaBulkRead(final UsbDevice device) {
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                UsbDeviceConnection connection = null;
                UsbInterface claimedInterface = null;
                String result;
                try {
                    if (device.getVendorId() != AOA_VENDOR_ID || device.getProductId() != AOA_PRODUCT_ID) {
                        result = "只读测试已取消：设备 VID/PID 已变化，请刷新诊断信息后重试。";
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
                                StringBuilder sampleHex = new StringBuilder();
                                long firstDataAtMs = -1;
                                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                                while (android.os.SystemClock.elapsedRealtime() < deadline
                                        && successfulReads < 20 && totalBytes < 65536) {
                                    int count = connection.bulkTransfer(inEndpoint, buffer, buffer.length, 500);
                                    if (count > 0) {
                                        if (firstDataAtMs < 0) firstDataAtMs = android.os.SystemClock.elapsedRealtime();
                                        totalBytes += count;
                                        successfulReads++;
                                        int sampleLimit = Math.min(count, Math.max(0, (512 - sampleHex.length() / 3)));
                                        for (int k = 0; k < sampleLimit; k++) {
                                            if (sampleHex.length() > 0) sampleHex.append(' ');
                                            sampleHex.append(String.format(Locale.US, "%02X", buffer[k] & 0xFF));
                                        }
                                    } else if (count == 0 || count == -1) {
                                        timeoutOrNoData++;
                                    } else {
                                        errors++;
                                        break;
                                    }
                                }
                                result = "AOA Bulk IN 只读测试结束（最长约 5 秒）。接口=" + claimedInterface.getId()
                                        + "，端点=" + hex2(inEndpoint.getAddress())
                                        + "，成功读取次数=" + successfulReads
                                        + "，收到字节=" + totalBytes
                                        + "，无数据/超时次数=" + timeoutOrNoData
                                        + "，其他错误次数=" + errors
                                        + (firstDataAtMs >= 0 ? "，首次数据延迟=" + (firstDataAtMs - (deadline - 5000)) + "ms" : "")
                                        + (sampleHex.length() > 0 ? "，首批数据 HEX（最多约 512 字节）=" + sampleHex : "，HEX 样本=无（本次未收到数据）")
                                        + "。本测试未向 Bulk OUT 写入任何数据；没有收到数据不代表 ICCOA 不存在。";
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
                if (encoded.length > 255) {
                    setUsbStatus("AOA 身份字段 " + i + " 超过 255 字节，已中止切换。请缩短内容后重试。");
                    return;
                }
            }
            for (int i = 0; i < identity.length; i++) {
                byte[] value = identity[i].getBytes("UTF-8");
                int sent = connection.controlTransfer(
                        UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                        52, 0, i, value, value.length, 2000);
                if (sent != value.length) {
                    recordEvent("AOA_IDENTITY", "字段 index=" + i + " 发送失败：返回=" + sent + "，预期=" + value.length + " 字节；字段原文未记录。");
                    setUsbStatus("AOA 握手中止：AOA 版本 " + aoaVersion + "，发送身份字符串 index=" + i
                            + " 失败（返回 " + sent + "，预期 " + value.length + "）。未请求切换模式。");
                    return;
                }
                recordEvent("AOA_IDENTITY", "字段 index=" + i + " 发送成功，UTF-8 字节数=" + value.length + "；字段原文未记录。");
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
            recordEvent(stage, message);
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
        out.append("“申请 AOA 设备权限并测试接收”仅对 18D1:2D00 声明接口并限时读取 Bulk IN；若收到数据，会显示最多约 512 字节 HEX 样本；不向 Bulk OUT 写入数据。\n");
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
