package com.physicaloid.helicontrol;

import java.io.UnsupportedEncodingException;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.RadioGroup.OnCheckedChangeListener;
import android.widget.SeekBar;
import android.widget.TextView;

import com.physicaloid.helicontrol.inputmanagercompat.InputManagerCompat;
import com.physicaloid.lib.Physicaloid;
import com.physicaloid.lib.usb.driver.uart.ReadLisener;
import com.physicaloid.lib.usb.driver.uart.UartConfig;

public class HeliControl extends Activity implements SeekBar.OnSeekBarChangeListener, InputManagerCompat.InputDeviceListener {
    private static final String TAG = HeliControl.class.getSimpleName();

    Button btOpen, btClose, btWrite, btTest;
    EditText etWrite;
    TextView tvRead, tvTimer;
    RadioGroup rgChannel;
    SeekBar sbPowerBar;
    RadioButton rb0, rb1, rb2, rb3;

    Physicaloid mPhysicaloid;

    char channelA = 1;
    char channelB = 0;

    private static final int DPAD_STATE_LEFT = 1 << 0;
    private static final int DPAD_STATE_RIGHT = 1 << 1;
    private static final int DPAD_STATE_UP = 1 << 2;
    private static final int DPAD_STATE_DOWN = 1 << 3;

    int power;
    int leftRight;
    int upDown;
    int adjustments;

    private long mLastStepTime;

    private InputManagerCompat mInputManager;

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        // Turn on and off animations based on the window focus.
        // Alternately, we could update the game state using the Activity
        // onResume()
        // and onPause() lifecycle events.
        if (hasWindowFocus) {
            mLastStepTime = SystemClock.uptimeMillis();
            mInputManager.onResume();
        } else {
            mInputManager.onPause();
        }

