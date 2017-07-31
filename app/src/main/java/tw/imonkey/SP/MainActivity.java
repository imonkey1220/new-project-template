/*
 * Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tw.imonkey.SP;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.android.things.pio.UartDevice;
import com.google.android.things.pio.UartDeviceCallback;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import de.greenrobot.event.EventBus;

/**
 * Example activity that provides a UART loopback on the
 * specified device. All data received at the specified
 * baud rate will be transferred back out the same UART.
 */
public class MainActivity extends Activity {
    private static final String TAG = MainActivity.class.getSimpleName();

    // UART Configuration Parameters
    String SP="UART0";
    private static final int BAUD_RATE = 115200;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = 1;

    private static final int CHUNK_SIZE = 512;

    String buffer = "" , CMD = "";
    //*******firebase*************
    String memberEmail,deviceId;
    public static final String devicePrefs = "devicePrefs";
    DatabaseReference mSETTINGS,mRequest,mAlert, mLog, mTX, mRX,mUsers,presenceRef,connectedRef;
    int logCount,RXCount,TXCount;
    int dataCount;
    int limit=1000;//max Logs (even number)
    public MySocketServer mServer;
    private static final int SERVER_PORT = 9402;
    Map<String, Object> alert = new HashMap<>();
    Map<String, Object> log = new HashMap<>();
    Map<String, Object> register = new HashMap<>();
    Map<String, String> RXCheck = new HashMap<>();
    ArrayList<String> users = new ArrayList<>();
    boolean restart=true;

    Gpio RESETGpio;
    String RESET="BCM26";

    //*******PLC****************
    //set serialport protocol parameters
    String STX=new String(new char[]{0x02});
    String ETX=new String(new char[]{0x03});
    String ENQ=new String(new char[]{0x05});
    String newLine=new String(new char[]{0x0D,0x0A});


    private PeripheralManagerService mService = new PeripheralManagerService();
    private HandlerThread mInputThread;
    private Handler mInputHandler;

    private UartDevice mSPDevice;

