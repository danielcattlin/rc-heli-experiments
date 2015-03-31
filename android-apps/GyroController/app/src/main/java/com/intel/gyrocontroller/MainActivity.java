package com.intel.gyrocontroller;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Text;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity implements AdapterView.OnItemSelectedListener {
    private BluetoothAdapter mBluetoothAdapter;
    private TextView mStatus, mPowerText;
    private ArrayAdapter<String> mArrayAdapter;
    private Spinner mSpinner;
    private VerticalSeekBar mSeekBar;
    private Button mConnectButton, mDiscoverButton, mOK;
    private IntentFilter mFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
    private String mSelectedDevice = "";
    private static final int REQUEST_ENABLE_BT = 1;
    private static String mUniqueID = "00001101-0000-1000-8000-00805F9B34FB";
    private Context mContext;
    private SensorEncoder mSensorEncoder;
    private SensorHandler mSensorHandler;
    private byte[] latestInstruction = new byte[]{(byte)63, (byte)63, (byte)32, (byte)57};

    public final class SensorHandler extends Handler {
        public SensorHandler(Looper looper) { super(looper); }
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case SensorEncoder.NEW_DATA:
                    assignLatestInstruction(message.getData().getIntArray("data"));
                    break;
                case SensorEncoder.CALIBRATION_COMPLETE:
                    removeConfirmation();
                    break;
                default:
                    super.handleMessage(message);
            }
        }
    }

    // Create a BroadcastReceiver for ACTION_FOUND
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // Add the name and address to an array adapter to show in a ListView
                if(device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    mArrayAdapter.add(device.getName() + "@" + device.getAddress());
                    mArrayAdapter.notifyDataSetChanged();
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE);
                if(state == BluetoothDevice.BOND_BONDED) {
                    startConnection(mBluetoothAdapter.getRemoteDevice(
                            getDeviceAddress(mSelectedDevice)));
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = this;
        mStatus = (TextView)findViewById(R.id.status);
        mPowerText = (TextView)findViewById(R.id.power_text);
        mSpinner = (Spinner)findViewById(R.id.bluetooth_devices);
        mArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item);
        mSpinner.setAdapter(mArrayAdapter);
        mSpinner.setOnItemSelectedListener(this);
        registerReceiver(mReceiver, mFilter);
        mSeekBar = (VerticalSeekBar)findViewById(R.id.power_scroller);
        mConnectButton = (Button)findViewById(R.id.connect_btn);
        mConnectButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(
                        getDeviceAddress(mSelectedDevice));
                if(device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    mFilter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                    registerReceiver(mReceiver, mFilter);
                    device.createBond();
                } else {
                    startConnection(device);
                }
            }
        });
        mDiscoverButton = (Button)findViewById(R.id.scan_btn);
        mDiscoverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mBluetoothAdapter.startDiscovery();
                v.setClickable(false);
            }
        });
        mOK = (Button)findViewById(R.id.ok_button);
        mOK.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSensorEncoder.startCalibration();
            }
        });
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if(mBluetoothAdapter == null) {
            mStatus.setText(R.string.no_bluetooth);
        } else if(!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
        getPairedDevices();
        mSensorHandler = new SensorHandler(Looper.getMainLooper());
        mSensorEncoder = new SensorEncoder(this, mSensorHandler);
        mStatus.setText("Setting up…\nController must be calibrated." +
                "\nHold the device upright and press OK.");
        mOK.setVisibility(View.VISIBLE);
    }

    private void startConnection(BluetoothDevice device) {
        BlueConThread connection = new BlueConThread(device);
        connection.start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == REQUEST_ENABLE_BT) {
            if(resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
            } else {
                mStatus.setText(R.string.enable_bluetooth);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    public void getPairedDevices() {
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        // If there are paired devices
        if (pairedDevices.size() > 0) {
            mArrayAdapter.clear();
            // Loop through paired devices
            for (BluetoothDevice device : pairedDevices) {
                // Add the name and address to an array adapter to show in a ListView
                mArrayAdapter.add(device.getName() + "@" + device.getAddress());
                mArrayAdapter.notifyDataSetChanged();
            }
        }
    }

    public String getDeviceAddress(String info) {
        String address = info.split("@")[1];
        Log.i("state", address);
        return info.split("@")[1];
    }

    public void removeConfirmation() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mOK.setVisibility(View.INVISIBLE);
                mSeekBar.setVisibility(View.VISIBLE);
                mPowerText.setVisibility(View.VISIBLE);
                mStatus.setText("Controller ready");
            }
        });
    }

    public synchronized void assignLatestInstruction(int[] items) {
        int progress = (int)(mSeekBar.getProgress() * 1.27);

        latestInstruction[0] = (byte)((int)SensorEncoder.LR_MAP.get(items[0]));
        latestInstruction[1] = (byte)((int)SensorEncoder.FB_MAP.get(items[1]));
        latestInstruction[2] = (byte)progress;
        latestInstruction[3] = (byte)((int)SensorEncoder.YAW_MAP.get(items[2]));
    }

    public synchronized byte[] waitForLatestInstruction() {
        return latestInstruction;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        mConnectButton.setClickable(true);
        mSelectedDevice = parent.getItemAtPosition(position).toString();
        Log.i("address", mSelectedDevice);
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        mConnectButton.setClickable(false);
    }

    private class BlueAccThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;

        public BlueAccThread() {
            // Use a temporary object that is later assigned to mmServerSocket,
            // because mmServerSocket is final
            BluetoothServerSocket tmp = null;
            try {
                tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord("BlueGyro",
                        UUID.fromString(mUniqueID));
            } catch (IOException e) {
                e.printStackTrace();
            }
            mmServerSocket = tmp;
        }

        public void run() {
            BluetoothSocket socket = null;
            // Keep listening until exception occurs or a socket is returned
            while (true) {
                try {
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    break;
                }
                // If a connection was accepted
                if (socket != null) {
                    // Do work to manage the connection (in a separate thread)
                    BlueCommThread comms =  new BlueCommThread(socket);
                    comms.run();
                    try {
                        mmServerSocket.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
        }

        /** Will cancel the listening socket, and cause the thread to finish */
        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class BlueConThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public BlueConThread(BluetoothDevice device) {
            // Use a temporary object that is later assigned to mmSocket,
            // because mmSocket is final
            BluetoothSocket tmp = null;
            mmDevice = device;

            // Get a BluetoothSocket to connect with the given BluetoothDevice
            try {
                tmp = mmDevice.createInsecureRfcommSocketToServiceRecord(UUID.fromString(mUniqueID));
            } catch (IOException e) {
                e.printStackTrace();
            }
            mmSocket = tmp;
        }

        public void run() {
            // Cancel discovery because it will slow down the connection
            mBluetoothAdapter.cancelDiscovery();

            try {
                if(!mmSocket.isConnected())
                    mmSocket.connect();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(mContext, "Connected to device", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException connectException) {
                connectException.printStackTrace();
                // Unable to connect; close the socket and get out
                try {
                    mmSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return;
            }

            // Do work to manage the connection (in a separate thread)
            BlueCommThread comms =  new BlueCommThread(mmSocket);
            comms.start();
        }

        /** Will cancel an in-progress connection, and close the socket */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class BlueCommThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public BlueCommThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {

            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    sleep(110);
                    //Log.i("Sending", (char)latestInstruction[0] + "" +  (char)latestInstruction[1] + "" +
                    //        (char)latestInstruction[2] + "" + (char)latestInstruction[3]);
                    write(waitForLatestInstruction());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                }
            }
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