        super.onWindowFocusChanged(hasWindowFocus);
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        power = progress;
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }

    long startTime = 0;

    //runs without a timer by reposting this handler at the end of the runnable
    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {

        @Override
        public void run() {
            long millis = System.currentTimeMillis() - startTime;
            int seconds = (int) (millis / 1000);
            int minutes = seconds / 60;
            int ms = (int) (millis % 100);
            seconds = seconds % 60;
            tvTimer.setText(String.format("%d:%02d:%02d", minutes, seconds,ms));
            timerHandler.postDelayed(this, 50);
            sendCommand(power, leftRight, upDown, adjustments);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mInputManager = InputManagerCompat.Factory.getInputManager(this.getApplicationContext());
        mInputManager.registerInputDeviceListener(this, null);

        setContentView(R.layout.activity_helicontrol);

        btOpen  = (Button) findViewById(R.id.btOpen);
        btClose = (Button) findViewById(R.id.btClose);
        btWrite = (Button) findViewById(R.id.btWrite);
        btTest = (Button) findViewById(R.id.btTest);
        etWrite = (EditText) findViewById(R.id.etWrite);
        tvRead  = (TextView) findViewById(R.id.tvRead);

        tvTimer = (TextView) findViewById(R.id.tvTimer);
        rgChannel = (RadioGroup) findViewById(R.id.rgChannel);
        rb0 = (RadioButton) findViewById(R.id.rb0);
        rb1 = (RadioButton) findViewById(R.id.rb1);
        rb2 = (RadioButton) findViewById(R.id.rb2);
        rb3 = (RadioButton) findViewById(R.id.rb3);

        sbPowerBar = (SeekBar)findViewById(R.id.powerSeekBar); // make seekbar object
        sbPowerBar.setOnSeekBarChangeListener(this);

        setEnabledUi(false);

        mPhysicaloid = new Physicaloid(this);

        rgChannel.setOnCheckedChangeListener(new OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                switch (checkedId) {
                    case R.id.rb0:
                        channelA=1;
                        channelB=0;
                        break;
                    case R.id.rb1:
                        channelA=0;
                        channelB=1;
                        break;
                    case R.id.rb2:
                        channelA=0;
                        channelB=0;
                        break;
                    case R.id.rb3:
                        channelA=1;
                        channelB=1;
                        break;
                    default:
                        tvAppend(tvRead, "no set");
                        break;
                }
            }
        });


        //****************************************************************
        // TODO : register intent filtered actions for device being attached or detached

        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiver, filter);
        //****************************************************************

        try {
            openDevice();
        } catch (NullPointerException e) {

        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //****************************************************************
        // TODO : unregister the intent filtered actions
        unregisterReceiver(mUsbReceiver);
        //****************************************************************
    }

    public void onClickOpen(View v) {
        openDevice();
    }

    private void openDevice() {
        if (!mPhysicaloid.isOpened()) {
            UartConfig uartConfig = new UartConfig(115200, UartConfig.DATA_BITS8, UartConfig.STOP_BITS1, UartConfig.PARITY_NONE, false, false);
            if (mPhysicaloid.open(uartConfig)) { // default 9600bps
                setEnabledUi(true);

                mPhysicaloid.addReadListener(new ReadLisener() {
                    String readStr;

                    // callback when reading one or more size buffer
                    @Override
                    public void onRead(int size) {
                        byte[] buf = new byte[size];

                        mPhysicaloid.read(buf, size);
                        try {
                            readStr = new String(buf, "UTF-8");
                        } catch (UnsupportedEncodingException e) {
                            Log.e(TAG, e.toString());
                            return;
                        }

                        // UI thread
                        tvAppend(tvRead, readStr);
                        tvRead.scrollTo(0, Integer.MAX_VALUE);
                    }
                });
            }
        }
    }

    public void onClickClose(View v) {
        closeDevice();
    }

    private void closeDevice() {
        if(mPhysicaloid.close()) {
            setEnabledUi(false);
            mPhysicaloid.clearReadListener();
        }
    }

    public static final float MIN_THROTTLE = 20;
    public static final float MAX_THROTTLE = 185;

    public void setPower(float fPower) {

        if (fPower>=0)
            power=0;
        else
            power = (int) (MIN_THROTTLE - fPower *(MAX_THROTTLE-MIN_THROTTLE));

        System.out.println(fPower+" fPower power "+power+ " ["+MIN_THROTTLE+"/"+MAX_THROTTLE+"]");
    };

    public void setLeftRight(float axis) {
        leftRight = (int) (axis*31);
    };

    public void setUpDown(float axis) {
        upDown = (int) (axis*31);
    };

    public void increaseAdjustment() {
        adjustments++;
        if (adjustments>31)
            adjustments=0;
    };

    public void decreaseAdjustment() {
        adjustments--;
        if (adjustments<0)
            adjustments=31;
    };

    int test = 0;
    public void sendCommand(int power, int leftright, int updown, int adj) {
        byte[] buf = new byte[7];
        buf[0]='B';
        buf[1]= (byte) power; // POWER
        buf[2]= (byte) leftright; // Left
        buf[3]= (byte) updown; // Right
        buf[4]= (byte) adj; // Adjustements
        buf[5]= (byte) (channelA | channelB<<1 ); //| channelC<<2
        buf[6]='E';
        mPhysicaloid.write(buf, buf.length);
    }

    public void onClickTest(View v) {


        switch (test) {
            case 0:
                sendCommand(0, 0, 0, 0);
                break;
            case 1:
                sendCommand(80, 0, 0, 0);
                break;
            case 2:
                sendCommand(185, 0, 0, 0);
                break;
            case 3:
                sendCommand(15, 0, 0, 0);
                break;
            default:
                test =0;
                return;
        }
        test++;
    }

    public void onClickWrite(View v) {
        String str = etWrite.getText().toString();
        if(str.length()>0) {
            byte[] buf = str.getBytes();
            mPhysicaloid.write(buf, buf.length);
        }
    }

    String oldText = new String();

    Handler mHandler = new Handler();
    private void tvAppend(TextView tv, CharSequence text) {
        final TextView ftv = tv;
        final CharSequence ftext = text;
        mHandler.post(new Runnable() {

            @Override
            public void run() {
                if (ftv.getLineCount() == ftv.getMaxLines())
                    ftv.setText("");

                if (!oldText.contentEquals(ftext.toString())) {
                   ftv.append(ftext);
                   oldText = ftext.toString();
                }
            }
        });
    }

    private void setEnabledUi(boolean on) {
        if(on) {
            startTime = System.currentTimeMillis();
            timerHandler.postDelayed(timerRunnable, 0);

            btOpen.setEnabled(false);
            btOpen.setVisibility(View.GONE);
            btTest.setEnabled(true);
            btClose.setEnabled(true);
            btClose.setVisibility(View.VISIBLE);
            btWrite.setEnabled(true);
            etWrite.setEnabled(true);
            tvRead.setEnabled(true);
            rb0.setEnabled(true);
            rb1.setEnabled(true);
            rb2.setEnabled(true);
            rb3.setEnabled(true);

        } else {
            timerHandler.removeCallbacks(timerRunnable);

            btOpen.setEnabled(true);
            btOpen.setVisibility(View.VISIBLE);
            btTest.setEnabled(false);
            btClose.setEnabled(false);
            btClose.setVisibility(View.GONE);
            btWrite.setEnabled(false);
            etWrite.setEnabled(false);
            tvRead.setEnabled(false);
            rb0.setEnabled(false);
            rb1.setEnabled(false);
            rb2.setEnabled(false);
            rb3.setEnabled(false);
        }
    }

    //****************************************************************
    // TODO : get intent when a USB device attached
    protected void onNewIntent(Intent intent) {
        String action = intent.getAction();

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            openDevice();
        }
    };
    //****************************************************************

    //****************************************************************
    // TODO : get intent when a USB device detached
    BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                closeDevice();
            }
        }
    };
    //****************************************************************

    // on AndroidManifest.xml
    // TODO : add usb device attached intent
    // TODO : add device filter for usb device being attached

    private void processJoystickInput(MotionEvent event,
                                      int historyPos) {

        InputDevice mInputDevice = event.getDevice();

        float x = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_X, historyPos);
        float x2 = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_HAT_X, historyPos);
        float x3 = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_Z, historyPos);

        float y = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_Y, historyPos);
        float y2 = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_HAT_Y, historyPos);
        float y3 = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_RZ, historyPos);

        float th = getCenteredAxis(event, mInputDevice, MotionEvent.AXIS_THROTTLE, historyPos);

        float rTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        float lTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);

        System.out.println(" AXIS ("+x+","+y+") AXIS2 ("+x3+","+y3+") rTrigger " + rTrigger+ " lTrigger "+lTrigger);

        if (lTrigger!=0)
            setPower(-lTrigger);
        else
            setPower(y);

        setLeftRight(x3);
        setUpDown(y3);
    }

    private static float getCenteredAxis(MotionEvent event, InputDevice device,
                                         int axis, int historyPos) {
        final InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range != null) {
            final float flat = range.getFlat();
            final float value = historyPos < 0 ? event.getAxisValue(axis)
                    : event.getHistoricalAxisValue(axis, historyPos);

            // Ignore axis values that are within the 'flat' region of the
            // joystick axis center.
            // A joystick at rest does not always report an absolute position of
            // (0,0).
            if (Math.abs(value) > flat) {
                return value;
            }
        }
        return 0;
    }

    /**
     * Any gamepad button + the spacebar or DPAD_CENTER will be used as the fire
     * key.
     *
     * @param keyCode
     * @return true of it's a fire key.
     */
    private static boolean isFireKey(int keyCode) {
        return KeyEvent.isGamepadButton(keyCode)
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_SPACE;
    }

    // Iterate through the input devices, looking for controllers. Create a ship
    // for every device that reports itself as a gamepad or joystick.
    void findControllersAndAttachHelicopters() {
        int[] deviceIds = mInputManager.getInputDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice dev = mInputManager.getInputDevice(deviceId);
            int sources = dev.getSources();
            // if the device is a gamepad/joystick, create a ship to represent it
            if (((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
                    ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)) {
                // if the device has a gamepad or joystick
                ;
            }
        }
    }

    private int mDPadState;

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {

        // Handle keys going up.
        boolean handled = false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                System.out.println("onKeyUp DPAD_STATE_LEFT ");
                mDPadState &= ~DPAD_STATE_LEFT;
                handled = true;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                System.out.println("onKeyUp DPAD_STATE_RIGHT ");
                mDPadState &= ~DPAD_STATE_RIGHT;
                handled = true;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                System.out.println("onKeyUp DPAD_STATE_UP ");
                mDPadState &= ~DPAD_STATE_UP;
                handled = true;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                System.out.println("onKeyUp DPAD_STATE_DOWN ");
                mDPadState &= ~DPAD_STATE_DOWN;
                handled = true;
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                System.out.println("onKeyUp KEYCODE_BUTTON_X X ");
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                System.out.println("onKeyUp KEYCODE_BUTTON_Y Circle ");
                increaseAdjustment();
                break;
            case KeyEvent.KEYCODE_BUTTON_A:
                System.out.println("onKeyUp KEYCODE_BUTTON_A Square ");
                decreaseAdjustment();
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
                System.out.println("onKeyUp KEYCODE_BUTTON_B Triangle ");
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                System.out.println("onKeyUp KEYCODE_BUTTON_R1 ");
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
                System.out.println("onKeyUp KEYCODE_BUTTON_L1 ");
                break;
            case KeyEvent.KEYCODE_BUTTON_R2:
                System.out.println("onKeyUp KEYCODE_BUTTON_R2 ");
                break;
            case KeyEvent.KEYCODE_BUTTON_START:
                System.out.println("onKeyUp KEYCODE_BUTTON_START ");
                break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                System.out.println("onKeyUp KEYCODE_BUTTON_SELECT ");
                break;
            default:

                System.out.println("onKeyUp  "+keyCode);
                if (isFireKey(keyCode)) {
                    handled = true;
                }
                break;
        }

        return handled;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Handle DPad keys and fire button on initial down but not on
        // auto-repeat.
        boolean handled = false;
        if (event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    mDPadState |= DPAD_STATE_LEFT;
                    handled = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    mDPadState |= DPAD_STATE_RIGHT;
                    handled = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    mDPadState |= DPAD_STATE_UP;
                    handled = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    mDPadState |= DPAD_STATE_DOWN;
                    handled = true;
                    break;
                default:
                    if (isFireKey(keyCode)) {
                        handled = true;
                    }
                    break;
            }
        }
        return handled;
    }

    InputDevice mLastInputDevice;

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        mInputManager.onGenericMotionEvent(event);

        // Check that the event came from a joystick or gamepad since a generic
        // motion event could be almost anything. API level 18 adds the useful
        // event.isFromSource() helper function.

        if (event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)) {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                mLastInputDevice=event.getDevice();
                processJoystickInput(event, -1);

                //if (mDPadState != 0)  return true;
                return true;
            }
        }

        int eventSource = event.getSource();
        if ((((eventSource & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
                ((eventSource & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK))
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            int id = event.getDeviceId();
            System.out.println("onGenericMotionEvent "+id+" action "+event.getAction()+ " eventSource "+eventSource);
            if (-1 != id) {

            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        System.out.println("onInputDeviceAdded "+deviceId);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        System.out.println("onInputDeviceChanged "+deviceId);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        System.out.println("onInputDeviceRemoved "+deviceId);
    }
}
