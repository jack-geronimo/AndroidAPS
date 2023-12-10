package app.aaps.pump.equil.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import app.aaps.core.interfaces.logging.AAPSLogger;
import app.aaps.core.interfaces.logging.LTag;
import app.aaps.core.interfaces.rx.bus.RxBus;
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged;
import app.aaps.core.interfaces.sharedPreferences.SP;
import app.aaps.pump.equil.EquilConst;
import app.aaps.pump.equil.data.database.ResolvedResult;
import app.aaps.pump.equil.driver.definition.BluetoothConnectionState;
import app.aaps.pump.equil.manager.EquilManager;
import app.aaps.pump.equil.manager.EquilResponse;
import app.aaps.pump.equil.manager.Utils;
import app.aaps.pump.equil.manager.command.BaseCmd;
import app.aaps.pump.equil.manager.command.CmdDevicesOldGet;
import app.aaps.pump.equil.manager.command.CmdHistoryGet;
import app.aaps.pump.equil.manager.command.CmdInsulinGet;
import app.aaps.pump.equil.manager.command.CmdLargeBasalSet;
import app.aaps.pump.equil.manager.command.CmdModelGet;
import app.aaps.pump.equil.manager.command.CmdPair;


@SuppressLint("MissingPermission")
public class EquilBLE {

    @Inject RxBus rxBus;
    EquilManager equilManager;
    SP sp;
    BluetoothGattCallback mGattCallback;
    AAPSLogger aapsLogger;
    BluetoothGattCharacteristic notifyChara;
    BluetoothGattCharacteristic wirteChara;
    protected volatile BluetoothGatt mBluetoothGatt;
    public int mStatus;
    Context context;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothDevice equilDevice;
    boolean connected;
    boolean connecting;
    String macAddrss;
    HandlerThread bleThread;
    Handler bleHandler;

    @Inject public EquilBLE(AAPSLogger aapsLogger, Context context, SP sp) {
        this.aapsLogger = aapsLogger;
        this.context = context;
        this.sp = sp;
        bleThread = new HandlerThread("BleHandler");
        bleThread.start();
        bleHandler = new Handler(bleThread.getLooper());
    }

    public boolean isConnected() {
        return connected;
    }