    private Runnable mTransferUartRunnable = new Runnable() {
        @Override
        public void run() {
            transferUartData();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        List<String> deviceList = mService.getUartDeviceList();
        if (deviceList.isEmpty()) {
            Log.i(TAG, "No UART port available on this device.");
        } else {
            Log.i(TAG, "List of available devices: " + deviceList);
        }

        // Create a background looper thread for I/O
        mInputThread = new HandlerThread("InputThread");
        mInputThread.start();
        mInputHandler = new Handler(mInputThread.getLooper());

        // Attempt to access the UART device
        try {
            openUart(SP, BAUD_RATE);
            // Read any initially buffered data
            mInputHandler.post(mTransferUartRunnable);
        } catch (IOException e) {
            Log.e(TAG, "Unable to open UART device", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SP Destroyed");

        // Terminate the worker thread
        if (mInputThread != null) {
            mInputThread.quitSafely();
        }

        // Attempt to close the UART device
        try {
            closeUart();
        } catch (IOException e) {
            Log.e(TAG, "Error closing UART device:", e);
        }
        EventBus.getDefault().unregister(this);
        if ( RESETGpio != null) {
            try {
                RESETGpio.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                RESETGpio = null;
            }
        }
    }

    /**
     * Callback invoked when UART receives new incoming data.
     */
    private UartDeviceCallback mCallback = new UartDeviceCallback() {
        @Override
        public boolean onUartDeviceDataAvailable(UartDevice uart) {
            // Queue up a data transfer
            transferUartData();
            //Continue listening for more interrupts
            return true;
        }

        @Override
        public void onUartDeviceError(UartDevice uart, int error) {
            Log.w(TAG, uart + ": Error event " + error);
        }
    };

    /* Private Helper Methods */

    /**
     * Access and configure the requested UART device for 8N1.
     *
     * @param name Name of the UART peripheral device to open.
     * @param baudRate Data transfer rate. Should be a standard UART baud,
     *                 such as 9600, 19200, 38400, 57600, 115200, etc.
     *
     * @throws IOException if an error occurs opening the UART port.
     */
    private void openUart(String name, int baudRate) throws IOException {
        mSPDevice = mService.openUartDevice(name);
        // Configure the UART
        mSPDevice.setBaudrate(baudRate);
        mSPDevice.setDataSize(DATA_BITS);
        mSPDevice.setParity(UartDevice.PARITY_NONE);
        mSPDevice.setStopBits(STOP_BITS);

        mSPDevice.registerUartDeviceCallback(mCallback, mInputHandler);
    }

    /**
     * Close the UART device connection, if it exists
     */
    private void closeUart() throws IOException {
        if (mSPDevice != null) {
            mSPDevice.unregisterUartDeviceCallback(mCallback);
            try {
                mSPDevice.close();
            } finally {
                mSPDevice = null;
            }
        }
    }

    /**
     * Loop over the contents of the UART RX buffer, transferring each
     * one back to the TX buffer to create a loopback service.
     *
     * Potentially long-running operation. Call from a worker thread.
     */
    private void transferUartData() {
        if (mSPDevice != null) {
            // Loop until there is no more data in the RX buffer.
            try {
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                while ((read = mSPDevice.read(buffer, buffer.length)) > 0) {
                    mSPDevice.write(buffer, read);//todo:

                }
            } catch (IOException e) {
                Log.w(TAG, "Unable to transfer data over UART", e);
            }
        }
    }
    private void init(){
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
        EventBus.getDefault().register(this);
        SharedPreferences settings = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE);
        memberEmail = settings.getString("memberEmail", null);
        deviceId = settings.getString("deviceId", null);
        logCount = settings.getInt("logCount",0);

        if (memberEmail == null) {
            memberEmail = "test@po-po.com";
            deviceId = "RPI3_SP_test";
            DatabaseReference mAddTestDevice=FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId);
            Map<String, Object> addTest = new HashMap<>();
            addTest.put("companyId","po-po") ;
            addTest.put("device","rpi3SPtest");
            addTest.put("deviceType","RS232智慧機"); //GPIO智慧機
            addTest.put("description","Android things rpi3SP test");
            addTest.put("masterEmail",memberEmail) ;
            addTest.put("timeStamp", ServerValue.TIMESTAMP);
            addTest.put("topics_id",deviceId);
            Map<String, Object> user = new HashMap<>();
            user.put(memberEmail.replace(".","_"),memberEmail);
            addTest.put("users",user);
            mAddTestDevice.setValue(addTest);
            startServer();
        }
        mRX = FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/RX/");
        mTX = FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/TX/");
        mRequest= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/SETTINGS/CMD/");
        mSETTINGS = FirebaseDatabase.getInstance().getReference("/DEVICE/" + deviceId + "/SETTINGS");
        mAlert= FirebaseDatabase.getInstance().getReference("/DEVICE/"+ deviceId + "/alert");
        mLog=FirebaseDatabase.getInstance().getReference("/DEVICE/" + deviceId + "/LOG/");
        mUsers= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/users/");
        mUsers.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                users.clear();
                for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                    users.add(childSnapshot.getValue().toString());
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
        PeripheralManagerService mRESETservice = new PeripheralManagerService();
        try {
            RESETGpio = mRESETservice.openGpio(RESET);
            RESETGpio.setDirection(Gpio.DIRECTION_IN);
            RESETGpio.setEdgeTriggerType(Gpio.EDGE_BOTH);
            RESETGpio.registerGpioCallback(new GpioCallback() {
                @Override
                public boolean onGpioEdge(Gpio gpio) {
                    try {
                        if (RESETGpio.getValue()){
                            startServer();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    return true;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void alert(final String message){
        mSETTINGS.child("notify").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if(snapshot.child("SMS").getValue()!=null) {
                    for (String email : users) {
                        NotifyUser.SMSPUSH(deviceId, email, message);
                    }
                }
                if (snapshot.child("EMAIL").getValue() != null) {
                    for (String email : users) {
                        NotifyUser.emailPUSH(deviceId, email, message);
                    }
                }
                if (snapshot.child("PUSH").getValue() != null) {
                    for (String email : users) {
                        NotifyUser.IIDPUSH(deviceId, email, "智慧機通知", message);
                    }
                    NotifyUser.topicsPUSH(deviceId, memberEmail, "智慧機通知", message);
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });

        alert.clear();
        alert.put("message","Device:"+message);
        alert.put("timeStamp", ServerValue.TIMESTAMP);
        mAlert.setValue(alert);
    }
    private void log(String message) {
        log.clear();
        log.put("message", "Device:"+message);
        log.put("memberEmail", memberEmail);
        log.put("timeStamp", ServerValue.TIMESTAMP);
        mLog.push().setValue(log);
        logCount++;
        if (logCount>(limit+(limit)/2)){
            dataLimit(mLog,limit);
            logCount=limit;
        }
        SharedPreferences.Editor editor = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE).edit();
        editor.putInt("logCount",logCount);
        editor.apply();
    }
    private void dataLimit(final DatabaseReference mData,int limit) {
        mData.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                dataCount=(int)(snapshot.getChildrenCount());
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });
        if((dataCount-limit)>0) {
            mData.orderByKey().limitToFirst(dataCount - limit)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot snapshot) {
                            for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                                mData.child(childSnapshot.getKey()).removeValue();
                            }
                        }

                        @Override
                        public void onCancelled(DatabaseError databaseError) {
                        }
                    });
        }
    }
    //device online check
    private void deviceOnline(){
        presenceRef= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/connection");//for log activity
        presenceRef.setValue(true);
        presenceRef.onDisconnect().setValue(null);
        connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectedRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                if (connected) {
                    presenceRef.setValue(true);
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {
            }
        });
    }

    // websocket server
    private void startServer() {
        InetAddress inetAddress = getInetAddress();
        if (inetAddress == null) {
            return;
        }

        mServer = new MySocketServer(new InetSocketAddress(inetAddress.getHostAddress(), SERVER_PORT));
        mServer.start();
    }

    private static InetAddress getInetAddress() {
        try {
            for (Enumeration en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface networkInterface = (NetworkInterface) en.nextElement();

                for (Enumeration enumIpAddr = networkInterface.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = (InetAddress) enumIpAddr.nextElement();

                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress;
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }

        return null;
    }
    @SuppressWarnings("UnusedDeclaration")
    public void onEvent(SocketMessageEvent event) {
        String message = event.getMessage();
        String[] mArray = message.split(",");
        if (mArray.length==2) {
            SharedPreferences.Editor editor = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE).edit();
            editor.putString("memberEmail", mArray[0]);
            editor.putString("deviceId", mArray[1]);
            editor.apply();
            mServer.sendMessage("echo: " + message);
            Intent i;
            i = new Intent(this,MainActivity.class);
            startActivity(i);
            alert("IO智慧機設定完成!");
        }
    }
}
