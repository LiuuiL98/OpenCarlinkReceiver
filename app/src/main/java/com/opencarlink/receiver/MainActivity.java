package com.opencarlink.receiver;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbAccessory;
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
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.opencarlink.receiver.USB_PERMISSION";
    private static final String ACTION_AOA_PERMISSION = "com.opencarlink.receiver.AOA_PERMISSION";
    private static final String ACTION_AOA_DATA_PERMISSION = "com.opencarlink.receiver.AOA_DATA_PERMISSION";
    private static final String ACTION_ACCESSORY_PERMISSION = "com.opencarlink.receiver.ACCESSORY_PERMISSION";
    private static final int AOA_VENDOR_ID = 0x18D1;
    private static final int AOA_PRODUCT_ID = 0x2D00;
    private LinearLayout root;
    private TextView diagnostics;
    private TextView usbTestStatus;
    private EditText aoaManufacturerInput, aoaModelInput, aoaDescriptionInput, aoaVersionInput, aoaUriInput, aoaSerialInput;
    private UsbManager usbManager;
    private boolean receiverRegistered;
    private boolean accessoryReceiverRegistered;
    private UsbAccessory activeAccessory;
    private ParcelFileDescriptor accessoryParcelFileDescriptor;
    private FileInputStream accessoryInputStream;
    private volatile boolean stopAccessoryRead = true;
    private volatile long accessoryBytesReceived;
    private volatile String accessorySampleHex = "";
    private volatile String accessoryStatus = "尚未检测到 USB 配件连接。建议由 S25 官方 ICCOA CarLink 应用主动发起 AOA。";
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!ACTION_USB_PERMISSION.equals(action) && !ACTION_AOA_PERMISSION.equals(action) && !ACTION_AOA_DATA_PERMISSION.equals(action)) return;
            boolean aoaRequest = ACTION_AOA_PERMISSION.equals(action);
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
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

    private final BroadcastReceiver accessoryPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_ACCESSORY_PERMISSION.equals(intent.getAction())) return;
            UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (accessory == null) {
                setUsbStatus("USB 配件授权结果异常：系统未返回 UsbAccessory。");
            } else if (!granted) {
                setUsbStatus("未获得 USB 配件访问权限。请再次点击“检测/打开 ICCOA AOA 配件连接”。");
            } else {
                openAccessoryRead(accessory);
            }
            refreshDiagnostics();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
        registerAccessoryPermissionReceiver();
        buildUi();
        refreshDiagnostics();
        handleUsbAccessoryIntent(getIntent());
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleUsbAccessoryIntent(intent);
    }

    @Override
    protected void onDestroy() {
        closeAccessoryConnection();
        if (accessoryReceiverRegistered) {
            try { unregisterReceiver(accessoryPermissionReceiver); } catch (IllegalArgumentException ignored) { }
            accessoryReceiverRegistered = false;
        }
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
        subtitle.setText("车机兼容性诊断工具 · 版本 0.2.0");
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

        Button accessoryButton = new Button(this);
        accessoryButton.setText("检测/打开 ICCOA AOA 配件连接（推荐方向）");
        accessoryButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { probeAccessoryMode(); }
        });
        LinearLayout.LayoutParams accessoryButtonParams = matchWrap();
        accessoryButtonParams.topMargin = dp(8);
        root.addView(accessoryButton, accessoryButtonParams);
        TextView accessoryHint = new TextView(this);
        accessoryHint.setText("新版接收方向：由 S25 官方 ICCOA CarLink 应用作为 USB Host 发起 AOA，S9+ 以 USB 配件模式接收。测试时不要先点击下面的旧版“尝试 AOA 模式切换”。");
        accessoryHint.setTextColor(0xFFFFD166);
        accessoryHint.setTextSize(12);
        accessoryHint.setPadding(0, dp(4), 0, dp(6));
        root.addView(accessoryHint, matchWrap());

        Button usbTest = new Button(this);
        usbTest.setText("申请 USB 权限并测试打开连接");
        usbTest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestUsbPermissionAndTest(); }
        });
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(8);
        root.addView(usbTest, buttonParams);

        Button aoaButton = new Button(this);
        aoaButton.setText("旧版反向 AOA 模式切换（不用于推荐流程）");
        aoaButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestUsbPermissionAndStartAoa(); }
        });
        LinearLayout.LayoutParams aoaParams = matchWrap();
        aoaParams.topMargin = dp(8);
        root.addView(aoaButton, aoaParams);


        TextView identityTitle = new TextView(this);
        identityTitle.setText("旧版反向 AOA 身份（仅供对照测试）");
        identityTitle.setTextColor(0xFFFFFFFF);
        identityTitle.setTextSize(16);
        identityTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(identityTitle, matchWrap());
        TextView identityHint = new TextView(this);
        identityHint.setText("这些字段只在本机主动向对端发送 AOA 请求时使用；官方 ICCOA APK 的连接流程提示我们应优先测试相反方向。建议保持本页不动，改为在 S25 上主动发起连接。");
        identityHint.setTextColor(0xFFFFD166);
        identityHint.setTextSize(12);
        root.addView(identityHint, matchWrap());
        aoaManufacturerInput = addAoaInput("制造商（manufacturer）", "ICCOA");
        aoaModelInput = addAoaInput("型号（model）", "CarLink");
        aoaDescriptionInput = addAoaInput("描述（description）", "ICCOA CarLink");
        aoaVersionInput = addAoaInput("版本（version）", "1.0.0");
        aoaUriInput = addAoaInput("说明网址（URI）", "http://www.iccoa.cn/");
        aoaSerialInput = addAoaInput("序列号（serial）", "OCR-ANDROID-RECEIVER");
        Button resetIdentity = new Button(this);
        resetIdentity.setText("恢复默认 AOA 身份");
        resetIdentity.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                aoaManufacturerInput.setText("ICCOA");
                aoaModelInput.setText("CarLink");
                aoaDescriptionInput.setText("ICCOA CarLink");
                aoaVersionInput.setText("1.0.0");
                aoaUriInput.setText("http://www.iccoa.cn/");
                aoaSerialInput.setText("OCR-ANDROID-RECEIVER");
                setUsbStatus("已恢复从官方 APK 确认的 AOA 身份字段；序列号仍为本项目测试值。该操作只用于匹配测试，不代表 ICCOA 协议已连接。");
            }
        });
        root.addView(resetIdentity, matchWrap());

        Button aoaReadButton = new Button(this);
        aoaReadButton.setText("旧版反向 AOA Bulk IN 测试（仅供对照）");
        aoaReadButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestAoaDataPermission(); }
        });
        LinearLayout.LayoutParams aoaReadParams = matchWrap();
        aoaReadParams.topMargin = dp(8);
        root.addView(aoaReadButton, aoaReadParams);

        TextView aoaWarning = new TextView(this);
        aoaWarning.setText("注意：此处两个旧版按钮会让 S9+ 主动操作 S25 的 USB 模式，可能导致重新枚举或断开调试。推荐流程应由 S25 官方 ICCOA 应用发起 AOA，S9+ 通过 UsbAccessory 接口接收。");
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

        TextView note = new TextView(this);
        note.setText("协议状态：尚未实现。检测到 USB 或 Wi-Fi 并不代表已连接 ICCOA Carlink。");
        note.setTextColor(0xFFFFD166);
        note.setTextSize(14);
        note.setPadding(0, dp(10), 0, dp(10));
        root.addView(note, matchWrap());
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

    private void registerAccessoryPermissionReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_ACCESSORY_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(accessoryPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(accessoryPermissionReceiver, filter);
        }
        accessoryReceiverRegistered = true;
    }

    private void handleUsbAccessoryIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
            UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            if (accessory != null) {
                accessoryStatus = "系统通过 USB_ACCESSORY_ATTACHED 启动了本应用；正在申请配件访问权限。";
                requestAccessoryPermission(accessory);
            } else {
                probeAccessoryMode();
            }
        } else {
            UsbAccessory[] accessories = usbManager == null ? null : usbManager.getAccessoryList();
            if (accessories != null && accessories.length > 0) {
                accessoryStatus = "已检测到 USB 配件。点击顶部按钮打开只读接收通道。";
            }
        }
        refreshDiagnostics();
    }

    private void probeAccessoryMode() {
        UsbAccessory[] accessories = usbManager == null ? null : usbManager.getAccessoryList();
        if (accessories == null || accessories.length == 0) {
            accessoryStatus = "未检测到 UsbAccessory。请在 S25 打开官方 ICCOA CarLink 并主动发起连接；不要先使用本应用的旧版反向 AOA 按钮。";
            setUsbStatus(accessoryStatus);
            refreshDiagnostics();
            return;
        }
        requestAccessoryPermission(accessories[0]);
    }

    private void requestAccessoryPermission(UsbAccessory accessory) {
        if (usbManager == null || accessory == null) {
            setUsbStatus("USB 配件服务或设备信息不可用。");
            return;
        }
        if (usbManager.hasPermission(accessory)) {
            openAccessoryRead(accessory);
            return;
        }
        try {
            Intent intent = new Intent(ACTION_ACCESSORY_PERMISSION);
            intent.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 3, intent, flags);
            accessoryStatus = "已请求 USB 配件访问权限，请在系统提示中允许。";
            usbManager.requestPermission(accessory, permissionIntent);
            setUsbStatus(accessoryStatus);
        } catch (Exception e) {
            accessoryStatus = "请求 USB 配件权限失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e);
            setUsbStatus(accessoryStatus);
        }
        refreshDiagnostics();
    }

    private void openAccessoryRead(final UsbAccessory accessory) {
        synchronized (this) {
            if (accessoryParcelFileDescriptor != null && accessory.equals(activeAccessory)) {
                accessoryStatus = "USB 配件读取通道已经打开。";
                setUsbStatus(accessoryStatus + " 已接收 " + accessoryBytesReceived + " 字节。");
                refreshDiagnostics();
                return;
            }
        }
        closeAccessoryConnection();
        ParcelFileDescriptor descriptor = null;
        FileInputStream input = null;
        try {
            descriptor = usbManager.openAccessory(accessory);
            if (descriptor == null) {
                accessoryStatus = "UsbManager.openAccessory() 返回空；请确认配件权限及 USB 连接状态。";
                setUsbStatus(accessoryStatus);
                refreshDiagnostics();
                return;
            }
            input = new FileInputStream(descriptor.getFileDescriptor());
            synchronized (this) {
                activeAccessory = accessory;
                accessoryParcelFileDescriptor = descriptor;
                accessoryInputStream = input;
                stopAccessoryRead = false;
                accessoryBytesReceived = 0;
                accessorySampleHex = "";
                accessoryStatus = "已打开 USB 配件输入流，正在等待 S25 发送 ICCOA 数据。当前仅读不写。";
            }
            setUsbStatus(accessoryStatus);
            refreshDiagnostics();
            final FileInputStream stream = input;
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buffer = new byte[4096];
                    try {
                        while (!stopAccessoryRead) {
                            int count = stream.read(buffer);
                            if (count < 0) break;
                            if (count == 0) continue;
                            synchronized (MainActivity.this) {
                                accessoryBytesReceived += count;
                                StringBuilder hex = new StringBuilder();
                                int take = Math.min(count, Math.max(0, 512 - accessorySampleHex.length() / 3));
                                for (int i = 0; i < take; i++) {
                                    if (accessorySampleHex.length() > 0 || hex.length() > 0) hex.append(' ');
                                    hex.append(String.format(Locale.US, "%02X", buffer[i] & 0xFF));
                                }
                                if (hex.length() > 0) {
                                    accessorySampleHex = accessorySampleHex.length() == 0
                                            ? hex.toString() : accessorySampleHex + " " + hex.toString();
                                }
                                accessoryStatus = "USB 配件输入流正在接收数据；本次 +" + count
                                        + " 字节，累计 " + accessoryBytesReceived + " 字节。";
                            }
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    setUsbStatus(accessoryStatus);
                                    refreshDiagnostics();
                                }
                            });
                        }
                    } catch (IOException e) {
                        if (!stopAccessoryRead) {
                            accessoryStatus = "USB 配件读取结束：" + e.getClass().getSimpleName() + "：" + safeMessage(e);
                        }
                    } finally {
                        if (!stopAccessoryRead) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    setUsbStatus(accessoryStatus);
                                    refreshDiagnostics();
                                }
                            });
                        }
                    }
                }
            }, "ICCOA-UsbAccessory-Read");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
            accessoryStatus = "打开 USB 配件失败：" + e.getClass().getSimpleName() + "：" + safeMessage(e);
            setUsbStatus(accessoryStatus);
            refreshDiagnostics();
        }
    }

    private synchronized void closeAccessoryConnection() {
        stopAccessoryRead = true;
        if (accessoryInputStream != null) {
            try { accessoryInputStream.close(); } catch (IOException ignored) { }
            accessoryInputStream = null;
        }
        if (accessoryParcelFileDescriptor != null) {
            try { accessoryParcelFileDescriptor.close(); } catch (IOException ignored) { }
            accessoryParcelFileDescriptor = null;
        }
        activeAccessory = null;
    }

    private String nullToUnknown(String value) {
        return value == null || value.length() == 0 ? "（未提供）" : value;
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

    private void requestUsbPermissionAndStartAoa() {
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
            if (aoaVersion < 1 || aoaVersion > 2) {
                setUsbStatus("AOA 握手停止：返回了无法识别的 AOA 版本 " + aoaVersion + "。未发送切换请求。");
                return;
            }

            final String[] identity = new String[] {
                    aoaManufacturerInput == null ? "ICCOA" : aoaManufacturerInput.getText().toString().trim(),
                    aoaModelInput == null ? "CarLink" : aoaModelInput.getText().toString().trim(),
                    aoaDescriptionInput == null ? "ICCOA CarLink" : aoaDescriptionInput.getText().toString().trim(),
                    aoaVersionInput == null ? "1.0.0" : aoaVersionInput.getText().toString().trim(),
                    aoaUriInput == null ? "http://www.iccoa.cn/" : aoaUriInput.getText().toString().trim(),
                    aoaSerialInput == null ? "OCR-ANDROID-RECEIVER" : aoaSerialInput.getText().toString().trim()
            };
            for (int i = 0; i < identity.length; i++) {
                byte[] encoded = identity[i].getBytes("UTF-8");
                if (encoded.length > 255) {
                    setUsbStatus("AOA 身份字段 " + i + " 超过 255 字节，已中止切换。请缩短内容后重试。");
                    return;
                }
            }
            for (int i = 0; i < identity.length; i++) {
                byte[] value = (identity[i] + "\0").getBytes("UTF-8");
                int sent = connection.controlTransfer(
                        UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                        52, 0, i, value, value.length, 2000);
                if (sent != value.length) {
                    setUsbStatus("AOA 握手中止：AOA 版本 " + aoaVersion + "，发送身份字符串 index=" + i
                            + " 失败（返回 " + sent + "，预期 " + value.length + "）。未请求切换模式。");
                    return;
                }
            }
            int startResult = connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                    53, 0, 0, null, 0, 2000);
            if (startResult < 0) {
                setUsbStatus("AOA 版本 " + aoaVersion + "：身份字符串已发送，但 START_ACCESSORY_MODE 请求失败（" + startResult + "）。请刷新诊断信息。");
            } else {
                setUsbStatus("AOA 版本 " + aoaVersion + "：已发送 START_ACCESSORY_MODE 请求。对端可能会断开并重新枚举；请等待数秒后点击“刷新诊断信息”。本步骤尚未实现 ICCOA 数据协议。");
            }
        } catch (Exception e) {
            setUsbStatus("AOA 握手异常：" + e.getClass().getSimpleName() + "：" + safeMessage(e));
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { }
            }
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
        out.append("\n\n【USB 配件接收模式（新版推荐方向）】\n");
        UsbAccessory[] accessories = usbManager == null ? null : usbManager.getAccessoryList();
        if (accessories == null || accessories.length == 0) {
            out.append("当前未检测到 UsbAccessory。请保持数据线连接，并在 S25 官方 ICCOA CarLink 应用中主动发起连接；本机不应先发送 AOA 请求。\n");
        } else {
            out.append("检测到 USB 配件数量：").append(accessories.length).append("\n");
            for (UsbAccessory accessory : accessories) {
                out.append("制造商：").append(nullToUnknown(accessory.getManufacturer())).append("\n");
                out.append("型号：").append(nullToUnknown(accessory.getModel())).append("\n");
                out.append("描述：").append(nullToUnknown(accessory.getDescription())).append("\n");
                out.append("版本：").append(nullToUnknown(accessory.getVersion())).append("\n");
                out.append("URI：").append(nullToUnknown(accessory.getUri())).append("\n");
                if (Build.VERSION.SDK_INT >= 21) {
                    try { out.append("序列号：").append(nullToUnknown(accessory.getSerial())).append("\n"); }
                    catch (SecurityException e) { out.append("序列号：系统未授权读取\n"); }
                }
                out.append("本应用已获配件访问权限：").append(usbManager.hasPermission(accessory) ? "是" : "否").append("\n");
            }
        }
        out.append("配件读取状态：").append(accessoryStatus).append("\n");
        out.append("配件接收累计字节：").append(accessoryBytesReceived).append("\n");
        if (accessorySampleHex.length() > 0) out.append("配件首批 HEX 样本：").append(accessorySampleHex).append("\n");
        out.append("\n【USB 主机诊断（旧版反向测试）】\n");
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
        out.append("“检测/打开 ICCOA AOA 配件连接”使用 Android UsbAccessory API，等待 S25 主动发起 AOA；这是当前推荐测试方向。\n");
        out.append("“旧版反向 AOA 模式切换”会由 S9+ 向 S25 发送标准 AOA 请求 51/52/53，仅保留作对照，不是推荐 ICCOA 连接流程。\n");
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