    public void initAdapter() {
        final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return;
        }
        mBluetoothAdapter = bluetoothManager.getAdapter();
    }

    public synchronized void unBond(String transmitterMAC) {

        if (transmitterMAC == null) return;
        try {
            final Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (!pairedDevices.isEmpty()) {
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getAddress() != null) {
                        if (device.getAddress().equals(transmitterMAC)) {
                            try {
                                //noinspection JavaReflectionMemberAccess,rawtypes
                                Method m = device.getClass().getMethod("removeBond", (Class[]) null);
                                m.invoke(device, (Object[]) null);
                            } catch (Exception e) {
                                aapsLogger.error(LTag.PUMPBTCOMM, "Error", e);
                            }
                        }

                    }
                }
            }
        } catch (Exception e) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Error", e);
        }
    }

    private void bleConnectErrorForResult() {
        if (baseCmd != null) {
            synchronized (baseCmd) {
                baseCmd.setCmdStatus(false);
                baseCmd.notify();
            }
        }
    }

    @SuppressWarnings({"deprecation"})
    public void init(EquilManager equilManager) {
        macAddrss = equilManager.getAddress();
        this.equilManager = equilManager;
        aapsLogger.debug(LTag.PUMPBTCOMM, "initGatt======= ");
        initAdapter();
        mGattCallback = new BluetoothGattCallback() {
            @Override public synchronized void onConnectionStateChange(BluetoothGatt gatt, int status, int i2) {
                super.onConnectionStateChange(gatt, status, i2);
                String str = i2 == 2 ? "CONNECTED" : "DISCONNECTED";
                String sb = "onConnectionStateChange called with status:" +
                        status +
                        ", state:" +
                        str +
                        "， i2: " +
                        i2 +
                        "， error133: ";
                aapsLogger.debug(LTag.PUMPCOMM, "onConnectionStateChange " + sb);
                mStatus = status;
                connecting = false;
                if (status == 133) {
                    unBond(macAddrss);
                    SystemClock.sleep(50);
                    aapsLogger.debug(LTag.PUMPCOMM, "error133 ");
                    if (baseCmd != null) {
                        baseCmd.setResolvedResult(ResolvedResult.CONNECT_ERROR);
                    }
                    bleConnectErrorForResult();
                    disconnect();
                    return;
                }
                if (i2 == 2) {
                    connected = true;
                    equilManager.setBluetoothConnectionState(BluetoothConnectionState.CONNECTED);
                    handler.removeMessages(TIME_OUT_CONNECT_WHAT);
                    if (mBluetoothGatt != null) {
                        mBluetoothGatt.discoverServices();
                    }
                    updateCmdStatus(ResolvedResult.FAILURE);
//                    rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED));
                } else if (i2 == 0) {
                    bleConnectErrorForResult();
                    disconnect();
                }

            }

            @Override public synchronized void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    aapsLogger.debug(LTag.PUMPCOMM, "onServicesDiscovered received: " + status);
                    return;
                }
                final BluetoothGattService service = gatt.getService(UUID.fromString(GattAttributes.SERVICE_RADIO));
                if (service != null) {
                    notifyChara = service.getCharacteristic(UUID.fromString(GattAttributes.NRF_UART_NOTIFY));
                    wirteChara = service.getCharacteristic(UUID.fromString(GattAttributes.NRF_UART_WIRTE));
//                    rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED));
                    openNotification();
                    requestHighPriority();
                }

            }

            @Override public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, final int status) {
                try {
                    SystemClock.sleep(EquilConst.EQUIL_BLE_WRITE_TIME_OUT);
                    writeData();
                } catch (Exception e) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Error", e);
                }
            }

            @Override public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, int status) {
                onCharacteristicChanged(gatt, characteristic);
            }

            @Override public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull final BluetoothGattCharacteristic characteristic) {
                requestHighPriority();
                decode(characteristic.getValue());
            }

            @Override public synchronized void onDescriptorWrite(BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, int status) {
                aapsLogger.debug(LTag.PUMPCOMM, "onDescriptorWrite received: " + status);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    aapsLogger.debug(LTag.PUMPCOMM, "onDescriptorWrite: Wrote GATT Descriptor successfully.");
                    ready();
                }
            }
        };
    }

    @SuppressWarnings({"deprecation"})
    public void openNotification() {
        aapsLogger.debug(LTag.PUMPCOMM, "openNotification: " + isConnected());
        boolean r0 = mBluetoothGatt.setCharacteristicNotification(notifyChara, true);
        if (r0) {
            BluetoothGattDescriptor descriptor = notifyChara.getDescriptor(GattAttributes.mCharacteristicConfigDescriptor);
            byte[] v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            descriptor.setValue(v);
            boolean flag = mBluetoothGatt.writeDescriptor(descriptor);
            aapsLogger.debug(LTag.PUMPCOMM, "openNotification: " + flag);
        }
    }

    @SuppressLint("MissingPermission") public void requestHighPriority() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        }
    }

    public void ready() {
        aapsLogger.debug(LTag.PUMPCOMM, "ready: " + "===" + baseCmd);
        runNext = false;
        dataList = new ArrayList<>();
        writeConf = false;
        if (baseCmd != null) {
            equilResponse = baseCmd.getEquilResponse();
            indexData = 0;
            writeData();
        }
    }


    public void nextCmd2() {
        dataList = new ArrayList<>();
        writeConf = false;
        aapsLogger.debug(LTag.PUMPCOMM,
                "nextCmd===== " + baseCmd.isEnd + "====");
        if (baseCmd != null) {
            runNext = false;
            equilResponse = baseCmd.getNextEquilResponse();
            aapsLogger.debug(LTag.PUMPCOMM,
                    "nextCmd===== " + baseCmd + "===" + equilResponse.getSend());
            if (equilResponse == null || equilResponse.getSend() == null || equilResponse.getSend().size() == 0) {
                aapsLogger.debug(LTag.PUMPCOMM,
                        "equilResponse is null");
                return;
            }
            indexData = 0;
            writeData();
        }
    }

    public void disconnect() {
        connected = false;
        startTrue = false;
        autoScan = false;
        equilManager.setBluetoothConnectionState(BluetoothConnectionState.DISCONNECTED);
        aapsLogger.debug(LTag.PUMPCOMM, "Closing GATT connection");
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
        if (baseCmd != null) {
            if (baseCmd instanceof CmdLargeBasalSet) {
                baseCmd = null;
                return;
            }
        }
        baseCmd = null;
        preCmd = null;
        rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED));
    }

    boolean autoScan = false;

    public void findEquil(String mac) {
        initAdapter();
        if (TextUtils.isEmpty(mac)) {
            return;
        }
        this.equilDevice = this.mBluetoothAdapter.getRemoteDevice(mac);
        if (connected) {
            return;
        }
        if (autoScan) {
            startScan();
        } else {
            connectEquil(equilDevice);
        }
    }

    public void connectEquil(BluetoothDevice device) {
//        disconnect();

        handler.postDelayed(() -> {
            if (device != null) {
                aapsLogger.debug(LTag.PUMPCOMM, "connectEquil======");
                mBluetoothGatt = device.connectGatt(context, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
                connecting = true;
            }
        }, 500);

    }


    BaseCmd baseCmd;
    BaseCmd preCmd;

    public void writeCmd(BaseCmd baseCmd) {
        aapsLogger.debug(LTag.PUMPCOMM, "writeCmd {}", baseCmd);
        this.baseCmd = baseCmd;
        String macAddress;
        if (baseCmd instanceof CmdPair) {
            macAddress = ((CmdPair) baseCmd).getAddress();
        } else if (baseCmd instanceof CmdDevicesOldGet) {
            macAddress = ((CmdDevicesOldGet) baseCmd).getAddress();
        } else {
            macAddress = equilManager.getAddress();
        }
        autoScan = baseCmd instanceof CmdModelGet || baseCmd instanceof CmdInsulinGet;
        if (connected && baseCmd.isPairStep()) {
            ready();
        } else if (connected && preCmd != null) {
            baseCmd.setRunCode(preCmd.getRunCode());
            baseCmd.setRunPwd(preCmd.getRunPwd());
            nextCmd2();
        } else {
            findEquil(macAddress);
            handler.sendEmptyMessageDelayed(TIME_OUT_CONNECT_WHAT, baseCmd.getConnectTimeOut());
        }
        preCmd = baseCmd;

    }

    public void readHistory(CmdHistoryGet baseCmd) {
        if (connected && preCmd != null) {
            baseCmd.setRunCode(preCmd.getRunCode());
            baseCmd.setRunPwd(preCmd.getRunPwd());
            this.baseCmd = baseCmd;
            nextCmd2();
            preCmd = baseCmd;
        } else {
            aapsLogger.debug(LTag.PUMPCOMM, "readHistory error");
        }
    }

    EquilResponse equilResponse;
    int indexData;

    public void writeData() {
        if (equilResponse != null) {
            long diff = System.currentTimeMillis() - equilResponse.getCmdCreateTime();
            if (diff < EquilConst.EQUIL_CMD_TIME_OUT) {
                if (indexData < equilResponse.getSend().size()) {
                    byte[] data = equilResponse.getSend().get(indexData).array();
                    write(data);
                    indexData++;
                } else {
                    aapsLogger.debug(LTag.PUMPCOMM, "indexData error ");
                }
            } else {
                aapsLogger.debug(LTag.PUMPCOMM, "equil cmd time out ");
            }
        } else {
            aapsLogger.debug(LTag.PUMPCOMM, "equilResponse is null ");
        }

    }

    @SuppressWarnings({"deprecation"})
    public void write(byte[] bytes) {
        if (wirteChara == null || mBluetoothGatt == null) {
            aapsLogger.debug(LTag.PUMPCOMM, "write disconnect ");
            disconnect();
            return;
        }
        if (bytes == null) {
            aapsLogger.debug(LTag.PUMPCOMM, "bytes is null ");
            return;
        }
        wirteChara.setValue(bytes);
        aapsLogger.debug(LTag.PUMPCOMM, "write: " + Utils.bytesToHex(bytes));
        mBluetoothGatt.writeCharacteristic(wirteChara);
    }

    List<String> dataList = new ArrayList<>();
    boolean runNext = false;

    public synchronized void decode(byte[] buffer) {
        String str = Utils.bytesToHex(buffer);
        aapsLogger.debug(LTag.PUMPCOMM, "decode=====" + str);
        EquilResponse response = baseCmd.decodeEquilPacket(buffer);
        if (response != null) {
            if (!writeConf) {
                writeConf(response);
            }
            dataList = new ArrayList<>();
        }
    }

    boolean writeConf;

    public void writeConf(EquilResponse equilResponse) {
        try {
            dataList = new ArrayList<>();
            this.equilResponse = equilResponse;
            indexData = 0;
            writeData();
            writeConf = false;
            runNext = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static final int TIME_OUT_WHAT = 0x12;
    public static final int TIME_OUT_CONNECT_WHAT = 0x13;
    public Handler handler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case TIME_OUT_WHAT:
                    stopScan();
                    break;
                case TIME_OUT_CONNECT_WHAT:
                    stopScan();
                    aapsLogger.debug(LTag.PUMPCOMM, "TIME_OUT_CONNECT_WHAT====");
                    if (baseCmd != null) {
                        baseCmd.setResolvedResult(ResolvedResult.CONNECT_ERROR);
                    }
                    bleConnectErrorForResult();
                    disconnect();
                    break;
            }
        }
    };
    private boolean startTrue;

    private void startScan() {
        macAddrss = equilManager.getAddress();
        if (TextUtils.isEmpty(macAddrss) || macAddrss == null) {
            return;
        }
        aapsLogger.debug(LTag.PUMPCOMM, "startScan====" + startTrue + "====" + macAddrss + "===");
        if (startTrue) {
            return;
        }
        BluetoothLeScanner bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner != null) {
            updateCmdStatus(ResolvedResult.NOT_FOUNT);
            bluetoothLeScanner.startScan(buildScanFilters(), buildScanSettings(), scanCallback);
        }
    }

    private void updateCmdStatus(ResolvedResult result) {
        if (baseCmd != null) {
            baseCmd.setResolvedResult(result);
        }
    }

    public void getEquilStatus() {
        aapsLogger.debug(LTag.PUMPCOMM, "getEquilStatus====" + startTrue + "====" + connected);
        if (startTrue || connected) {
            return;
        }
        autoScan = false;
        baseCmd = null;
        startScan();
    }

    private List<ScanFilter> buildScanFilters() {
        ArrayList<ScanFilter> scanFilterList = new ArrayList<>();
        if (TextUtils.isEmpty(macAddrss)) {
            return scanFilterList;
        }
        ScanFilter.Builder scanFilterBuilder = new ScanFilter.Builder();
        scanFilterBuilder.setDeviceAddress(macAddrss);
        scanFilterList.add(scanFilterBuilder.build());
        return scanFilterList;
    }

    private ScanSettings buildScanSettings() {
        ScanSettings.Builder builder = new ScanSettings.Builder();
        builder.setReportDelay(0);
        return builder.build();
    }

    ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            String name = result.getDevice().getName();
            if (!TextUtils.isEmpty(name)) {
                try {
                    bleHandler.post(() -> equilManager.decodeData(result.getScanRecord().getBytes()));

                    stopScan();
                    if (autoScan) {
                        updateCmdStatus(ResolvedResult.CONNECT_ERROR);
                        connectEquil(result.getDevice());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
        }

        @Override public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
        }
    };

    public void stopScan() {
        startTrue = false;
        handler.removeMessages(TIME_OUT_WHAT);
        if (mBluetoothAdapter == null) {
            return;
        }
        BluetoothLeScanner bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (isBluetoothAvailable()) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
    }

    public boolean isBluetoothAvailable() {
        return (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled() && mBluetoothAdapter.getState() == BluetoothAdapter.STATE_ON);
    }

}
