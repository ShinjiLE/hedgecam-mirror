package com.caddish_hedgehog.hedgecam2;

import com.caddish_hedgehog.hedgecam2.CameraController.CameraController;
import com.caddish_hedgehog.hedgecam2.CameraController.CameraControllerManager2;
import com.caddish_hedgehog.hedgecam2.Preview.Preview;
import com.caddish_hedgehog.hedgecam2.Preview.VideoProfile;
import com.caddish_hedgehog.hedgecam2.Sound;
import com.caddish_hedgehog.hedgecam2.UI.FileListDialog;
import com.caddish_hedgehog.hedgecam2.UI.MainUI;
import com.caddish_hedgehog.hedgecam2.UI.PopupView;
import com.caddish_hedgehog.hedgecam2.Utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.hardware.camera2.CameraMetadata;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.CamcorderProfile;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.renderscript.RenderScript;
import android.renderscript.RSInvalidStateException;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.ZoomControls;

/** The main Activity for Open Camera.
 */
public class MainActivity extends Activity implements AudioListener.AudioListenerCallback {
	private static final String TAG = "HedgeCam/MainActivity";
	
	private static final int SAF_CODE_SAVE_FOLDER = 42;
	private static final int SAF_CODE_SAVE_VIDEO_FOLDER = 45;
	private static final int SAF_CODE_GHOST_IMAGE = 43;
	public static final int SAF_CODE_OPEN_BACKUP = 44;
	public static final int SAF_CODE_OPEN_XML_SETTINGS = 45;

	private SensorManager mSensorManager;
	private Sensor mSensorAccelerometer;
	private Sensor mSensorMagnetic;

	private Resources resources;
	private SharedPreferences sharedPreferences;

	private MainUI mainUI;
	private TextFormatter textFormatter;
	private MyApplicationInterface applicationInterface;
	private Preview preview;
	private OrientationEventListener orientationEventListener;
	private int large_heap_memory;
	private boolean supports_auto_stabilise;
	private boolean supports_force_video_4k;
	private boolean supports_camera2;
	private SaveLocationHistory save_location_history; // save location for non-SAF
	private SaveLocationHistory save_location_history_saf; // save location for SAF (only initialised when SAF is used)
	private boolean saf_dialog_from_preferences; // if a SAF dialog is opened, this records whether we opened it from the Preferences
	private boolean camera_in_background; // whether the camera is covered by a fragment/dialog (such as settings or folder picker)
	private GestureDetector gestureDetector;
	private boolean screen_is_locked; // whether screen is "locked" - this is Open Camera's own lock to guard against accidental presses, not the standard Android lock
	private final Map<Integer, Bitmap> preloaded_bitmap_resources = new Hashtable<>();
	private ValueAnimator gallery_save_anim;
	
	private AudioListener audio_listener;
	private int audio_noise_sensitivity = -1;
	private SpeechRecognizer speechRecognizer;
	private boolean speechRecognizerIsStarted;
	private Handler speech_recognizer_handler = null;
	private Runnable speech_recognizer_runnable = null;
	
	private final ToastBoxer switch_video_toast = new ToastBoxer();
	private final ToastBoxer screen_locked_toast = new ToastBoxer();
	private final ToastBoxer changed_auto_stabilise_toast = new ToastBoxer();
	private final ToastBoxer exposure_lock_toast = new ToastBoxer();
	private final ToastBoxer audio_control_toast = new ToastBoxer();
	private boolean block_startup_toast = false; // used when returning from Settings/Popup - if we're displaying a toast anyway, don't want to display the info toast too
	
	private boolean fullscreen = false;

	private final int[] buttons_events = {
		KeyEvent.KEYCODE_HEADSETHOOK,
		KeyEvent.KEYCODE_VOLUME_UP,
		KeyEvent.KEYCODE_VOLUME_DOWN,
		KeyEvent.KEYCODE_MEDIA_PREVIOUS,
		KeyEvent.KEYCODE_MEDIA_NEXT,
		KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
		KeyEvent.KEYCODE_MEDIA_STOP,
		KeyEvent.KEYCODE_CAMERA,
		KeyEvent.KEYCODE_FOCUS,
		KeyEvent.KEYCODE_ZOOM_IN,
		KeyEvent.KEYCODE_ZOOM_OUT,
		KeyEvent.KEYCODE_SEARCH
	};
	
	private final String[] buttons_preferences = {
		"preference_button_headset",
		"preference_button_vol_up",
		"preference_button_vol_down",
		"preference_button_prev",
		"preference_button_next",
		"preference_button_play",
		"preference_button_stop",
		"preference_button_camera",
		"preference_button_focus",
		"preference_button_zoom_in",
		"preference_button_zoom_out",
		"preference_button_search"
	};


	// for testing; must be volatile for test project reading the state
	public boolean is_test; // whether called from OpenCamera.test testing
	public volatile Bitmap gallery_bitmap;
	public volatile boolean test_low_memory;
	public volatile boolean test_have_angle;
	public volatile float test_angle;
	public volatile String test_last_saved_image;

	public boolean selfie_mode = false;
	public boolean audio_control = false;
	public boolean set_expo_metering_area = false;

	private Handler immersive_timer_handler = null;
	private Runnable immersive_timer_runnable = null;

	private RenderScript rs; // lazily created, so we don't take up resources if application isn't using HDR
	
	private Handler reset_zoom_handler = null;
	private Runnable reset_zoom_runnable = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		long debug_time = 0;
		if( MyDebug.LOG ) {
			Log.d(TAG, "onCreate: " + this);
			debug_time = System.currentTimeMillis();
		}
		super.onCreate(savedInstanceState);

		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 ) {
			Window win = getWindow();
			WindowManager.LayoutParams winParams = win.getAttributes();
			winParams.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT;
//			winParams.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE;
			win.setAttributes(winParams);
		}

		setContentView(R.layout.activity_main);
		PreferenceManager.setDefaultValues(this, R.xml.preferences, false); // initialise any unset preferences to their default values
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after setting default preference values: " + (System.currentTimeMillis() - debug_time));

		if( getIntent() != null && getIntent().getExtras() != null ) {
			// whether called from testing
			is_test = getIntent().getExtras().getBoolean("test_project");
			if( MyDebug.LOG )
				Log.d(TAG, "is_test: " + is_test);
		}
		if( getIntent() != null && getIntent().getExtras() != null ) {
			// whether called from Take Photo widget
			if( MyDebug.LOG )
				Log.d(TAG, "take_photo?: " + getIntent().getExtras().getBoolean(TakePhoto.TAKE_PHOTO));
		}
		resources = getResources();
		
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		Prefs.init(this, sharedPreferences);
		Utils.init(this);

		boolean has_done_first_time = Prefs.contains(Prefs.DONE_FIRST_TIME);
		if( MyDebug.LOG )
			Log.d(TAG, "has_done_first_time: " + has_done_first_time);
		if( !has_done_first_time ) {
			setDeviceDefaults();
		}

		// determine whether we should support "auto stabilise" feature
		// risk of running out of memory on lower end devices, due to manipulation of large bitmaps
		ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
		if( MyDebug.LOG ) {
			Log.d(TAG, "standard max memory = " + activityManager.getMemoryClass() + "MB");
			Log.d(TAG, "large max memory = " + activityManager.getLargeMemoryClass() + "MB");
		}
		large_heap_memory = activityManager.getLargeMemoryClass();
		if( large_heap_memory >= 128 ) {
			supports_auto_stabilise = true;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "supports_auto_stabilise? " + supports_auto_stabilise);

		// hack to rule out phones unlikely to have 4K video, so no point even offering the option!
		// both S5 and Note 3 have 128MB standard and 512MB large heap (tested via Samsung RTL), as does Galaxy K Zoom
		// also added the check for having 128MB standard heap, to support modded LG G2, which has 128MB standard, 256MB large - see https://sourceforge.net/p/opencamera/tickets/9/
		if( activityManager.getMemoryClass() >= 128 || activityManager.getLargeMemoryClass() >= 512 ) {
			supports_force_video_4k = true;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "supports_force_video_4k? " + supports_force_video_4k);

		// set up components
		mainUI = new MainUI(this);
		mainUI.updateOrientationPrefs();
		applicationInterface = new MyApplicationInterface(this, savedInstanceState);
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after creating application interface: " + (System.currentTimeMillis() - debug_time));
		textFormatter = new TextFormatter(this);

		// determine whether we support Camera2 API
		initCamera2Support();

		// set up window flags for normal operation
		setWindowFlagsForCamera();
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after setting window flags: " + (System.currentTimeMillis() - debug_time));

		save_location_history = new SaveLocationHistory(this, "save_location_history", getStorageUtils().getSaveLocation());
		if( applicationInterface.getStorageUtils().isUsingSAF() ) {
			if( MyDebug.LOG )
				Log.d(TAG, "create new SaveLocationHistory for SAF");
			save_location_history_saf = new SaveLocationHistory(this, "save_location_history_saf", getStorageUtils().getSaveLocationSAF());
		}
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after updating folder history: " + (System.currentTimeMillis() - debug_time));

		// set up sensors
		mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

		// accelerometer sensor (for device orientation)
		if( mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "found accelerometer");
			mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		}
		else {
			if( MyDebug.LOG )
				Log.d(TAG, "no support for accelerometer");
		}
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after creating accelerometer sensor: " + (System.currentTimeMillis() - debug_time));

		// magnetic sensor (for compass direction)
		if( mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "found magnetic sensor");
			mSensorMagnetic = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		}
		else {
			if( MyDebug.LOG )
				Log.d(TAG, "no support for magnetic sensor");
		}
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after creating magnetic sensor: " + (System.currentTimeMillis() - debug_time));

		// set up the camera and its preview
		preview = new Preview(applicationInterface, ((ViewGroup) this.findViewById(R.id.preview)));
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after creating preview: " + (System.currentTimeMillis() - debug_time));
		mainUI.setPreview(preview);

		// initialise on-screen button visibility
//		mainUI.showGUI(true);
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after setting button visibility: " + (System.currentTimeMillis() - debug_time));
		View pauseVideoButton = findViewById(R.id.pause_video);
		pauseVideoButton.setVisibility(View.INVISIBLE);

		// We initialise optional controls to invisible, so they don't show while the camera is opening - the actual visibility is
		// set in cameraSetup().
		// Note that ideally we'd set this in the xml, but doing so for R.id.zoom causes a crash on Galaxy Nexus startup beneath
		// setContentView()!
		// To be safe, we also do so for take_photo and zoom_seekbar (we already know we've had no reported crashes for focus_seekbar,
		// however).
		View takePhotoButton = findViewById(R.id.take_photo);
		takePhotoButton.setVisibility(View.INVISIBLE);
		View zoomControls = findViewById(R.id.zoom);
		zoomControls.setVisibility(View.INVISIBLE);
		View zoomSeekbar = findViewById(R.id.zoom_seekbar);
		zoomSeekbar.setVisibility(View.INVISIBLE);

		// listen for orientation event change
		orientationEventListener = new OrientationEventListener(this) {
			@Override
			public void onOrientationChanged(int orientation) {
				MainActivity.this.mainUI.onOrientationChanged(orientation);
			}
		};
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after setting orientation event listener: " + (System.currentTimeMillis() - debug_time));

		// set up gallery button long click
		View galleryButton = findViewById(R.id.gallery);
		galleryButton.setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				//Utils.showToast(null, "Long click");
				longClickedGallery();
				return true;
			}
		});
		((View)findViewById(R.id.switch_video)).setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				//Utils.showToast(null, "Long click");
				finish();
				return true;
			}
		});
		if( MyDebug.LOG ) Log.d(TAG, "onCreate: time after setting gallery long click listener: " + (System.currentTimeMillis() - debug_time));

		// listen for gestures
		gestureDetector = new GestureDetector(this, new MyGestureDetector());
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after creating gesture detector: " + (System.currentTimeMillis() - debug_time));

		// set up listener to handle immersive mode options
		View decorView = getWindow().getDecorView();
		decorView.setOnSystemUiVisibilityChangeListener
				(new View.OnSystemUiVisibilityChangeListener() {
			@Override
			public void onSystemUiVisibilityChange(int visibility) {
				// Note that system bars will only be "visible" if none of the
				// LOW_PROFILE, HIDE_NAVIGATION, or FULLSCREEN flags are set.
				if( !usingKitKatImmersiveMode() ) {
					clearImmersiveTimer();
					
					if (fullscreen && (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
						immersive_timer_runnable = new Runnable() {
							@Override
							public void run() {
								if (!camera_in_background)
									setFullscreen();

								immersive_timer_runnable = null;
								immersive_timer_handler = null;
							}
						};
					} else if (!mainUI.popupIsOpen() && (visibility & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0 && Prefs.getString(Prefs.IMMERSIVE_MODE, "immersive_mode_off").equals("immersive_mode_low_profile")) {
						immersive_timer_runnable = new Runnable() {
							@Override
							public void run() {
								if (!camera_in_background)
									getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);

								immersive_timer_runnable = null;
								immersive_timer_handler = null;
							}
						};
					}
					
					if (immersive_timer_runnable != null) {
						immersive_timer_handler = new Handler();
						immersive_timer_handler.postDelayed(immersive_timer_runnable, 3000);
					}
				/*
					if (fullscreen && (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
						final Handler handler = new Handler();
						handler.postDelayed(new Runnable() {
							@Override
							public void run() {
								if (!camera_in_background)
									setFullscreen();
							}
						}, 3000);
					} else if (!mainUI.popupIsOpen() && (visibility & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0 && Prefs.getString(Prefs.IMMERSIVE_MODE, "immersive_mode_off").equals("immersive_mode_low_profile")) {
						final Handler handler = new Handler();
						handler.postDelayed(new Runnable() {
							@Override
							public void run() {
								if (!camera_in_background)
									getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
							}
						}, 3000);
					}*/
					return;
				}
				if( MyDebug.LOG )
					Log.d(TAG, "onSystemUiVisibilityChange: " + visibility);
				if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
					if( MyDebug.LOG )
						Log.d(TAG, "system bars now visible");
					// The system bars are visible. Make any desired
					// adjustments to your UI, such as showing the action bar or
					// other navigational controls.
					mainUI.setImmersiveMode(false);
					setImmersiveTimer();
				}
				else {
					if( MyDebug.LOG )
						Log.d(TAG, "system bars now NOT visible");
					// The system bars are NOT visible. Make any desired
					// adjustments to your UI, such as hiding the action bar or
					// other navigational controls.
					mainUI.setImmersiveMode(true);
				}
			}
		});
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after setting immersive mode listener: " + (System.currentTimeMillis() - debug_time));

		// show "about" dialog for first time use; also set some per-device defaults
		if( !has_done_first_time ) {
			if( !is_test ) {
				AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
				alertDialog.setTitle(R.string.app_name);
				alertDialog.setMessage(R.string.intro_text);
				alertDialog.setPositiveButton(android.R.string.ok, null);
				alertDialog.setNegativeButton(R.string.preference_online_help, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						if( MyDebug.LOG )
							Log.d(TAG, "online help");
						launchOnlineHelp();
					}
				});
				alertDialog.show();
			}

			setFirstTimeFlag();
		}

		setModeFromIntents(savedInstanceState);

		// load icons
		preloadIcons(R.array.flash_icons);
		preloadIcons(R.array.focus_mode_icons);
		preloadIcons(R.array.photo_mode_icons);
		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: time after preloading icons: " + (System.currentTimeMillis() - debug_time));


		selfie_mode = Prefs.getBoolean(Prefs.SELFIE_MODE, false);
		audio_control = Prefs.getBoolean(Prefs.AUDIO_CONTROL, false);
		mainUI.setSelfieMode(selfie_mode);
		
		mainUI.setFaceDetection(Prefs.getBoolean(Prefs.FACE_DETECTION, false));

		if( MyDebug.LOG )
			Log.d(TAG, "onCreate: total time for Activity startup: " + (System.currentTimeMillis() - debug_time));
	}

	/* This method sets the preference defaults which are set specific for a particular device.
	 * This method should be called when Open Camera is run for the very first time after installation,
	 * or when the user has requested to "Reset settings".
	 */
	void setDeviceDefaults() {
		if( MyDebug.LOG )
			Log.d(TAG, "setDeviceDefaults");
		boolean is_samsung = Build.MANUFACTURER.toLowerCase(Locale.US).contains("samsung");
		boolean is_oneplus = Build.MANUFACTURER.toLowerCase(Locale.US).contains("oneplus");
		//boolean is_nexus = Build.MODEL.toLowerCase(Locale.US).contains("nexus");
		//boolean is_nexus6 = Build.MODEL.toLowerCase(Locale.US).contains("nexus 6");
		//boolean is_pixel_phone = Build.DEVICE != null && Build.DEVICE.equals("sailfish");
		//boolean is_pixel_xl_phone = Build.DEVICE != null && Build.DEVICE.equals("marlin");
		if( MyDebug.LOG ) {
			Log.d(TAG, "is_samsung? " + is_samsung);
			Log.d(TAG, "is_oneplus? " + is_oneplus);
			//Log.d(TAG, "is_nexus? " + is_nexus);
			//Log.d(TAG, "is_nexus6? " + is_nexus6);
			//Log.d(TAG, "is_pixel_phone? " + is_pixel_phone);
			//Log.d(TAG, "is_pixel_xl_phone? " + is_pixel_xl_phone);
		}
		if( is_samsung || is_oneplus ) {
			// workaround needed for Samsung S7 at least (tested on Samsung RTL)
			// workaround needed for OnePlus 3 at least (see http://forum.xda-developers.com/oneplus-3/help/camera2-support-t3453103 )
			// update for v1.37: significant improvements have been made for standard flash and Camera2 API. But OnePlus 3T still has problem
			// that photos come out with a blue tinge if flash is on, and the scene is bright enough not to need it; Samsung devices also seem
			// to work okay, testing on S7 on RTL, but still keeping the fake flash mode in place for these devices, until we're sure of good
			// behaviour
			if( MyDebug.LOG )
				Log.d(TAG, "set fake flash for camera2");
			Prefs.setBoolean(Prefs.CAMERA2_FAKE_FLASH, true);
		}
		/*if( is_nexus6 ) {
			// Nexus 6 captureBurst() started having problems with Android 7 upgrade - images appeared in wrong order (and with wrong order of shutter speeds in exif info), as well as problems with the camera failing with serious errors
			// we set this even for Nexus 6 devices not on Android 7, as at some point they'll likely be upgraded to Android 7
			// Update: now fixed in v1.37, this was due to bug where we set RequestTag.CAPTURE for all captures in takePictureBurstExpoBracketing(), rather than just the last!
			if( MyDebug.LOG )
				Log.d(TAG, "disable fast burst for camera2");
			Prefs.setBoolean(Prefs.CAMERA2_FAST_BURST, false);
		}*/
		
		if( !is_samsung && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
			boolean camera2 = true;
			CameraControllerManager2 manager2 = new CameraControllerManager2(this);
			if( manager2.getNumberOfCameras() == 0 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "Camera2 reports 0 cameras");
				camera2 = false;
			}
			for(int i=0;i<manager2.getNumberOfCameras() && camera2;i++) {
				if( !manager2.hasLevel(i, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) ) {
					if( MyDebug.LOG )
						Log.d(TAG, "camera " + i + " doesn't have limited or full support for Camera2 API");
					camera2 = false;
				}
				if( !manager2.hasLevel(i, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) ) {
					Prefs.setBoolean(Prefs.EXPO_BRACKETING_USE_ISO + "_" + i, false);
				}
			}
			if (camera2) {
				Prefs.setBoolean(Prefs.USE_CAMERA2, true);
			}
		}

		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ) {
			final boolean has_navigation_bar;
			int id = resources.getIdentifier("config_showNavigationBar", "bool", "android");
			if (id > 0)
				has_navigation_bar = resources.getBoolean(id);
			else
				has_navigation_bar = false;

			if (has_navigation_bar) {
				Prefs.setString(Prefs.IMMERSIVE_MODE, "immersive_mode_overlay");
			}
		}
		
		if (resources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
			Prefs.setString(Prefs.SYSTEM_UI_ORIENTATION, "portrait");
		}
	}

	/** Switches modes if required, if called from a relevant intent/tile.
	 */
	private void setModeFromIntents(Bundle savedInstanceState) {
		if( MyDebug.LOG )
			Log.d(TAG, "setModeFromIntents");
		if( savedInstanceState != null ) {
			// If we're restoring from a saved state, we shouldn't be resetting any modes
			if( MyDebug.LOG )
				Log.d(TAG, "restoring from saved state");
			return;
		}
		String action = this.getIntent().getAction();
		if( MediaStore.INTENT_ACTION_VIDEO_CAMERA.equals(action) || MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
			if( MyDebug.LOG )
				Log.d(TAG, "launching from video intent");
			Prefs.setVideoPref(true);
		}
		else if( MediaStore.ACTION_IMAGE_CAPTURE.equals(action) || MediaStore.ACTION_IMAGE_CAPTURE_SECURE.equals(action) || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA.equals(action) || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(action) ) {
			if( MyDebug.LOG )
				Log.d(TAG, "launching from photo intent");
			Prefs.setVideoPref(false);
		}
		else if( MyTileService.TILE_ID.equals(action) ) {
			if( MyDebug.LOG )
				Log.d(TAG, "launching from quick settings tile for Open Camera: photo mode");
			Prefs.setVideoPref(false);
		}
		else if( MyTileServiceVideo.TILE_ID.equals(action) ) {
			if( MyDebug.LOG )
				Log.d(TAG, "launching from quick settings tile for Open Camera: video mode");
			Prefs.setVideoPref(true);
		}
/*		else if( MyTileServiceFrontCamera.TILE_ID.equals(action) ) {
			if( MyDebug.LOG )
				Log.d(TAG, "launching from quick settings tile for Open Camera: selfie mode");
			for(int i=0;i<preview.getCameraControllerManager().getNumberOfCameras();i++) {
				if( preview.getCameraControllerManager().isFrontFacing(i) ) {
					if (MyDebug.LOG)
						Log.d(TAG, "found front camera: " + i);
					Prefs.setCameraIdPref(i);
					break;
				}
			}
		}*/
	}

	/** Determine whether we support Camera2 API.
	 */
	private void initCamera2Support() {
		if( MyDebug.LOG )
			Log.d(TAG, "initCamera2Support");
		supports_camera2 = false;
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
			CameraControllerManager2 manager2 = new CameraControllerManager2(this);
			supports_camera2 = true;
			if( manager2.getNumberOfCameras() == 0 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "Camera2 reports 0 cameras");
				supports_camera2 = false;
			}
		}
		if( MyDebug.LOG )
			Log.d(TAG, "supports_camera2? " + supports_camera2);
	}
	
	private void preloadIcons(int icons_id) {
		long debug_time = 0;
		if( MyDebug.LOG ) {
			Log.d(TAG, "preloadIcons: " + icons_id);
			debug_time = System.currentTimeMillis();
		}
		String [] icons = resources.getStringArray(icons_id);
		for(String icon : icons) {
			int resource = resources.getIdentifier(icon, null, this.getApplicationContext().getPackageName());
			if( MyDebug.LOG )
				Log.d(TAG, "load resource: " + resource);
			Bitmap bm = BitmapFactory.decodeResource(resources, resource);
			this.preloaded_bitmap_resources.put(resource, bm);
		}
		if( MyDebug.LOG ) {
			Log.d(TAG, "preloadIcons: total time for preloadIcons: " + (System.currentTimeMillis() - debug_time));
			Log.d(TAG, "size of preloaded_bitmap_resources: " + preloaded_bitmap_resources.size());
		}
	}
	
	@Override
	protected void onDestroy() {
		if( MyDebug.LOG ) {
			Log.d(TAG, "onDestroy");
			Log.d(TAG, "size of preloaded_bitmap_resources: " + preloaded_bitmap_resources.size());
		}
		if( preview != null ) {
			preview.onDestroy();
		}
		if( applicationInterface != null ) {
			applicationInterface.onDestroy();
		}
		if( rs != null ) {
			// need to destroy context, otherwise this isn't necessarily garbage collected - we had tests failing with out of memory
			// problems e.g. when running MainTests as a full set with Camera2 API. Although we now reduce the problem by creating
			// the rs lazily, it's still good to explicitly clear.
			try {
				rs.destroy(); // on Android M onwards this is a NOP - instead we call RenderScript.releaseAllContexts(); in MainActivity.onDestroy()
			}
			catch(RSInvalidStateException e) {
				e.printStackTrace();
			}
			rs = null;
		}
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
			// see note in HDRProcessor.onDestroy() - but from Android M, renderscript contexts are released with releaseAllContexts()
			// doc for releaseAllContexts() says "If no contexts have been created this function does nothing"
			RenderScript.releaseAllContexts();
		}
		// Need to recycle to avoid out of memory when running tests - probably good practice to do anyway
		for(Map.Entry<Integer, Bitmap> entry : preloaded_bitmap_resources.entrySet()) {
			if( MyDebug.LOG )
				Log.d(TAG, "recycle: " + entry.getKey());
			entry.getValue().recycle();
		}
		preloaded_bitmap_resources.clear();
		super.onDestroy();
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	
	private void setFirstTimeFlag() {
		if( MyDebug.LOG )
			Log.d(TAG, "setFirstTimeFlag");
		Prefs.setBoolean(Prefs.DONE_FIRST_TIME, true);
	}

	void launchOnlineHelp() {
		if( MyDebug.LOG )
			Log.d(TAG, "launchOnlineHelp");
		Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://opencamera.sourceforge.net/"));
		startActivity(browserIntent);
	}

	// for audio "noise" trigger option
	private int last_level = -1;
	private long time_quiet_loud = -1;
	private long time_last_audio_trigger_photo = -1;

	/** Listens to audio noise and decides when there's been a "loud" noise to trigger taking a photo.
	 */
	public void onAudio(int level) {
		boolean audio_trigger = false;
		/*if( level > 150 ) {
			if( MyDebug.LOG )
				Log.d(TAG, "loud noise!: " + level);
			audio_trigger = true;
		}*/

		if( last_level == -1 ) {
			last_level = level;
			return;
		}
		int diff = level - last_level;
		
		if( MyDebug.LOG )
			Log.d(TAG, "noise_sensitivity: " + audio_noise_sensitivity);

		if( diff > audio_noise_sensitivity ) {
			if( MyDebug.LOG )
				Log.d(TAG, "got louder!: " + last_level + " to " + level + " , diff: " + diff);
			time_quiet_loud = System.currentTimeMillis();
			if( MyDebug.LOG )
				Log.d(TAG, "	time: " + time_quiet_loud);
		}
		else if( diff < -audio_noise_sensitivity && time_quiet_loud != -1 ) {
			if( MyDebug.LOG )
				Log.d(TAG, "got quieter!: " + last_level + " to " + level + " , diff: " + diff);
			long time_now = System.currentTimeMillis();
			long duration = time_now - time_quiet_loud;
			if( MyDebug.LOG ) {
				Log.d(TAG, "stopped being loud - was loud since :" + time_quiet_loud);
				Log.d(TAG, "	time_now: " + time_now);
				Log.d(TAG, "	duration: " + duration);
			}
			if( duration < 1500 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "audio_trigger set");
				audio_trigger = true;
			}
			time_quiet_loud = -1;
		}
		else {
			if( MyDebug.LOG )
				Log.d(TAG, "audio level: " + last_level + " to " + level + " , diff: " + diff);
		}

		last_level = level;

		if( audio_trigger ) {
			if( MyDebug.LOG )
				Log.d(TAG, "audio trigger");
			// need to run on UI thread so that this function returns quickly (otherwise we'll have lag in processing the audio)
			// but also need to check we're not currently taking a photo or on timer, so we don't repeatedly queue up takePicture() calls, or cancel a timer
			long time_now = System.currentTimeMillis();
			boolean want_audio_listener = Prefs.getString(Prefs.AUDIO_CONTROL_TYPE, "noise").equals("noise");
			if( time_last_audio_trigger_photo != -1 && time_now - time_last_audio_trigger_photo < 5000 ) {
				// avoid risk of repeatedly being triggered - as well as problem of being triggered again by the camera's own "beep"!
				if( MyDebug.LOG )
					Log.d(TAG, "ignore loud noise due to too soon since last audio triggerred photo:" + (time_now - time_last_audio_trigger_photo));
			}
			else if( !want_audio_listener ) {
				// just in case this is a callback from an AudioListener before it's been freed (e.g., if there's a loud noise when exiting settings after turning the option off
				if( MyDebug.LOG )
					Log.d(TAG, "ignore loud noise due to audio listener option turned off");
			}
			else {
				if( MyDebug.LOG )
					Log.d(TAG, "audio trigger from loud noise");
				time_last_audio_trigger_photo = time_now;
				audioTrigger();
			}
		}
	}
	
	/* Audio trigger - either loud sound, or speech recognition.
	 * This performs some additional checks before taking a photo.
	 */
	private void audioTrigger() {
		if( MyDebug.LOG )
			Log.d(TAG, "ignore audio trigger due to popup open");
		if( popupIsOpen() ) {
			if( MyDebug.LOG )
				Log.d(TAG, "ignore audio trigger due to popup open");
		}
		else if( camera_in_background ) {
			if( MyDebug.LOG )
				Log.d(TAG, "ignore audio trigger due to camera in background");
		}
		else if( preview.isTakingPhotoOrOnTimer() ) {
			if( MyDebug.LOG )
				Log.d(TAG, "ignore audio trigger due to already taking photo or on timer");
		}
		else if( preview.isVideoRecording() ) {
			if( MyDebug.LOG )
				Log.d(TAG, "ignore audio trigger due to already recording video");
		}
		else {
			if( MyDebug.LOG )
				Log.d(TAG, "schedule take picture due to loud noise");
			//takePicture();
			this.runOnUiThread(new Runnable() {
				public void run() {
					if( MyDebug.LOG )
						Log.d(TAG, "taking picture due to audio trigger");
					takePicture();
				}
			});
		}
	}
	
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if( MyDebug.LOG )
			Log.d(TAG, "onKeyDown: " + keyCode);
		for(int i=0;i<buttons_events.length;i++) {
			if (keyCode == buttons_events[i]) {
				String key_action = Prefs.getString(buttons_preferences[i], "nothing");

				if (key_action.equals("shutter_button")) {
					if (event.getDownTime() == event.getEventTime())
						takePicture();
					return true;
				} else if (key_action.equals("pause_video")) {
					if (event.getDownTime() == event.getEventTime())
						pauseVideo();
					return true;
				} else if (key_action.equals("selfie_mode")) {
					return true;
				} else if (key_action.equals("zoom_in")) {
					mainUI.zoomIn();
					return true;
				} else if (key_action.equals("zoom_out")) {
					mainUI.zoomOut();
					return true;
				} else if (key_action.equals("autofocus")) {
					// important not to repeatedly request focus, even though main_activity.getPreview().requestAutoFocus() will cancel - causes problem with hardware camera key where a half-press means to focus
					// also check DownTime vs EventTime to prevent repeated focusing whilst the key is held down - see https://sourceforge.net/p/opencamera/tickets/174/ ,
					// or same issue above for volume key focus
					if( event.getDownTime() == event.getEventTime() && !preview.isFocusWaiting() ) {
						if( MyDebug.LOG )
							Log.d(TAG, "request focus due to focus key");
						preview.requestAutoFocus();
					}
					return true;
				} else if (key_action.equals("focus_plus")) {
					if (Prefs.getBoolean(Prefs.ZOOM_WHEN_FOCUSING, false))
						this.zoomWhenFocusing();

					mainUI.changeFocusDistance(1);
					return true;
				} else if (key_action.equals("focus_minus")) {
					if (Prefs.getBoolean(Prefs.ZOOM_WHEN_FOCUSING, false))
						this.zoomWhenFocusing();

					mainUI.changeFocusDistance(-1);
					return true;
				} else if (key_action.equals("iso_plus")) {
					mainUI.changeISO(1);
					return true;
				} else if (key_action.equals("iso_minus")) {
					mainUI.changeISO(-1);
					return true;
				} else if (key_action.equals("exposure_plus")) {
					mainUI.changeExposure(1);
					return true;
				} else if (key_action.equals("exposure_minus")) {
					mainUI.changeExposure(-1);
					return true;
				} else if (key_action.equals("wb_plus")) {
					mainUI.changeWhiteBalance(1);
					return true;
				} else if (key_action.equals("wb_minus")) {
					mainUI.changeWhiteBalance(-1);
					return true;
				} else if (key_action.equals("really_nothing")) {
					return true;
				}

				break;
			}
		}

		if (keyCode == KeyEvent.KEYCODE_MENU) {
			final MyPreferenceFragment fragment = getPreferenceFragment();
			if (fragment == null)
				openSettings();
/*			else {
				forceCloseSettings();
				closeSettings();
			}*/
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}
	
	// For some stupid devices like LG G4
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if( MyDebug.LOG )
			Log.d(TAG, "onKeyUp: " + keyCode);
		for(int i=0;i<buttons_events.length;i++) {
			if (keyCode == buttons_events[i]) {
				if (!Prefs.getString(buttons_preferences[i], "nothing").equals("nothing"))
					return true;
			}
		}
		return super.onKeyUp(keyCode, event);
	}
	
	private void zoomWhenFocusing() {
		if (preview.supportsZoom()) {
			preview.focusZoom();
			
			if( reset_zoom_handler != null && reset_zoom_runnable != null ) {
				reset_zoom_handler.removeCallbacks(reset_zoom_runnable);
			}

			if (reset_zoom_handler == null) {
				reset_zoom_handler = new Handler();
			}
			
			if (reset_zoom_runnable == null) {
				reset_zoom_runnable = new Runnable(){
					@Override
					public void run(){
						preview.resetZoom();
				   }
				};
			}

			reset_zoom_handler.postDelayed(reset_zoom_runnable, 1500);
		}
	}

	
	private final SensorEventListener accelerometerListener = new SensorEventListener() {
		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
		}

		@Override
		public void onSensorChanged(SensorEvent event) {
			preview.onAccelerometerSensorChanged(event);
		}
	};
	
	private final SensorEventListener magneticListener = new SensorEventListener() {
		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
		}

		@Override
		public void onSensorChanged(SensorEvent event) {
			preview.onMagneticSensorChanged(event);
		}
	};

	@Override
	protected void onResume() {
		long debug_time = 0;
		if( MyDebug.LOG ) {
			Log.d(TAG, "onResume");
			debug_time = System.currentTimeMillis();
		}
		if (fullscreen) {
			setFullscreen();
			// Now i know how looks hell for me: writing apps for Android 24/7. I hate this fuckin shit.
			final Handler handler = new Handler();
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					setFullscreen();
				}
			}, 100);
		}
		super.onResume();

		// Set black window background; also needed if we hide the virtual buttons in immersive mode
		// Note that we do it here rather than customising the theme's android:windowBackground, so this doesn't affect other views - in particular, the MyPreferenceFragment settings
		getWindow().getDecorView().getRootView().setBackgroundColor(Color.BLACK);

		int delay = Prefs.getBoolean(Prefs.SPEED_UP_SENSORS, false) ? SensorManager.SENSOR_DELAY_UI : SensorManager.SENSOR_DELAY_NORMAL;
		mSensorManager.registerListener(accelerometerListener, mSensorAccelerometer, delay);
		mSensorManager.registerListener(magneticListener, mSensorMagnetic, delay);
		orientationEventListener.enable();

		initLocation();

		updateGalleryIcon(); // update in case images deleted whilst idle

		preview.onResume();

		if (getPreferenceFragment() == null) {
			if (audio_control)
				startAudioListeners();
		
			mainUI.showSeekbars();
			mainUI.layoutUI(true);
		}

		if( MyDebug.LOG ) {
			Log.d(TAG, "onResume: total time to resume: " + (System.currentTimeMillis() - debug_time));
		}
	}
	
	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		if( MyDebug.LOG )
			Log.d(TAG, "onWindowFocusChanged: " + hasFocus);
		super.onWindowFocusChanged(hasFocus);
		if( !this.camera_in_background && hasFocus ) {
			// low profile mode is cleared when app goes into background
			// and for Kit Kat immersive mode, we want to set up the timer
			// we do in onWindowFocusChanged rather than onResume(), to also catch when window lost focus due to notification bar being dragged down (which prevents resetting of immersive mode)
			initImmersiveMode();
		}
	}

	@Override
	protected void onPause() {
		long debug_time = 0;
		if( MyDebug.LOG ) {
			Log.d(TAG, "onPause");
			debug_time = System.currentTimeMillis();
		}
		super.onPause(); // docs say to call this before freeing other things
		mainUI.destroyPopup(); // important as user could change/reset settings from Android settings when pausing
		preview.cancelTimer();
		mSensorManager.unregisterListener(accelerometerListener);
		mSensorManager.unregisterListener(magneticListener);
		orientationEventListener.disable();
		freeAudioListener(false);
		freeSpeechRecognizer();
		applicationInterface.getLocationSupplier().freeLocationListeners();
		applicationInterface.getGyroSensor().stopRecording();
		applicationInterface.clearLastImages(); // this should happen when pausing the preview, but call explicitly just to be safe
		preview.onPause();
		if (fullscreen) setFullscreen();
		if( MyDebug.LOG ) {
			Log.d(TAG, "onPause: total time to pause: " + (System.currentTimeMillis() - debug_time));
		}
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		if( MyDebug.LOG )
			Log.d(TAG, "onConfigurationChanged()");
		// configuration change can include screen orientation (landscape/portrait) when not locked (when settings is open)
		// needed if app is paused/resumed when settings is open and device is in portrait mode
		if (getPreferenceFragment() == null){
			preview.setCameraDisplayOrientation();
			if (!mainUI.inImmersiveMode()) {
				mainUI.showGUI(true);
			}
		}
		super.onConfigurationChanged(newConfig);
	}
	
	public void clickedTakePhoto(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedTakePhoto");
		this.takePicture();
	}

	public void clickedPauseVideo(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedPauseVideo");
		this.pauseVideo();
	}

	public void clickedSelfieMode(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedSelfieMode");
		mainUI.destroyPopup();
		selfie_mode = !selfie_mode;
		
		Prefs.setBoolean(Prefs.SELFIE_MODE, selfie_mode);

		mainUI.setSelfieMode(selfie_mode);

		mainUI.setTakePhotoIcon();
		this.showPhotoVideoToast(true);
	}

	public void clickedAudioControl(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedAudioControl");
		audio_control = !audio_control;

		Prefs.setBoolean(Prefs.AUDIO_CONTROL, audio_control);

		if (audio_control) {
			startAudioListeners();
		} else {
			stopAudioListeners(false);
		}
		mainUI.setAudioControl(audio_control);
	}

	public void clickedFaceDetection(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedFaceDetection");
		mainUI.closePopup();
		boolean state = Prefs.getBoolean(Prefs.FACE_DETECTION, false);
		
		state = !state;

		Prefs.setBoolean(Prefs.FACE_DETECTION, state);
		
		mainUI.setFaceDetection(state);

		preview.onPause();
		preview.onResume();
		
		this.showPhotoVideoToast(true);
	}
	
	/* Returns the cameraId that the "Switch camera" button will switch to.
	 */
	public int getNextCameraId() {
		if( MyDebug.LOG )
			Log.d(TAG, "getNextCameraId");
		int cameraId = Prefs.getCameraIdPref();
		if( MyDebug.LOG )
			Log.d(TAG, "current cameraId: " + cameraId);
		if( this.preview.canSwitchCamera() ) {
			int n_cameras = preview.getCameraControllerManager().getNumberOfCameras();
			cameraId = (cameraId+1) % n_cameras;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "next cameraId: " + cameraId);
		return cameraId;
	}

	public void clickedSwitchCamera(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedSwitchCamera");
		if( preview.isOpeningCamera() ) {
			if( MyDebug.LOG )
				Log.d(TAG, "already opening camera in background thread");
			return;
		}
		mainUI.closePopup();
		if( this.preview.canSwitchCamera() ) {
			int cameraId = getNextCameraId();
			View switchCameraButton = findViewById(R.id.switch_camera);
			switchCameraButton.setEnabled(false); // prevent slowdown if user repeatedly clicks
			Prefs.setCameraIdPref(cameraId);
			this.preview.setCamera(cameraId);
			mainUI.layoutUI();
			switchCameraButton.setEnabled(true);
			// no need to call mainUI.setSwitchCameraContentDescription - this will be called from PreviewcameraSetup when the
			// new camera is opened
		}
	}

	public void clickedSwitchVideo(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedSwitchVideo");

		if (this.preview.isVideoRecording()) {
			this.preview.takeVideoSnapshot();
		} else {
			mainUI.destroyPopup(); // important as we don't want to use a cached popup, as we can show different options depending on whether we're in photo or video mode
			View switchVideoButton = findViewById(R.id.switch_video);
			switchVideoButton.setEnabled(false); // prevent slowdown if user repeatedly clicks
			this.preview.switchVideo(false);
			switchVideoButton.setEnabled(true);

			mainUI.showGUI(true, false);
			mainUI.setPopupIcons();
			mainUI.setTakePhotoIcon();

			mainUI.setManualFocusSeekbars();
			mainUI.layoutSeekbars();
			if( !block_startup_toast ) {
				this.showPhotoVideoToast(true);
			}
		}
	}

	public void clickedExposure(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedExposure");
		mainUI.toggleSeekbars();
	}
	
	public static double seekbarScaling(double frac) {
		// For various seekbars, we want to use a non-linear scaling, so user has more control over smaller values
		return (Math.pow(100.0, frac) - 1.0) / 99.0;
	}

	public static double seekbarScalingInverse(double scaling) {
		return Math.log(99.0*scaling + 1.0) / Math.log(100.0);
	}

	public static double exponentialScaling(double frac, double min, double max) {
		/* We use S(frac) = A * e^(s * frac)
		 * We want S(0) = min, S(1) = max
		 * So A = min
		 * and Ae^s = max
		 * => s = ln(max/min)
		 */
		if (min == 0.0) {
			return max * Math.log(1 + frac * (Math.E - 1));
		} else {
			double s = Math.log(max / min);
			return min * Math.exp(s * frac);
		}
	}

	public static double exponentialScalingInverse(double value, double min, double max) {
		if (min == 0.0) {
			//fixme
			return 1 - Math.log(1 + (1 - value / max) * (Math.E - 1));
		} else {
			double s = Math.log(max / min);
			return Math.log(value / min) / s;
		}
	}

	public void clickedAutoAdjustmentLock(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedAutoAdjustmentLock");
		this.preview.toggleAutoAdjustmentLock();
		ImageButton button = (ImageButton) findViewById(R.id.auto_adjustment_lock);
		button.setImageResource(preview.isAutoAdjustmentLocked() ? R.drawable.ctrl_lock_red : R.drawable.ctrl_lock);
		Utils.showToast(exposure_lock_toast, preview.isAutoAdjustmentLocked() ? R.string.auto_adjustment_locked : R.string.auto_adjustment_unlocked);
	}

	public void clickedExpoMetering(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedExpoMetering");
		set_expo_metering_area = !set_expo_metering_area;
		ImageButton button = (ImageButton) findViewById(R.id.expo_metering_area);
		button.setImageResource(set_expo_metering_area ? R.drawable.ctrl_expo_metering_red : R.drawable.ctrl_expo_metering);
	}
	
	public void clickedSettings(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedSettings");
		openSettings();
	}

	public boolean popupIsOpen() {
		return mainUI.popupIsOpen();
	}
	
	public Bitmap getPreloadedBitmap(int resource) {
		return this.preloaded_bitmap_resources.get(resource);
	}

	public void clickedPopup(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedPopup");
		mainUI.togglePopup(view);
	}

	private final PreferencesListener preferencesListener = new PreferencesListener();

	/** Keeps track of changes to SharedPreferences.
	 */
	class PreferencesListener implements SharedPreferences.OnSharedPreferenceChangeListener {
		private static final String TAG = "HedgeCam/PreferencesListener";

		private boolean need_reconnect;
		private boolean need_restart;
		private boolean need_update_overlay;
		private boolean need_reload_sound;

		void startListening() {
			if( MyDebug.LOG )
				Log.d(TAG, "startListening");
			need_reconnect = false;
			need_restart = false;
			need_update_overlay = false;
			need_reload_sound = false;

			// n.b., registerOnSharedPreferenceChangeListener warns that we must keep a reference to the listener (which
			// is this class) as long as we want to listen for changes, otherwise the listener may be garbage collected!
			sharedPreferences.registerOnSharedPreferenceChangeListener(this);
		}

		void stopListening() {
			if( MyDebug.LOG )
				Log.d(TAG, "stopListening");
			sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
		}

		@Override
		public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
			if( MyDebug.LOG )
				Log.d(TAG, "onSharedPreferenceChanged: " + key);
			//fixme
			switch( key ) {
				case Prefs.IND_FREQ:
				case Prefs.IND_SLOW_IF_BUSY:
					preview.updateTickInterval();
					break;
				case Prefs.POPUP_FONT_SIZE:
					Utils.updateToastConfig();
					break;
				case Prefs.SELFIE_MODE:
					selfie_mode = Prefs.getBoolean(Prefs.SELFIE_MODE, false);
					mainUI.setSelfieMode(selfie_mode);
					break;
				case Prefs.AUDIO_CONTROL:
					audio_control = Prefs.getBoolean(Prefs.AUDIO_CONTROL, false);
					mainUI.setAudioControl(audio_control);
					break;
				case Prefs.GUI_ORIENTATION:
					mainUI.updateOrientationPrefs();
					break;
				case Prefs.GHOST_IMAGE:
				case Prefs.GHOST_IMAGE_SOURCE:
				case Prefs.GHOST_IMAGE_FILE:
				case Prefs.GHOST_IMAGE_FILE_SAF:
				case Prefs.GHOST_IMAGE_ALPHA:
					need_update_overlay = true;
					break;
				case Prefs.SHUTTER_SOUND:
				case Prefs.VIDEO_SOUND:
				case Prefs.TIMER_BEEP:
				case Prefs.TIMER_SPEAK:
				case Prefs.TIMER_START_SOUND:
				case Prefs.FACE_DETECTION_SOUND:
				case Prefs.SOUND_VOLUME:
				case Prefs.AUDIO_STREAM:
					need_reload_sound = true;
					break;
				case "camera_resolution_0":
				case "camera_resolution_1":
				case "camera_resolution_2":
				case Prefs.QUALITY:
				case Prefs.IMAGE_FORMAT:
				case Prefs.IMMERSIVE_MODE:
				case Prefs.FACE_DETECTION:
				case Prefs.ROTATE_PREVIEW:
				case Prefs.PREVIEW_MAX_SIZE:
				case Prefs.STARTUP_FOCUS:
				case Prefs.FORCE_FACE_FOCUS:
				case Prefs.CENTER_FOCUS:
				case Prefs.CAMERA2_FAKE_FLASH:
				case Prefs.UPDATE_FOCUS_FOR_VIDEO:
				case Prefs.USE_1920X1088:
				case "preference_reconnect_camera":
				case "preference_resolution":
				case Prefs.CAMERA2_FAST_BURST:
				case "preference_video_quality":
				case Prefs.FORCE_VIDEO_4K:
				case Prefs.VIDEO_FLASH:
				case Prefs.ANTIBANDING:
				case Prefs.NOISE_REDUCTION+"_2_0":
				case Prefs.NOISE_REDUCTION+"_2_1":
				case Prefs.NOISE_REDUCTION+"_2_2":
				case Prefs.EDGE+"_2_0":
				case Prefs.EDGE+"_2_1":
				case Prefs.EDGE+"_2_2":
				case Prefs.SMART_FILTER+"_0":
				case Prefs.SMART_FILTER+"_1":
				case Prefs.SMART_FILTER+"_2":
				case Prefs.OPTICAL_STABILIZATION+"_0":
				case Prefs.OPTICAL_STABILIZATION+"_1":
				case Prefs.OPTICAL_STABILIZATION+"_2":
				case Prefs.HOT_PIXEL_CORRECTION+"_2_0":
				case Prefs.HOT_PIXEL_CORRECTION+"_2_1":
				case Prefs.HOT_PIXEL_CORRECTION+"_2_2":
				case Prefs.MIN_FOCUS_DISTANCE+"_0":
				case Prefs.MIN_FOCUS_DISTANCE+"_1":
				case Prefs.MIN_FOCUS_DISTANCE+"_2":
				case Prefs.EXPO_BRACKETING_STOPS_UP:
				case Prefs.EXPO_BRACKETING_STOPS_DOWN:
				case Prefs.HDR_STOPS_UP:
				case Prefs.HDR_STOPS_DOWN:
				case Prefs.EXPO_BRACKETING_USE_ISO+"_0":
				case Prefs.EXPO_BRACKETING_USE_ISO+"_1":
				case Prefs.EXPO_BRACKETING_USE_ISO+"_2":
				case Prefs.EXPO_BRACKETING_DELAY:
				case Prefs.FOCUS_RANGE:
				case Prefs.PREVIEW_MAX_EXPO:
				case Prefs.HDR_IGNORE_SMART_FILTER:
				case Prefs.FAST_BURST_DISABLE_FILTERS:
				case Prefs.NR_DISABLE_FILTERS:
				case Prefs.ISO_STEPS:
				case Prefs.EXPOSURE_STEPS:
				case Prefs.WHITE_BALANCE_STEPS:
				case Prefs.UNCOMPRESSED_PHOTO:
				case Prefs.FULL_SIZE_COPY:
				case Prefs.FOCUS_DISTANCE_CALIBRATION+"_0":
				case Prefs.FOCUS_DISTANCE_CALIBRATION+"_1":
				case Prefs.FOCUS_DISTANCE_CALIBRATION+"_2":
				case Prefs.VIDEO_FPS:
				case Prefs.VIDEO_LOG_PROFILE:
				case Prefs.LOCK_PREVIEW_FPS_TO_VIDEO_FPS:
				case "preview_resolution_0":
				case "preview_resolution_1":
				case "preview_resolution_2":
				case "preview_resolution_0_video":
				case "preview_resolution_1_video":
				case "preview_resolution_2_video":
				case Prefs.DEFAULT_COLOR_CORRECTION:
				case Prefs.WHITE_BALANCE_CALIBRATION+"_0":
				case Prefs.WHITE_BALANCE_CALIBRATION+"_1":
				case Prefs.WHITE_BALANCE_CALIBRATION+"_2":
					need_reconnect = true;
					preview.closeCamera();
					break;
				case Prefs.SHUTTER_BUTTON_STYLE:
				case Prefs.SHOW_WHEN_LOCKED:
				case Prefs.MULTITOUCH_ZOOM:
				case Prefs.PREVIEW_SURFACE:
				case Prefs.SPEED_UP_SENSORS:
				case Prefs.SYSTEM_UI_ORIENTATION:
					need_restart = true;
				default:
					break;
			}
		}

		boolean needReconnect() {
			return need_reconnect;
		}
		boolean needRestart() {
			return need_restart;
		}
		boolean needUpdateOverlay() {
			return need_update_overlay;
		}
		boolean needReloadSound() {
			return need_reload_sound;
		}
	}
	
	public void openSettings() {
		if( MyDebug.LOG )
			Log.d(TAG, "openSettings");
		mainUI.destroyPopup();
		preview.cancelTimer(); // best to cancel any timer, in case we take a photo while settings window is open, or when changing settings
		preview.stopVideo(false); // important to stop video, as we'll be changing camera parameters when the settings window closes
		stopAudioListeners(true);
		
		Bundle bundle = new Bundle();
		bundle.putInt("cameraId", this.preview.getCameraId());
		bundle.putInt("nCameras", preview.getCameraControllerManager().getNumberOfCameras());
		bundle.putString("hardware_level", this.preview.getHardwareLevel());
		bundle.putBoolean("using_camera_2", this.preview.usingCamera2API());
		bundle.putBoolean("supports_auto_stabilise", this.supports_auto_stabilise);
		bundle.putBoolean("supports_force_video_4k", this.supports_force_video_4k);
		bundle.putBoolean("supports_camera2", this.supports_camera2);
		bundle.putBoolean("supports_face_detection", this.preview.supportsFaceDetection());
		bundle.putBoolean("supports_raw", this.preview.supportsRaw());
		bundle.putBoolean("supports_renderscript", this.supportsRenderScript());
		bundle.putBoolean("supports_dro", this.supportsDRO());
		bundle.putBoolean("supports_hdr", this.supportsHDR());
		bundle.putBoolean("supports_nr", this.supportsNoiseReduction());
		bundle.putBoolean("supports_expo_bracketing", this.supportsExpoBracketing());
		bundle.putBoolean("supports_focus_bracketing", this.supportsFocusBracketing());
		bundle.putBoolean("supports_fast_burst", this.supportsFastBurst());
		bundle.putInt("max_expo_bracketing_n_images", this.maxExpoBracketingNImages());
		bundle.putBoolean("supports_exposure_compensation", this.preview.supportsExposures());
		bundle.putInt("exposure_compensation_min", this.preview.getMinimumExposure());
		bundle.putInt("exposure_compensation_max", this.preview.getMaximumExposure());
		bundle.putBoolean("supports_iso_range", this.preview.supportsISORange());
		bundle.putInt("iso_range_min", this.preview.getMinimumISO());
		bundle.putInt("iso_range_max", this.preview.getMaximumISO());
		bundle.putBoolean("supports_exposure_time", this.preview.supportsExposureTime());
		bundle.putLong("exposure_time_min", this.preview.getMinimumExposureTime());
		bundle.putLong("exposure_time_max", this.preview.getMaximumExposureTime());
		bundle.putBoolean("supports_white_balance_temperature", this.preview.supportsWhiteBalanceTemperature());
		bundle.putInt("white_balance_temperature_min", this.preview.getMinimumWhiteBalanceTemperature());
		bundle.putInt("white_balance_temperature_max", this.preview.getMaximumWhiteBalanceTemperature());
		bundle.putBoolean("supports_video_stabilization", this.preview.supportsVideoStabilization());
		bundle.putBoolean("can_disable_shutter_sound", this.preview.canDisableShutterSound());
		bundle.putBoolean("supports_lock", this.preview.supportsAutoAdjustmentLock());
		bundle.putBoolean("supports_switch_camera", this.preview.getCameraControllerManager().getNumberOfCameras() > 1);
		bundle.putBoolean("supports_exposure_button", supportsExposureButton());
		bundle.putBoolean("supports_flash", this.preview.supportsFlash());
		bundle.putBoolean("supports_focus", this.preview.supportsFocus());
		bundle.putBoolean("supports_metering_area", this.preview.getMaxNumMeteringAreas() > 0);

		putBundleExtra(bundle, "color_effects", this.preview.getSupportedColorEffects());
		putBundleExtra(bundle, "scene_modes", this.preview.getSupportedSceneModes());
		putBundleExtra(bundle, "white_balances", this.preview.getSupportedWhiteBalances());
		putBundleExtra(bundle, "isos", this.preview.getSupportedISOs());
		bundle.putString("iso_key", this.preview.getISOKey());

		bundle.putString("noise_reduction_mode", this.preview.getNoiseReductionMode());
		putBundleExtra(bundle, "noise_reduction_modes", this.preview.getSupportedNoiseReductionModes());
		bundle.putString("edge_mode", this.preview.getEdgeMode());
		putBundleExtra(bundle, "edge_modes", this.preview.getSupportedEdgeModes());
		bundle.putString("optical_stabilization_mode", this.preview.getOpticalStabilizationMode());
		putBundleExtra(bundle, "optical_stabilization_modes", this.preview.getSupportedOpticalStabilizationModes());
		bundle.putString("hot_pixel_correction_mode", this.preview.getHotPixelCorrectionMode());
		putBundleExtra(bundle, "hot_pixel_correction_modes", this.preview.getSupportedHotPixelCorrectionModes());

		if (!this.preview.usingCamera2API()) {
			bundle.putString("zero_shutter_delay_mode", this.preview.getZeroShutterDelayMode());
			putBundleExtra(bundle, "zero_shutter_delay_modes", this.preview.getSupportedZeroShutterDelayModes());
		}

		if( this.preview.getCameraController() != null ) {
			bundle.putString("parameters_string", preview.getCameraController().getParametersString());
		}

		List<CameraController.Size> preview_sizes = this.preview.getSupportedPreviewSizes();
		if( preview_sizes != null ) {
			int [] widths = new int[preview_sizes.size()];
			int [] heights = new int[preview_sizes.size()];
			int i=0;
			for(CameraController.Size size: preview_sizes) {
				widths[i] = size.width;
				heights[i] = size.height;
				i++;
			}
			bundle.putIntArray("preview_widths", widths);
			bundle.putIntArray("preview_heights", heights);
		}
		bundle.putInt("preview_width", preview.getCurrentPreviewSize().width);
		bundle.putInt("preview_height", preview.getCurrentPreviewSize().height);

		List<CameraController.Size> sizes = this.preview.getSupportedPictureSizes();
		if( sizes != null ) {
			int [] widths = new int[sizes.size()];
			int [] heights = new int[sizes.size()];
			int i=0;
			for(CameraController.Size size: sizes) {
				widths[i] = size.width;
				heights[i] = size.height;
				i++;
			}
			bundle.putIntArray("resolution_widths", widths);
			bundle.putIntArray("resolution_heights", heights);
		}
		if( preview.getCurrentPictureSize() != null ) {
			bundle.putInt("resolution_width", preview.getCurrentPictureSize().width);
			bundle.putInt("resolution_height", preview.getCurrentPictureSize().height);
		}
		
		List<String> video_quality = this.preview.getVideoQualityHander().getSupportedVideoQuality();
		if( video_quality != null && this.preview.getCameraController() != null ) {
			String [] video_quality_arr = new String[video_quality.size()];
			String [] video_quality_string_arr = new String[video_quality.size()];
			int i=0;
			for(String value: video_quality) {
				video_quality_arr[i] = value;
				video_quality_string_arr[i] = this.preview.getCamcorderProfileDescription(value);
				i++;
			}
			bundle.putStringArray("video_quality", video_quality_arr);
			bundle.putStringArray("video_quality_string", video_quality_string_arr);
		}
		if( preview.getVideoQualityHander().getCurrentVideoQuality() != null ) {
			bundle.putString("current_video_quality", preview.getVideoQualityHander().getCurrentVideoQuality());
		}
		VideoProfile camcorder_profile = preview.getVideoProfile();
		bundle.putInt("video_frame_width", camcorder_profile.videoFrameWidth);
		bundle.putInt("video_frame_height", camcorder_profile.videoFrameHeight);
		bundle.putInt("video_bit_rate", camcorder_profile.videoBitRate);
		bundle.putInt("video_frame_rate", camcorder_profile.videoFrameRate);
		bundle.putDouble("video_capture_rate", camcorder_profile.videoCaptureRate);
		bundle.putBoolean("video_high_speed", preview.isVideoHighSpeed());
		bundle.putFloat("video_capture_rate_factor", Prefs.getVideoCaptureRateFactor());

		List<CameraController.Size> video_sizes = this.preview.getVideoQualityHander().getSupportedVideoSizes();
		if( video_sizes != null ) {
			int [] widths = new int[video_sizes.size()];
			int [] heights = new int[video_sizes.size()];
			int i=0;
			for(CameraController.Size size: video_sizes) {
				widths[i] = size.width;
				heights[i] = size.height;
				i++;
			}
			bundle.putIntArray("video_widths", widths);
			bundle.putIntArray("video_heights", heights);
		}
		
		putBundleExtra(bundle, "flash_values", this.preview.getSupportedFlashValues());
		putBundleExtra(bundle, "focus_values", this.preview.getSupportedFocusValues());

		preferencesListener.startListening();

		showPreview(false);
		setWindowFlagsForSettings();
		MyPreferenceFragment fragment = new MyPreferenceFragment(this);
		fragment.setArguments(bundle);
		// use commitAllowingStateLoss() instead of commit(), does to "java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState" crash seen on Google Play
		// see http://stackoverflow.com/questions/7575921/illegalstateexception-can-not-perform-this-action-after-onsaveinstancestate-wit
		getFragmentManager().beginTransaction().add(R.id.prefs_container, fragment, "PREFERENCE_FRAGMENT").addToBackStack(null).commitAllowingStateLoss();
	}
	
	public void closeSettings() {
		if( MyDebug.LOG )
			Log.d(TAG, "closeSettings");
			
		if( preferencesListener.needRestart() ) {
			preview.onPause();
			recreate();
			return;
		}

		preferencesListener.stopListening();
		setWindowFlagsForCamera();
		if( preferencesListener.needReconnect() ) {
			updateForSettings();
		} else {
			applicationInterface.onPrefsChanged();
			if (audio_control)
				startAudioListeners();
		}
		preview.setCameraDisplayOrientation(); // need to call in case the preview rotation option was changed
		if( preferencesListener.needUpdateOverlay() ) {
			mainUI.setOverlayImage();
		}
		if (preferencesListener.needReloadSound()) {
			Sound.reload();
		}
		if (!mainUI.inImmersiveMode()) {
			mainUI.showGUI(true);
			mainUI.setPopupIcons();
		}
		showPreview(true);
	}

	public void updateForSettings() {
		updateForSettings(null, false);
	}

	public void updateForSettings(String toast_message) {
		updateForSettings(toast_message, false);
	}

	/** Must be called when an settings (as stored in SharedPreferences) are made, so we can update the
	 *  camera, and make any other necessary changes.
	 * @param keep_popup If false, the popup will be closed and destroyed. Set to true if you're sure
	 *				   that the changed setting isn't one that requires the PopupView to be recreated
	 */
	public void updateForSettings(String toast_message, boolean keep_popup) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "updateForSettings()");
			if( toast_message != null ) {
				Log.d(TAG, "toast_message: " + toast_message);
			}
		}
		long debug_time = 0;
		if( MyDebug.LOG ) {
			debug_time = System.currentTimeMillis();
		}
		// make sure we're into continuous video mode
		// workaround for bug on Samsung Galaxy S5 with UHD, where if the user switches to another (non-continuous-video) focus mode, then goes to Settings, then returns and records video, the preview freezes and the video is corrupted
		// so to be safe, we always reset to continuous video mode, and then reset it afterwards
		preview.updateFocusForVideo();
		if( MyDebug.LOG )
			Log.d(TAG, "update folder history");
		save_location_history.updateFolderHistory(getStorageUtils().getSaveLocation(), true);
		// no need to update save_location_history_saf, as we always do this in onActivityResult()
		if( MyDebug.LOG ) {
			Log.d(TAG, "updateForSettings: time after update folder history: " + (System.currentTimeMillis() - debug_time));
		}

		if( !keep_popup ) {
			mainUI.destroyPopup(); // important as we don't want to use a cached popup
			if( MyDebug.LOG ) {
				Log.d(TAG, "updateForSettings: time after destroy popup: " + (System.currentTimeMillis() - debug_time));
			}
		}

		// update camera for changes made in prefs - do this without closing and reopening the camera app if possible for speed!
		// but need workaround for Nexus 7 bug on old camera API, where scene mode doesn't take effect unless the camera is restarted - I can reproduce this with other 3rd party camera apps, so may be a Nexus 7 issue...
		// doesn't happen if we allow using Camera2 API on Nexus 7, but reopen for consistency (and changing scene modes via
		// popup menu no longer should be calling updateForSettings() for Camera2, anyway)
		boolean need_reopen = false;
		if( preview.getCameraController() != null ) {
			String scene_mode = preview.getCameraController().getSceneMode();
			if( MyDebug.LOG )
				Log.d(TAG, "scene mode was: " + scene_mode);
			String key = Prefs.SCENE_MODE;
			String value = Prefs.getString(key, preview.getCameraController().getDefaultSceneMode());
			if( !value.equals(scene_mode) ) {
				if( MyDebug.LOG )
					Log.d(TAG, "scene mode changed to: " + value);
				need_reopen = true;
			}
			else {
				if( applicationInterface.useCamera2() ) {
					// need to reopen if fake flash mode changed, as it changes the available camera features, and we can only set this after opening the camera
					boolean camera2_fake_flash = preview.getCameraController().getUseCamera2FakeFlash();
					if( MyDebug.LOG )
						Log.d(TAG, "camera2_fake_flash was: " + camera2_fake_flash);
					if( Prefs.getBoolean(Prefs.CAMERA2_FAKE_FLASH, false) != camera2_fake_flash ) {
						if( MyDebug.LOG )
							Log.d(TAG, "camera2_fake_flash changed");
						need_reopen = true;
					}
				}
			}
		}
		if( MyDebug.LOG ) {
			Log.d(TAG, "updateForSettings: time after check need_reopen: " + (System.currentTimeMillis() - debug_time));
		}

		if (audio_control)
			startAudioListeners();

		initLocation(); // in case we've enabled or disabled GPS
		if( MyDebug.LOG ) {
			Log.d(TAG, "updateForSettings: time after init speech and location: " + (System.currentTimeMillis() - debug_time));
		}
		if( toast_message != null )
			block_startup_toast = true;
		if( need_reopen || preview.getCameraController() == null ) { // if camera couldn't be opened before, might as well try again
			preview.reopenCamera();
			if( MyDebug.LOG ) {
				Log.d(TAG, "updateForSettings: time after reopen: " + (System.currentTimeMillis() - debug_time));
			}
		}
		else {
			preview.setCameraDisplayOrientation(); // need to call in case the preview rotation option was changed
			if( MyDebug.LOG ) {
				Log.d(TAG, "updateForSettings: time after set display orientation: " + (System.currentTimeMillis() - debug_time));
			}
			preview.pausePreview(true);
			if( MyDebug.LOG ) {
				Log.d(TAG, "updateForSettings: time after pause: " + (System.currentTimeMillis() - debug_time));
			}
			preview.setupCamera(false, false);
			if( MyDebug.LOG ) {
				Log.d(TAG, "updateForSettings: time after setup: " + (System.currentTimeMillis() - debug_time));
			}
		}
		// don't set block_startup_toast to false yet, as camera might be closing/opening on background thread
		if( toast_message != null && toast_message.length() > 0 )
			Utils.showToast(null, toast_message);

		// don't need to reset to saved_focus_value, as we'll have done this when setting up the camera (or will do so when the camera is reopened, if need_reopen)
		/*if( saved_focus_value != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "switch focus back to: " + saved_focus_value);
			preview.updateFocus(saved_focus_value, true, false);
		}*/
		
		applicationInterface.onPrefsChanged();

		if( MyDebug.LOG ) {
			Log.d(TAG, "updateForSettings: done: " + (System.currentTimeMillis() - debug_time));
		}
	}

	private MyPreferenceFragment getPreferenceFragment() {
		return (MyPreferenceFragment)getFragmentManager().findFragmentByTag("PREFERENCE_FRAGMENT");
	}
	
	@Override
	public void onBackPressed() {
		if( screen_is_locked ) {
//			Utils.showToast(screen_locked_toast, R.string.screen_is_locked);
			return;
		}
		final MyPreferenceFragment fragment = getPreferenceFragment();
		if( fragment != null ) {
			closeSettings();
		}
		else {
			if( popupIsOpen() ) {
				mainUI.closePopup();
				return;
			} else if (!camera_in_background) {
				// Trying to fix reopening the app after back pressed
				finish();
				return;
			}
		}
		super.onBackPressed();
	}

	public boolean usingKitKatImmersiveMode() {
		// whether we are using a Kit Kat style immersive mode (either hiding GUI, or everything)
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ) {
			String immersive_mode = Prefs.getString(Prefs.IMMERSIVE_MODE, "immersive_mode_off");
			if( immersive_mode.equals("immersive_mode_gui") || immersive_mode.equals("immersive_mode_everything") )
				return true;
		}
		return false;
	}

	public boolean usingKitKatImmersiveModeEverything() {
		// whether we are using a Kit Kat style immersive mode for everything
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ) {
			String immersive_mode = Prefs.getString(Prefs.IMMERSIVE_MODE, "immersive_mode_off");
			if( immersive_mode.equals("immersive_mode_everything") )
				return true;
		}
		return false;
	}
	
	private void setImmersiveTimer() {
		clearImmersiveTimer();

		immersive_timer_handler = new Handler();
		immersive_timer_handler.postDelayed(immersive_timer_runnable = new Runnable(){
			@Override
			public void run(){
				if( MyDebug.LOG )
					Log.d(TAG, "setImmersiveTimer: run");
				if( !camera_in_background && !popupIsOpen() && usingKitKatImmersiveMode() )
					setImmersiveMode(true);
		   }
		}, 5000);
	}
	
	private void clearImmersiveTimer() {
		if( immersive_timer_handler != null && immersive_timer_runnable != null ) {
			immersive_timer_handler.removeCallbacks(immersive_timer_runnable);

			immersive_timer_handler = null;
			immersive_timer_runnable = null;
		}
	}

	public void initImmersiveMode() {
		if( !usingKitKatImmersiveMode() ) {
			setImmersiveMode(true);
		}
		else {
			// don't start in immersive mode, only after a timer
			setImmersiveTimer();
		}
	}

	void setImmersiveMode(boolean on) {
		if( MyDebug.LOG )
			Log.d(TAG, "setImmersiveMode: " + on);
		// n.b., preview.setImmersiveMode() is called from onSystemUiVisibilityChange()
		fullscreen = false;
		if( on ) {
			if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && usingKitKatImmersiveMode() ) {
				getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
			}
			else {
				String immersive_mode = Prefs.getString(Prefs.IMMERSIVE_MODE, "immersive_mode_off");
				if( immersive_mode.equals("immersive_mode_low_profile") )
					getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
				else if( immersive_mode.equals("immersive_mode_fullscreen") ) {
					fullscreen = true;
					setFullscreen();
				}
				else if( immersive_mode.equals("immersive_mode_sticky") )
					getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
				else
					getWindow().getDecorView().setSystemUiVisibility(0);
					
				if (immersive_mode.equals("immersive_mode_overlay")) {
					getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
				} else {
					getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
				}
			}
		}
		else
			getWindow().getDecorView().setSystemUiVisibility(0);
	}
	
	void setFullscreen() {
		getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
	}

	/** Sets the brightness level for normal operation (when camera preview is visible).
	 *  If force_max is true, this always forces maximum brightness; otherwise this depends on user preference.
	 */
	void setBrightnessForCamera(boolean force_max) {
		if( MyDebug.LOG )
			Log.d(TAG, "setBrightnessForCamera");
		// set screen to max brightness - see http://stackoverflow.com/questions/11978042/android-screen-brightness-max-value
		// done here rather than onCreate, so that changing it in preferences takes effect without restarting app
		WindowManager.LayoutParams layout = getWindow().getAttributes();
		if( force_max || Prefs.getBoolean(Prefs.MAX_BRIGHTNESS, false) ) {
			layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
		}
		else {
			layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
		}
		getWindow().setAttributes(layout);
	}

	void setMinBrightness() {
		if( MyDebug.LOG )
			Log.d(TAG, "setMinBrightness");
		WindowManager.LayoutParams layout = getWindow().getAttributes();
		layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
		getWindow().setAttributes(layout);
	}

	/** Sets the window flags for normal operation (when camera preview is visible).
	 */
	public void setWindowFlagsForCamera() {
		if( MyDebug.LOG )
			Log.d(TAG, "setWindowFlagsForCamera");
		/*{
			Intent intent = new Intent(this, MyWidgetProvider.class);
			intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
			AppWidgetManager widgetManager = AppWidgetManager.getInstance(this);
			ComponentName widgetComponent = new ComponentName(this, MyWidgetProvider.class);
			int[] widgetIds = widgetManager.getAppWidgetIds(widgetComponent);
			intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds);
			sendBroadcast(intent);
		}*/

		// force to landscape mode
		setRequestedOrientation(mainUI.isSystemUIPortrait() ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

		// keep screen active - see http://stackoverflow.com/questions/2131948/force-screen-on
		if( Prefs.getBoolean(Prefs.KEEP_DISPLAY_ON, false) ) {
			if( MyDebug.LOG )
				Log.d(TAG, "do keep screen on");
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		else {
			if( MyDebug.LOG )
				Log.d(TAG, "don't keep screen on");
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		if( Prefs.getBoolean(Prefs.SHOW_WHEN_LOCKED, true) ) {
			if( MyDebug.LOG )
				Log.d(TAG, "do show when locked");
			// keep Open Camera on top of screen-lock (will still need to unlock when going to gallery or settings)
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
		}
		else {
			if( MyDebug.LOG )
				Log.d(TAG, "don't show when locked");
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
		}
		

		setBrightnessForCamera(false);
		
		initImmersiveMode();
		camera_in_background = false;
	}
	
	/** Sets the window flags for when the settings window is open.
	 */
	public void setWindowFlagsForSettings() {
		switch (mainUI.getOrientation()) {
			case Landscape:
				setRequestedOrientation(mainUI.getUIPlacementRight() ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
				break;
			case Portrait:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
				break;
			default:
				// allow screen rotation
				fixRotation(false);
		}
		
		Window window = getWindow();
		// revert to standard screen blank behaviour
		window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		// settings should still be protected by screen lock
		window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
		window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

		{
			WindowManager.LayoutParams layout = window.getAttributes();
			layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
			window.setAttributes(layout);
		}

		setImmersiveMode(false);
		clearImmersiveTimer();
		camera_in_background = true;
	}
	
	private void fixRotation(final boolean for_other_app) {
		switch (mainUI.getUIRotationRelative()) {
			case 0:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
				break;
			case 180:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
				break;
			case 270:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
				break;
		}
		
		Handler handler = new Handler();
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (for_other_app) {
					if (camera_in_background) {
						switch (mainUI.getOrientation()) {
							case Landscape:
								setRequestedOrientation(mainUI.getUIPlacementRight() ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
								break;
							case Portrait:
								setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
								break;
							default:
								// allow screen rotation
								setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
						}
					} else {
						setRequestedOrientation(mainUI.isSystemUIPortrait() ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
					}
				} else {
					if (camera_in_background)
						setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
				}
			}
		}, 200);
	}
	
	public void showPreview(boolean show) {
		if( MyDebug.LOG )
			Log.d(TAG, "showPreview: " + show);
		final ViewGroup container = (ViewGroup)findViewById(R.id.hide_container);
		container.setBackgroundColor(Color.BLACK);
		container.setAlpha(show ? 0.0f : 1.0f);
	}
	
	public void updateGalleryIcon() {
		updateGalleryIcon(null);
	}

	/** Updates the gallery icon by searching for the most recent photo.
	 *  Launches the task in a separate thread.
	 */
	public void updateGalleryIcon(Bitmap bitmap) {
		long debug_time = 0;
		if( MyDebug.LOG ) {
			Log.d(TAG, "updateGalleryIcon");
			debug_time = System.currentTimeMillis();
		}

		new AsyncTask<Bitmap, Void, Bitmap>() {
			private static final String TAG = "HedgeCam/MainActivity/AsyncTask";

			/** The system calls this to perform work in a worker thread and
			  * delivers it the parameters given to AsyncTask.execute() */
			protected Bitmap doInBackground(Bitmap... bitmaps) {
				if( MyDebug.LOG )
					Log.d(TAG, "doInBackground");
				int rotation = 0;
				Bitmap thumbnail = bitmaps[0];
				if (thumbnail == null) {
					StorageUtils.Media media = applicationInterface.getStorageUtils().getLatestMedia();
					KeyguardManager keyguard_manager = (KeyguardManager)MainActivity.this.getSystemService(Context.KEYGUARD_SERVICE);
					boolean is_locked = keyguard_manager != null && keyguard_manager.inKeyguardRestrictedInputMode();
					if( MyDebug.LOG )
						Log.d(TAG, "is_locked?: " + is_locked);
					if( media != null && getContentResolver() != null && !is_locked ) {
						// check for getContentResolver() != null, as have had reported Google Play crashes
						try {
							if( media.video ) {
								  thumbnail = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), media.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
							}
							else {
								  thumbnail = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), media.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
							}
						}
						catch(Throwable exception) {
							// have had Google Play NoClassDefFoundError crashes from new ExifInterface() for Galaxy Ace4 (vivalto3g), Galaxy S Duos3 (vivalto3gvn)
							// also NegativeArraySizeException - best to catch everything
	 						if( MyDebug.LOG )
								Log.e(TAG, "exif orientation exception");
							exception.printStackTrace();
						}
						
						rotation = media.orientation;
					} else
						thumbnail = BitmapFactory.decodeResource(resources, R.drawable.gallery_blank);
				}
				if( thumbnail != null ) {
					int width = thumbnail.getWidth();
					int height = thumbnail.getHeight();
					if( MyDebug.LOG )
						Log.d(TAG, "thumbnail size is " + width + " x " + height);
					int dst_size;
					if (mainUI.shutter_icon_material)
						dst_size = resources.getDimensionPixelSize(R.dimen.button_gallery_rounded_size)-resources.getDimensionPixelSize(R.dimen.button_gallery_rounded_padding)*2;
					else
						dst_size = resources.getDimensionPixelSize(R.dimen.button_gallery_size)-resources.getDimensionPixelSize(R.dimen.button_gallery_padding)*2;
					float scale = (float)dst_size/(float)Math.min(width, height);
					if (scale != 1) {
						if( MyDebug.LOG )
							Log.d(TAG, "scale is " + scale);
						if (width > height) {
							width = (int)((float)width*scale);
							height = dst_size;
						} else {
							width = dst_size;
							height = (int)((float)height*scale);
						}
						thumbnail = Bitmap.createScaledBitmap(thumbnail, width, height, true);
						if( MyDebug.LOG )
							Log.d(TAG, "new_thumbnail size is " + width + " x " + height);
					}
					if (rotation != 0) {
						Matrix matrix = new Matrix();
						matrix.setRotate(rotation, width * 0.5f, height * 0.5f);
						try {
							Bitmap new_thumbnail = Bitmap.createBitmap(thumbnail, 0, 0, width, height, matrix, true);
							// careful, as rotated_thumbnail is sometimes not a copy!
							if( new_thumbnail != thumbnail ) {
								thumbnail.recycle();
								thumbnail = new_thumbnail;
								width = thumbnail.getWidth();
								height = thumbnail.getHeight();
							}
						}
						catch(Throwable t) {
							if( MyDebug.LOG )
								Log.d(TAG, "failed to rotate thumbnail");
						}
					}

					Bitmap new_thumbnail = null;
					if (mainUI.shutter_icon_material) {
						int thumb_size = Math.min(width, height);
						new_thumbnail = Bitmap.createBitmap(thumb_size, thumb_size, Bitmap.Config.ARGB_8888);

						final Canvas canvas = new Canvas(new_thumbnail);
						final Paint paint = new Paint();
						paint.setAntiAlias(true);
						paint.setColor(0xffffffff);
						canvas.drawCircle(thumb_size / 2, thumb_size / 2, thumb_size / 2, paint);
						paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
						if (width > height)
							canvas.drawBitmap(thumbnail, (int)((height-width)/2), 0, paint);
						else if (height > width)
							canvas.drawBitmap(thumbnail, 0, (int)((width-height)/2), paint);
						else
							canvas.drawBitmap(thumbnail, 0, 0, paint);
					} else {
						if (width > height)
							new_thumbnail = Bitmap.createBitmap(thumbnail, (int)((width-height)/2), 0, height, height);
						else if (height > width)
							new_thumbnail = Bitmap.createBitmap(thumbnail, 0, (int)((height-width)/2), width, width);
					}
					if (new_thumbnail != null) {
						thumbnail.recycle();
						thumbnail = new_thumbnail;
					}
				}
				return thumbnail;
			}

			/** The system calls this to perform work in the UI thread and delivers
			  * the result from doInBackground() */
			protected void onPostExecute(Bitmap thumbnail) {
				if( MyDebug.LOG )
					Log.d(TAG, "onPostExecute");
				// since we're now setting the thumbnail to the latest media on disk, we need to make sure clicking the Gallery goes to this
				applicationInterface.getStorageUtils().clearLastMediaScanned();
				if( thumbnail != null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "set gallery button to thumbnail");
					ImageButton galleryButton = (ImageButton)findViewById(R.id.gallery);
					galleryButton.setImageBitmap(thumbnail);
					if (gallery_bitmap != null) gallery_bitmap.recycle();
					gallery_bitmap = thumbnail;
				}
			}
		}.execute(bitmap);

		if( MyDebug.LOG )
			Log.d(TAG, "updateGalleryIcon: total time to update gallery icon: " + (System.currentTimeMillis() - debug_time));
	}

	void savingImage(final boolean started) {
		if( MyDebug.LOG )
			Log.d(TAG, "savingImage: " + started);

		this.runOnUiThread(new Runnable() {
			public void run() {
				final ImageButton galleryButton = (ImageButton) findViewById(R.id.gallery);
				if( started ) {
					//galleryButton.setColorFilter(0x80ffffff, PorterDuff.Mode.MULTIPLY);
					if( gallery_save_anim == null ) {
						gallery_save_anim = ValueAnimator.ofInt(Color.argb(255, 255, 255, 255), Color.argb(191, 127, 127, 127));
						gallery_save_anim.setEvaluator(new ArgbEvaluator());
						gallery_save_anim.setRepeatCount(ValueAnimator.INFINITE);
						gallery_save_anim.setRepeatMode(ValueAnimator.REVERSE);
						gallery_save_anim.setDuration(500);
					}
					if (!gallery_save_anim.isStarted()) {
						gallery_save_anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
							@Override
							public void onAnimationUpdate(ValueAnimator animation) {
								galleryButton.setColorFilter((Integer)animation.getAnimatedValue(), PorterDuff.Mode.MULTIPLY);
							}
						});
						gallery_save_anim.start();
					}
				} else {
					if( gallery_save_anim != null ) {
						gallery_save_anim.cancel();
					}
					galleryButton.setColorFilter(null);
					if (Prefs.getBoolean(Prefs.GHOST_IMAGE, false) && Prefs.getString(Prefs.GHOST_IMAGE_SOURCE, "last_photo").equals("last_photo")) {
						mainUI.setOverlayImage();
					}
				}
			}
		});
	}

	public void clickedGallery(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedGallery");
		
		if (applicationInterface.getStorageUtils().getLatestMedia() == null) return;
			
		//Intent intent = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
		Uri uri = applicationInterface.getStorageUtils().getLastMediaScanned();
		if( uri == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "go to latest media");
			StorageUtils.Media media = applicationInterface.getStorageUtils().getLatestMedia();
			if( media != null ) {
				uri = media.uri;
			}
		}

		if( uri != null ) {
			// check uri exists
			if( MyDebug.LOG )
				Log.d(TAG, "found most recent uri: " + uri);
			try {
				ContentResolver cr = getContentResolver();
				ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
				if( pfd == null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "uri no longer exists (1): " + uri);
					uri = null;
				}
				else {
					pfd.close();
				}
			}
			catch(IOException e) {
				if( MyDebug.LOG )
					Log.d(TAG, "uri no longer exists (2): " + uri);
				uri = null;
			}
		}
		if( uri == null ) {
			uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
		}
		if( !is_test ) {
			fixRotation(true);
			// don't do if testing, as unclear how to exit activity to finish test (for testGallery())
			if( MyDebug.LOG )
				Log.d(TAG, "launch uri:" + uri);
			final String REVIEW_ACTION = "com.android.camera.action.REVIEW";
			try {
				// REVIEW_ACTION means we can view video files without autoplaying
				Intent intent = new Intent(REVIEW_ACTION, uri);
				this.startActivity(intent);
			}
			catch(ActivityNotFoundException e) {
				if( MyDebug.LOG )
					Log.d(TAG, "REVIEW_ACTION intent didn't work, try ACTION_VIEW");
				Intent intent = new Intent(Intent.ACTION_VIEW, uri);
				// from http://stackoverflow.com/questions/11073832/no-activity-found-to-handle-intent - needed to fix crash if no gallery app installed
				//Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("blah")); // test
				if( intent.resolveActivity(getPackageManager()) != null ) {
					this.startActivity(intent);
				}
				else{
					Utils.showToast(null, R.string.no_gallery_app);
				}
			}
		}
	}

	/** Opens the Storage Access Framework dialog to select a folder.
	 * @param from_preferences Whether called from the Preferences
	 */
	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	void openFolderChooserDialogSAF(boolean from_preferences, boolean is_video_folder) {
		if( MyDebug.LOG )
			Log.d(TAG, "openFolderChooserDialogSAF: " + from_preferences);
		this.saf_dialog_from_preferences = from_preferences;
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		//Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		//intent.addCategory(Intent.CATEGORY_OPENABLE);
		startActivityForResult(intent, is_video_folder ? SAF_CODE_SAVE_VIDEO_FOLDER : SAF_CODE_SAVE_FOLDER);
	}

	/** Opens the Storage Access Framework dialog to select a file for ghost image.
	 * @param from_preferences Whether called from the Preferences
	 */
	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	void openGhostImageChooserDialogSAF(boolean from_preferences) {
		if( MyDebug.LOG )
			Log.d(TAG, "openGhostImageChooserDialogSAF: " + from_preferences);
		this.saf_dialog_from_preferences = from_preferences;
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("image/*");
		try {
			startActivityForResult(intent, SAF_CODE_GHOST_IMAGE);
		}
		catch(ActivityNotFoundException e) {
			// see https://stackoverflow.com/questions/34021039/action-open-document-not-working-on-miui/34045627
			Utils.showToast(null, R.string.open_files_saf_exception_ghost);
			Log.e(TAG, "ActivityNotFoundException from startActivityForResult");
			e.printStackTrace();
		}
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	void openSettingsFileChooserDialogSAF(boolean from_preferences, boolean clear) {
		if( MyDebug.LOG )
			Log.d(TAG, "openBackupChooserDialogSAF: " + from_preferences);
		this.saf_dialog_from_preferences = from_preferences;
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("text/xml");
		try {
			startActivityForResult(intent, clear ? SAF_CODE_OPEN_BACKUP : SAF_CODE_OPEN_XML_SETTINGS);
		}
		catch(ActivityNotFoundException e) {
			// see https://stackoverflow.com/questions/34021039/action-open-document-not-working-on-miui/34045627
			Utils.showToast(null, R.string.open_files_saf_exception_ghost);
			Log.e(TAG, "ActivityNotFoundException from startActivityForResult");
			e.printStackTrace();
		}
	}

	/** Call when the SAF save history has been updated.
	 *  This is only public so we can call from testing.
	 * @param save_folder The new SAF save folder Uri.
	 */
	public void updateFolderHistorySAF(String save_folder) {
		if( MyDebug.LOG )
			Log.d(TAG, "updateSaveHistorySAF");
		if( save_location_history_saf == null ) {
			save_location_history_saf = new SaveLocationHistory(this, "save_location_history_saf", save_folder);
		}
		save_location_history_saf.updateFolderHistory(save_folder, true);
	}
	
	public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
		if( MyDebug.LOG )
			Log.d(TAG, "onActivityResult: " + requestCode);
		if( requestCode == SAF_CODE_SAVE_FOLDER || requestCode == SAF_CODE_SAVE_VIDEO_FOLDER ) {
			if( resultCode == RESULT_OK && resultData != null ) {
				Uri treeUri = resultData.getData();
				if( MyDebug.LOG )
					Log.d(TAG, "returned treeUri: " + treeUri);
				// from https://developer.android.com/guide/topics/providers/document-provider.html#permissions :
				final int takeFlags = resultData.getFlags()
						& (Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				try {
					/*if( true )
						throw new SecurityException(); // test*/
					// Check for the freshest data.
					getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

					Prefs.setString(requestCode == SAF_CODE_SAVE_VIDEO_FOLDER ? Prefs.SAVE_VIDEO_LOCATION_SAF : Prefs.SAVE_LOCATION_SAF, treeUri.toString());

					if( MyDebug.LOG )
						Log.d(TAG, "update folder history for saf");
					updateFolderHistorySAF(treeUri.toString());

					File file = applicationInterface.getStorageUtils().getFileFromDocumentUriSAF(treeUri, true);
					if( file != null ) {
						Utils.showToast(null, resources.getString(R.string.changed_save_location) + "\n" + file.getAbsolutePath());
					}
				}
				catch(SecurityException e) {
					Log.e(TAG, "SecurityException failed to take permission");
					e.printStackTrace();
					if (requestCode == SAF_CODE_SAVE_FOLDER) {
						// failed - if the user had yet to set a save location, make sure we switch SAF back off
						String uri = Prefs.getString(Prefs.SAVE_LOCATION_SAF, "");
						if( uri.length() == 0 ) {
							if( MyDebug.LOG )
								Log.d(TAG, "no SAF save location was set");
							Prefs.setBoolean(Prefs.USING_SAF, false);
							Utils.showToast(null, R.string.saf_permission_failed);
						}
					}
				}
			}
			else {
				if( MyDebug.LOG )
					Log.d(TAG, "SAF dialog cancelled");
				// cancelled - if the user had yet to set a save location, make sure we switch SAF back off
				String uri = Prefs.getString(Prefs.SAVE_LOCATION_SAF, "");
				if( uri.length() == 0 ) {
					if( MyDebug.LOG )
						Log.d(TAG, "no SAF save location was set");
					Prefs.setBoolean(Prefs.USING_SAF, false);
					Utils.showToast(null, R.string.saf_cancelled);
				}
			}

			if( !saf_dialog_from_preferences ) {
				setWindowFlagsForCamera();
				showPreview(true);
			}
		} else if ( requestCode == SAF_CODE_GHOST_IMAGE ) {
			boolean check_source_pref = false;
			if( resultCode == RESULT_OK && resultData != null ) {
				Uri fileUri = resultData.getData();
				if( MyDebug.LOG )
					Log.d(TAG, "returned single fileUri: " + fileUri);
				// persist permission just in case?
				final int takeFlags = resultData.getFlags()
						& (Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				try {
					// Check for the freshest data.
					getContentResolver().takePersistableUriPermission(fileUri, takeFlags);

					Prefs.setString(Prefs.GHOST_IMAGE_FILE_SAF, fileUri.toString());
				}
				catch(SecurityException e) {
					Log.e(TAG, "SecurityException failed to take permission");
					e.printStackTrace();
					Utils.showToast(null, R.string.saf_permission_failed_open_image);
					// failed - if the user had yet to set a ghost image
					check_source_pref = true;
				}
			}
			else {
				if( MyDebug.LOG )
					Log.d(TAG, "SAF dialog cancelled");
				// cancelled - if the user had yet to set a ghost image, make sure we switch the option back off
				check_source_pref = true;
			}

			if (check_source_pref) {
				String uri = Prefs.getString(Prefs.GHOST_IMAGE_FILE_SAF, "");
				if( uri.length() == 0 ) {
					if( MyDebug.LOG )
						Log.d(TAG, "no SAF ghost image was set");
					Prefs.setString(Prefs.GHOST_IMAGE_SOURCE, "last_photo");
				}
			}

			if( !saf_dialog_from_preferences ) {
				setWindowFlagsForCamera();
				showPreview(true);
			}
		} else {
			final MyPreferenceFragment fragment = getPreferenceFragment();
			if (fragment != null)
				fragment.onActivityResult(requestCode,  resultCode, resultData);
		}
	}

	void updateSaveFolder(String new_save_location, boolean is_video_folder) {
		if( MyDebug.LOG )
			Log.d(TAG, "updateSaveFolder: " + new_save_location);
		if( new_save_location != null ) {
			String orig_save_location = this.applicationInterface.getStorageUtils().getSaveLocation();

			if( !orig_save_location.equals(new_save_location) ) {
				if( MyDebug.LOG )
					Log.d(TAG, "changed save_folder to: " + this.applicationInterface.getStorageUtils().getSaveLocation());
				Prefs.setString(is_video_folder ? Prefs.SAVE_VIDEO_LOCATION : Prefs.SAVE_LOCATION, new_save_location);

				this.save_location_history.updateFolderHistory(new_save_location, true);
				Utils.showToast(null, resources.getString(R.string.changed_save_location) + "\n" + new_save_location);
			}
		}
	}

	/** Opens Open Camera's own (non-Storage Access Framework) dialog to select a folder.
	 */
	private void openFolderChooserDialog(final boolean is_video_folder) {
		if( MyDebug.LOG )
			Log.d(TAG, "openFolderChooserDialog");
		new FileListDialog(is_video_folder ?  Prefs.SAVE_VIDEO_LOCATION : null, new FileListDialog.Listener() {
			@Override
			public void onSelected(String folder) {
				setWindowFlagsForCamera();
				showPreview(true);
				updateSaveFolder(folder, is_video_folder);
			}
			
			@Override
			public void onCancelled() {
				setWindowFlagsForCamera();
				showPreview(true);
			}
		}).show(getFragmentManager(), "FOLDER_FRAGMENT");
	}

	/** User can long-click on gallery to select a recent save location from the history, of if not available,
	 *  go straight to the file dialog to pick a folder.
	 */
	private void longClickedGallery() {
		if( MyDebug.LOG )
			Log.d(TAG, "longClickedGallery");
		showPreview(false);
		setWindowFlagsForSettings();
		if( applicationInterface.getStorageUtils().isUsingSAF() ) {
			if( save_location_history_saf == null || save_location_history_saf.size() <= 1 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "go straight to choose folder dialog for SAF");
				openFolderChooserDialogSAF(false, Prefs.isVideoFolder());
				return;
			}
		}
		else {
			if( save_location_history.size() <= 1 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "go straight to choose folder dialog");
				openFolderChooserDialog(Prefs.isVideoFolder());
				return;
			}
		}

		final SaveLocationHistory history = applicationInterface.getStorageUtils().isUsingSAF() ? save_location_history_saf : save_location_history;
		showPreview(false);
		AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
		alertDialog.setTitle(R.string.choose_save_location);
		
		alertDialog.setNegativeButton(resources.getString(R.string.clear_folder_history), new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				if( MyDebug.LOG )
					Log.d(TAG, "selected clear save history");
				new AlertDialog.Builder(MainActivity.this)

				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.clear_folder_history)
				.setMessage(R.string.clear_folder_history_question)
				.setPositiveButton(R.string.answer_yes, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						if( MyDebug.LOG )
							Log.d(TAG, "confirmed clear save history");
						if( applicationInterface.getStorageUtils().isUsingSAF() )
							clearFolderHistorySAF();
						else
							clearFolderHistory();
						setWindowFlagsForCamera();
						showPreview(true);
					}
				})
				.setNegativeButton(R.string.answer_no, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						if( MyDebug.LOG )
							Log.d(TAG, "don't clear save history");
						setWindowFlagsForCamera();
						showPreview(true);
					}
				})
				.setOnCancelListener(new DialogInterface.OnCancelListener() {
					@Override
					public void onCancel(DialogInterface arg0) {
						if( MyDebug.LOG )
							Log.d(TAG, "cancelled clear save history");
						setWindowFlagsForCamera();
						showPreview(true);
					}
				})
				.show();
			}
		});

		alertDialog.setNeutralButton(resources.getString(R.string.choose_another_folder), new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				if( MyDebug.LOG )
					Log.d(TAG, "selected choose new folder");
				if( applicationInterface.getStorageUtils().isUsingSAF() ) {
					openFolderChooserDialogSAF(false, Prefs.isVideoFolder());
				}
				else {
					openFolderChooserDialog(Prefs.isVideoFolder());
				}
			}
		});

		CharSequence [] items = new CharSequence[history.size()];
		String current_folder = getStorageUtils().isUsingSAF() ? getStorageUtils().getSaveLocationSAF() : getStorageUtils().getSaveLocation();
		int current_index = -1;
		// history is stored in order most-recent-last
		for(int i=0; i<history.size(); i++) {
			String folder_name = history.get(history.size() - 1 - i);
			if (folder_name.equals(current_folder))
				current_index = i;
			if( getStorageUtils().isUsingSAF() ) {
				// try to get human readable form if possible
				File file = getStorageUtils().getFileFromDocumentUriSAF(Uri.parse(folder_name), true);
				if( file != null ) {
					folder_name = file.getAbsolutePath();
				}
			}
			items[i] = folder_name;
		}
		alertDialog.setSingleChoiceItems(items, current_index, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				if( MyDebug.LOG )
					Log.d(TAG, "selected: " + which);
				if( which >= 0 && which < history.size() ) {
					String save_folder = history.get(history.size() - 1 - which);
					if( MyDebug.LOG )
						Log.d(TAG, "changed save_folder from history to: " + save_folder);
					String save_folder_name = save_folder;
					if( applicationInterface.getStorageUtils().isUsingSAF() ) {
						// try to get human readable form if possible
						File file = applicationInterface.getStorageUtils().getFileFromDocumentUriSAF(Uri.parse(save_folder), true);
						if( file != null ) {
							save_folder_name = file.getAbsolutePath();
						}
					}
					Utils.showToast(null, resources.getString(R.string.changed_save_location) + "\n" + save_folder_name);
					Prefs.setString(applicationInterface.getStorageUtils().isUsingSAF() ? Prefs.SAVE_LOCATION_SAF : Prefs.SAVE_LOCATION, save_folder);
					history.updateFolderHistory(save_folder, true); // to move new selection to most recent
				}
				setWindowFlagsForCamera();
				showPreview(true);

				dialog.dismiss();
			}
		});
		alertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
			@Override
			public void onCancel(DialogInterface arg0) {
				setWindowFlagsForCamera();
				showPreview(true);
			}
		});
		alertDialog.show();
	}

	/** Clears the non-SAF folder history.
	 */
	public void clearFolderHistory() {
		if( MyDebug.LOG )
			Log.d(TAG, "clearFolderHistory");
		save_location_history.clearFolderHistory(getStorageUtils().getSaveLocation());
	}

	/** Clears the SAF folder history.
	 */
	public void clearFolderHistorySAF() {
		if( MyDebug.LOG )
			Log.d(TAG, "clearFolderHistorySAF");
		save_location_history_saf.clearFolderHistory(getStorageUtils().getSaveLocationSAF());
	}

	static private void putBundleExtra(Bundle bundle, String key, List<String> values) {
		if( values != null ) {
			String [] values_arr = new String[values.size()];
			int i=0;
			for(String value: values) {
				values_arr[i] = value;
				i++;
			}
			bundle.putStringArray(key, values_arr);
		}
	}

	public void clickedShare(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedShare");
		applicationInterface.shareLastImage();
	}

	public void clickedTrash(View view) {
		if( MyDebug.LOG )
			Log.d(TAG, "clickedTrash");
		applicationInterface.trashLastImage();
	}

	private final boolean test_panorama = false;

	/** User has pressed the take picture button, or done an equivalent action to request this (e.g.,
	 *  volume buttons, audio trigger).
	 */
	public void takePicture() {
		if( MyDebug.LOG )
			Log.d(TAG, "takePicture");

		if( test_panorama ) {
			if (applicationInterface.getGyroSensor().isRecording()) {
				if (MyDebug.LOG)
					Log.d(TAG, "panorama complete");
				applicationInterface.stopPanorama();
				return;
			} else {
				if (MyDebug.LOG)
					Log.d(TAG, "start panorama");
				applicationInterface.startPanorama();
			}
		}

		this.takePicturePressed();
	}
	
	public void pauseVideo() {
		if( MyDebug.LOG )
			Log.d(TAG, "pauseVideo");
		if( preview.isVideoRecording() ) { // just in case
			preview.pauseVideo();
			mainUI.setPauseVideoContentDescription();
		}
	}

	void takePicturePressed() {
		if( MyDebug.LOG )
			Log.d(TAG, "takePicturePressed");

		mainUI.closePopup();

		if( applicationInterface.getGyroSensor().isRecording() ) {
			if (MyDebug.LOG)
				Log.d(TAG, "set next panorama point");
			applicationInterface.setNextPanoramaPoint();
		}

		this.preview.takePicturePressed();
	}
	
	/** Lock the screen - this is Open Camera's own lock to guard against accidental presses,
	 *  not the standard Android lock.
	 */
	void lockScreen() {
		findViewById(R.id.locker).setOnTouchListener(new View.OnTouchListener() {
			@SuppressLint("ClickableViewAccessibility") @Override
			public boolean onTouch(View arg0, MotionEvent event) {
				return gestureDetector.onTouchEvent(event);
				//return true;
			}
		});
		screen_is_locked = true;
	}

	/** Unlock the screen (see lockScreen()).
	 */
	void unlockScreen() {
		findViewById(R.id.locker).setOnTouchListener(null);
		screen_is_locked = false;
	}
	
	/** Whether the screen is locked (see lockScreen()).
	 */
	public boolean isScreenLocked() {
		return screen_is_locked;
	}

	/** Listen for gestures.
	 *  Doing a swipe will unlock the screen (see lockScreen()).
	 */
	private class MyGestureDetector extends SimpleOnGestureListener {
		@Override
		public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
			try {
				if( MyDebug.LOG )
					Log.d(TAG, "from " + e1.getX() + " , " + e1.getY() + " to " + e2.getX() + " , " + e2.getY());
				final ViewConfiguration vc = ViewConfiguration.get(MainActivity.this);
				//final int swipeMinDistance = 4*vc.getScaledPagingTouchSlop();
				final float scale = resources.getDisplayMetrics().density;
				final int swipeMinDistance = (int) (160 * scale + 0.5f); // convert dps to pixels
				final int swipeThresholdVelocity = vc.getScaledMinimumFlingVelocity();
				if( MyDebug.LOG ) {
					Log.d(TAG, "from " + e1.getX() + " , " + e1.getY() + " to " + e2.getX() + " , " + e2.getY());
					Log.d(TAG, "swipeMinDistance: " + swipeMinDistance);
				}
				float xdist = e1.getX() - e2.getX();
				float ydist = e1.getY() - e2.getY();
				float dist2 = xdist*xdist + ydist*ydist;
				float vel2 = velocityX*velocityX + velocityY*velocityY;
				if( dist2 > swipeMinDistance*swipeMinDistance && vel2 > swipeThresholdVelocity*swipeThresholdVelocity ) {
					Utils.showToast(screen_locked_toast, R.string.unlocked);
					unlockScreen();
				}
			}
			catch(Exception e) {
				e.printStackTrace();
			}
			return false;
		}

		@Override
		public boolean onDown(MotionEvent e) {
//			Utils.showToast(screen_locked_toast, R.string.screen_is_locked);
			return true;
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle state) {
		if( MyDebug.LOG )
			Log.d(TAG, "onSaveInstanceState");
		super.onSaveInstanceState(state);
		if( this.preview != null ) {
			preview.onSaveInstanceState(state);
		}
	}
	
	public boolean supportsExposureButton() {
		if( preview.getCameraController() == null )
			return false;
		String iso_value = Prefs.getISOPref();
		boolean manual_iso = !iso_value.equals("auto");
		return preview.supportsExposures() || (manual_iso && preview.supportsISORange() );
	}

	void cameraSetup() {
		long debug_time = 0;
		if( MyDebug.LOG ) {
			Log.d(TAG, "cameraSetup");
			debug_time = System.currentTimeMillis();
		}
		if( this.supportsForceVideo4K() && preview.usingCamera2API() ) {
			if( MyDebug.LOG )
				Log.d(TAG, "using Camera2 API, so can disable the force 4K option");
			this.disableForceVideo4K();
		}
		if( this.supportsForceVideo4K() && preview.getVideoQualityHander().getSupportedVideoSizes() != null ) {
			for(CameraController.Size size : preview.getVideoQualityHander().getSupportedVideoSizes()) {
				if( size.width >= 3840 && size.height >= 2160 ) {
					if( MyDebug.LOG )
						Log.d(TAG, "camera natively supports 4K, so can disable the force option");
					this.disableForceVideo4K();
				}
			}
		}
		if( MyDebug.LOG )
			Log.d(TAG, "cameraSetup: time after handling Force 4K option: " + (System.currentTimeMillis() - debug_time));

		mainUI.setZoomSeekbar();
		if( MyDebug.LOG )
			Log.d(TAG, "cameraSetup: time after setting up zoom: " + (System.currentTimeMillis() - debug_time));

		mainUI.setManualFocusSeekbars();
		if( MyDebug.LOG )
			Log.d(TAG, "cameraSetup: time after setManualFocusSeekbars(): " + (System.currentTimeMillis() - debug_time));

		mainUI.setManualIsoSeekbars();
		mainUI.setManualWBSeekbar();
		if( MyDebug.LOG )
			Log.d(TAG, "cameraSetup: time after setting up iso: " + (System.currentTimeMillis() - debug_time));
			
		mainUI.setExposureSeekbar();
		if( MyDebug.LOG )
			Log.d(TAG, "cameraSetup: time after setting up exposure: " + (System.currentTimeMillis() - debug_time));

		mainUI.updateSeekbars(false);
		mainUI.showGUI(true);
		if( MyDebug.LOG )
			Log.d(TAG, "cameraSetup: time after setting exposure lock button: " + (System.currentTimeMillis() - debug_time));

		mainUI.setPopupIcons(); // needed so that the icon is set right even if no flash mode is set when starting up camera (e.g., switching to front camera with no flash)
		if( MyDebug.LOG )
			Log.d(TAG, "cameraSetup: time after setting popup icon: " + (System.currentTimeMillis() - debug_time));

		mainUI.setTakePhotoIcon();
		mainUI.setSwitchCameraContentDescription();
		if( MyDebug.LOG )
			Log.d(TAG, "cameraSetup: time after setting take photo icon: " + (System.currentTimeMillis() - debug_time));

		if( !block_startup_toast ) {
			this.showPhotoVideoToast(false);
		}
		block_startup_toast = false;
		if( MyDebug.LOG )
			Log.d(TAG, "cameraSetup: total time for cameraSetup: " + (System.currentTimeMillis() - debug_time));
	}

	public boolean supportsAutoStabilise() {
		return this.supports_auto_stabilise;
	}
	
	public boolean supportsRenderScript() {
		return( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT );
	}

	public boolean supportsDRO() {
		return this.supportsRenderScript();
	}

	public boolean supportsHDR() {
		// we also require the device have sufficient memory to do the processing, simplest to use the same test as we do for auto-stabilise...
		return( this.supportsRenderScript() && this.supportsAutoStabilise() && preview.supportsExpoBracketing() );
	}
	
	public boolean supportsExpoBracketing() {
		return preview.supportsExpoBracketing();
	}

	public boolean supportsFocusBracketing() {
		List<String> supported_focus_values = preview.getSupportedFocusValues();
		if( supported_focus_values != null ) {
			return supported_focus_values.indexOf("focus_mode_manual2") != -1;
		}
		return false;
	}

	public boolean supportsFastBurst() {
		return true;
	}

	public boolean supportsNoiseReduction() {
		return this.supportsRenderScript();
	}

	private int maxExpoBracketingNImages() {
		return preview.maxExpoBracketingNImages();
	}

	public boolean supportsForceVideo4K() {
		return this.supports_force_video_4k;
	}

	public boolean supportsCamera2() {
		return this.supports_camera2;
	}

	public boolean supportsVideoPause() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
	}

	private void disableForceVideo4K() {
		this.supports_force_video_4k = false;
	}

	/** Return free memory in MB.
	 */
	@SuppressWarnings("deprecation")
	public long freeMemory() { // return free memory in MB
		if( MyDebug.LOG )
			Log.d(TAG, "freeMemory");
		try {
			File folder = applicationInterface.getStorageUtils().getImageFolder();
			if( folder == null ) {
				throw new IllegalArgumentException(); // so that we fall onto the backup
			}
			StatFs statFs = new StatFs(folder.getAbsolutePath());
			// cast to long to avoid overflow!
			long blocks = statFs.getAvailableBlocks();
			long size = statFs.getBlockSize();
			return (blocks*size) / 1048576;
		}
		catch(IllegalArgumentException e) {
			// this can happen if folder doesn't exist, or don't have read access
			// if the save folder is a subfolder of DCIM, we can just use that instead
			try {
				if( !applicationInterface.getStorageUtils().isUsingSAF() ) {
					// StorageUtils.getSaveLocation() only valid if !isUsingSAF()
					String folder_name = applicationInterface.getStorageUtils().getSaveLocation();
					if( !folder_name.startsWith("/") ) {
						File folder = StorageUtils.getBaseFolder();
						StatFs statFs = new StatFs(folder.getAbsolutePath());
						// cast to long to avoid overflow!
						long blocks = statFs.getAvailableBlocks();
						long size = statFs.getBlockSize();
						return (blocks*size) / 1048576;
					}
				}
			}
			catch(IllegalArgumentException e2) {
				// just in case
			}
		}
		return -1;
	}
	
	public Preview getPreview() {
		return this.preview;
	}
	
	public MainUI getMainUI() {
		return this.mainUI;
	}
	
	public MyApplicationInterface getApplicationInterface() {
		return this.applicationInterface;
	}

	public TextFormatter getTextFormatter() {
		return this.textFormatter;
	}
	
	public LocationSupplier getLocationSupplier() {
		return this.applicationInterface.getLocationSupplier();
	}
	
	public StorageUtils getStorageUtils() {
		return this.applicationInterface.getStorageUtils();
	}

	public File getImageFolder() {
		return this.applicationInterface.getStorageUtils().getImageFolder();
	}

	public ToastBoxer getChangedAutoStabiliseToastBoxer() {
		return changed_auto_stabilise_toast;
	}
	
	public boolean cameraInBackground() {
		return this.camera_in_background;
	}

	/** Displays a toast with information about the current preferences.
	 *  If always_show is true, the toast is always displayed; otherwise, we only display
	 *  a toast if it's important to notify the user (i.e., unusual non-default settings are
	 *  set). We want a balance between not pestering the user too much, whilst also reminding
	 *  them if certain settings are on.
	 */
	private void showPhotoVideoToast(boolean always_show) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "showPhotoVideoToast");
			Log.d(TAG, "always_show? " + always_show);
		}
		CameraController camera_controller = preview.getCameraController();
		if( camera_controller == null || this.camera_in_background ) {
			if( MyDebug.LOG )
				Log.d(TAG, "camera not open or in background");
			return;
		}
		String toast_string;
		boolean simple = true;
		boolean video_high_speed = preview.isVideoHighSpeed();
		if( preview.isVideo() ) {
			VideoProfile profile = preview.getVideoProfile();
			String bitrate_string;
			if( profile.videoBitRate >= 10000000 )
				bitrate_string = profile.videoBitRate/1000000 + "Mbps";
			else if( profile.videoBitRate >= 10000 )
				bitrate_string = profile.videoBitRate/1000 + "Kbps";
			else
				bitrate_string = profile.videoBitRate + "bps";
			String bitrate_value = Prefs.getString(Prefs.VIDEO_BITRATE, "default");
			if( !bitrate_value.equals("default") ) {
				simple = false;
			}

			int capture_rate = (int)(profile.videoCaptureRate+0.5);
			toast_string = resources.getString(R.string.video) + ": " + profile.videoFrameWidth + "x" + profile.videoFrameHeight + ", " + capture_rate + "fps" + (video_high_speed ? " [" + getResources().getString(R.string.high_speed) + "]" : "") + ", " + bitrate_string;

			String fps_value = Prefs.getVideoFPSPref();
			if( !fps_value.equals("default") || video_high_speed ) {
				simple = false;
			}

			float capture_rate_factor = Prefs.getVideoCaptureRateFactor();
			if( Math.abs(capture_rate_factor - 1.0f) > 1.0e-5 ) {
				toast_string += "\n" + resources.getString(R.string.preference_video_capture_rate) + ": " + capture_rate_factor + "x";
				simple = false;
			}

			boolean record_audio = Prefs.getBoolean(Prefs.RECORD_AUDIO, true);
			if( !record_audio ) {
				toast_string += "\n" + resources.getString(R.string.audio_disabled);
				simple = false;
			}
			String max_duration_value = Prefs.getString(Prefs.VIDEO_MAX_DURATION, "0");
			if( max_duration_value.length() > 0 && !max_duration_value.equals("0") ) {
				toast_string += "\n" + resources.getString(R.string.max_duration) + ": " + Utils.findEntryForValue(
					max_duration_value,
					R.array.preference_video_max_duration_entries,
					R.array.preference_video_max_duration_values
				);
				simple = false;
			}
			long max_filesize = Prefs.getVideoMaxFileSizeUserPref();
			if( max_filesize != 0 ) {
				long max_filesize_mb = max_filesize/(1024*1024);
				toast_string += "\n" + resources.getString(R.string.max_filesize) +": " + max_filesize_mb + resources.getString(R.string.mb_abbreviation);
				simple = false;
			}
			if( Prefs.getBoolean(Prefs.VIDEO_FLASH, false) && preview.supportsFlash() ) {
				toast_string += "\n" + resources.getString(R.string.preference_video_flash);
				simple = false;
			}
		}
		else {
			toast_string = resources.getString(R.string.photo);
			CameraController.Size current_size = preview.getCurrentPictureSize();
			toast_string += " " + current_size.width + "x" + current_size.height;
			if( preview.supportsFocus() && preview.getSupportedFocusValues().size() > 1 ) {
				String focus_value = preview.getCurrentFocusValue();
				if( focus_value != null && !focus_value.equals("focus_mode_auto") && !focus_value.equals("focus_mode_continuous_picture") ) {
					String focus_entry = Utils.findEntryForValue(focus_value, R.array.focus_mode_entries, R.array.focus_mode_values);
					if( focus_entry != null ) {
						toast_string += "\n" + focus_entry;
					}
				}
			}
			if( Prefs.getBoolean(Prefs.AUTO_STABILISE, false) ) {
				// important as users are sometimes confused at the behaviour if they don't realise the option is on
				toast_string += "\n" + resources.getString(R.string.preference_auto_stabilise);
				simple = false;
			}
			if (Prefs.getPhotoMode() != Prefs.PhotoMode.Standard) {
				toast_string += "\n" + resources.getString(R.string.photo_mode) + ": " + Utils.findEntryForValue(
					Prefs.getPhotoModePref(),
					R.array.photo_mode_entries,
					R.array.photo_mode_values
				);
				simple = false;
			}
		}
		if( Prefs.getBoolean(Prefs.FACE_DETECTION, false) ) {
			// important so that the user realises why touching for focus/metering areas won't work - easy to forget that face detection has been turned on!
			toast_string += "\n" + resources.getString(R.string.preference_face_detection);
			simple = false;
		}
		String iso_value = Prefs.getISOPref();
		if( !iso_value.equals(camera_controller.getDefaultISO()) ) {
			toast_string += "\n" + preview.getISOString(iso_value);
			simple = false;
		}
		int current_exposure = camera_controller.getExposureCompensation();
		if( current_exposure != 0 ) {
			toast_string += "\n" + resources.getString(R.string.exposure_compensation) + ": " + preview.getExposureCompensationString(current_exposure);
			simple = false;
		}
		String scene_mode = camera_controller.getSceneMode();
		if( scene_mode != null && !scene_mode.equals(camera_controller.getDefaultSceneMode()) ) {
			toast_string += "\n" + resources.getString(R.string.scene_mode) + ": " + Utils.getStringResourceByName("sm_", scene_mode);
			simple = false;
		}
		String white_balance = camera_controller.getWhiteBalance();
		if( white_balance != null && !white_balance.equals(camera_controller.getDefaultWhiteBalance()) ) {
			toast_string += "\n" + resources.getString(R.string.white_balance) + ": " + Utils.getStringResourceByName("wb_", white_balance);
			if( white_balance.equals("manual") && preview.supportsWhiteBalanceTemperature() ) {
				toast_string += " " + camera_controller.getWhiteBalanceTemperature();
			}
			simple = false;
		}
		String color_effect = camera_controller.getColorEffect();
		if( color_effect != null && !color_effect.equals(camera_controller.getDefaultColorEffect()) ) {
			toast_string += "\n" + resources.getString(R.string.color_effect) + ": " + Utils.getStringResourceByName("ce_", color_effect);
			simple = false;
		}
		if (selfie_mode) {
			String timer = Prefs.getString(Prefs.TIMER, "5");
			if( !timer.equals("0") ) {
				toast_string += "\n" + resources.getString(R.string.preference_timer) + ": " + Utils.findEntryForValue(
					timer,
					R.array.preference_timer_entries,
					R.array.preference_timer_values
				);
				simple = false;
			}
			String repeat = Prefs.getString(Prefs.BURST_MODE, "1");
			if( !repeat.equals("1") ) {
				toast_string += "\n" + resources.getString(R.string.preference_burst_mode) + ": " + Utils.findEntryForValue(
					repeat,
					R.array.preference_burst_mode_entries,
					R.array.preference_burst_mode_values
				);
				simple = false;
			}
		}
		/*if( audio_listener != null ) {
			toast_string += "\n" + resources.getString(R.string.preference_audio_noise_control);
		}*/

		if( MyDebug.LOG ) {
			Log.d(TAG, "toast_string: " + toast_string);
			Log.d(TAG, "simple?: " + simple);
		}
		if( !simple || always_show )
			Utils.showToast(switch_video_toast, toast_string);
	}

	private void freeAudioListener(boolean wait_until_done) {
		if( MyDebug.LOG )
			Log.d(TAG, "freeAudioListener");
		if( audio_listener != null ) {
			audio_listener.release(wait_until_done);
			audio_listener = null;
		}
	}
	
	private void initSpeechRecognizer() {
		if( MyDebug.LOG )
			Log.d(TAG, "initSpeechRecognizer");
		speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
		if( speechRecognizer != null ) {
			speechRecognizer.setRecognitionListener(new RecognitionListener() {
				@Override
				public void onBeginningOfSpeech() {
					if( MyDebug.LOG )
						Log.d(TAG, "RecognitionListener: onBeginningOfSpeech");
				}

				@Override
				public void onBufferReceived(byte[] buffer) {
					if( MyDebug.LOG )
						Log.d(TAG, "RecognitionListener: onBufferReceived");
				}

				@Override
				public void onEndOfSpeech() {
					if( MyDebug.LOG )
						Log.d(TAG, "RecognitionListener: onEndOfSpeech");
					restartSpeechRecognizer(false);
				}

				@Override
				public void onError(int error) {
					if( MyDebug.LOG )
						Log.d(TAG, "RecognitionListener: onError: " + error);
//						if( error != SpeechRecognizer.ERROR_NO_MATCH ) {
						// we sometime receive ERROR_NO_MATCH straight after listening starts
						// it seems that the end is signalled either by ERROR_SPEECH_TIMEOUT or onEndOfSpeech()
//						}
						// �������
						restartSpeechRecognizer(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
				}

				@Override
				public void onEvent(int eventType, Bundle params) {
					if( MyDebug.LOG )
						Log.d(TAG, "RecognitionListener: onEvent");
				}

				@Override
				public void onPartialResults(Bundle partialResults) {
					if( MyDebug.LOG )
						Log.d(TAG, "RecognitionListener: onPartialResults");
				}

				@Override
				public void onReadyForSpeech(Bundle params) {
					if( MyDebug.LOG )
						Log.d(TAG, "RecognitionListener: onReadyForSpeech");
					if( speech_recognizer_handler != null && speech_recognizer_runnable != null ) {
						speech_recognizer_handler.removeCallbacks(speech_recognizer_runnable);

						speech_recognizer_handler = null;
						speech_recognizer_runnable = null;

						Utils.showToast(audio_control_toast, R.string.speech_recognizer_started);
					}
				}

				public void onResults(Bundle results) {
					if( MyDebug.LOG )
						Log.d(TAG, "RecognitionListener: onResults");
					ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
					boolean found = false;
					final String trigger = "cheese";
					//String debug_toast = "";
					for(int i=0;list != null && i<list.size();i++) {
						String text = list.get(i);
						if( MyDebug.LOG ) {
							float [] scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
							if( scores != null )
								Log.d(TAG, "text: " + text + " score: " + scores[i]);
						}
						/*if( i > 0 )
							debug_toast += "\n";
						debug_toast += text + " : " + results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)[i];*/
						if( text.toLowerCase(Locale.US).contains(trigger) ) {
							found = true;
						}
					}
					//Utils.showToast(null, debug_toast); // debug only!
					if( found ) {
						if( MyDebug.LOG )
							Log.d(TAG, "audio trigger from speech recognition");
						audioTrigger();
					}
					else if( list != null && list.size() > 0 ) {
						String toast = list.get(0) + "?";
						if( MyDebug.LOG )
							Log.d(TAG, "unrecognised: " + toast);
						Utils.showToast(audio_control_toast, toast);
					}
				}

				@Override
				public void onRmsChanged(float rmsdB) {
				}
			});
		}
	}

	private void startSpeechRecognizer() {
		if( MyDebug.LOG )
			Log.d(TAG, "startSpeechRecognizer");
		if( speechRecognizer != null ) {
			Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
			intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en_US"); // since we listen for "cheese", ensure this works even for devices with different language settings
			intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000);
			speechRecognizer.startListening(intent);
		}
	}
	
	private void restartSpeechRecognizer(final boolean full) {
		if( MyDebug.LOG )
			Log.d(TAG, "restartSpeechRecognizer");
		if (full) {
			freeSpeechRecognizer();
			Handler handler = new Handler();
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					initSpeechRecognizer();
					startSpeechRecognizer();
				}
			}, 500);
		} else {
			Handler handler = new Handler();
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					startSpeechRecognizer();
				}
			}, 200);
		}
	}
	
	private void freeSpeechRecognizer() {
		if( MyDebug.LOG )
			Log.d(TAG, "freeSpeechRecognizer");
		if( speechRecognizer != null ) {
			try {
				speechRecognizer.cancel();
				speechRecognizer.destroy();
				speechRecognizer = null;
			} catch (IllegalArgumentException e) {}
		}
	}

	private void speechRecognizerFailed() {
		speechRecognizer = null;

		audio_control = false;

		Prefs.setBoolean(Prefs.AUDIO_CONTROL, false);

		mainUI.setAudioControl(false);

		Utils.showToast(audio_control_toast, R.string.speech_recognizer_unavailable);
	}
	
	void startAudioListeners() {
		String audio_control = Prefs.getString(Prefs.AUDIO_CONTROL_TYPE, "noise");
		if( audio_control.equals("voice") ) {
			initSpeechRecognizer();
			if( speechRecognizer == null ) {
				speechRecognizerFailed();
			} else {
				startSpeechRecognizer();

			speech_recognizer_runnable = new Runnable() {
				@Override
				public void run() {
					speechRecognizerFailed();
					speech_recognizer_runnable = null;
					speech_recognizer_handler = null;
				}
			};
			speech_recognizer_handler = new Handler();
			speech_recognizer_handler.postDelayed(speech_recognizer_runnable, 2000);
			}
		} else {
			if( audio_listener == null ) {
				audio_listener = new AudioListener(this);
				if( audio_listener.status() ) {
					String sensitivity_pref = Prefs.getString(Prefs.AUDIO_NOISE_CONTROL_SENSITIVITY, "0");
					switch(sensitivity_pref) {
						case "3":
							audio_noise_sensitivity = 50;
							break;
						case "2":
							audio_noise_sensitivity = 75;
							break;
						case "1":
							audio_noise_sensitivity = 125;
							break;
						case "-1":
							audio_noise_sensitivity = 150;
							break;
						case "-2":
							audio_noise_sensitivity = 200;
							break;
						default:
							// default
							audio_noise_sensitivity = 100;
							break;
					}
					audio_listener.start();
					Utils.showToast(audio_control_toast, R.string.audio_listener_started);
				}
				else {
					audio_listener.release(true); // shouldn't be needed, but just to be safe
					audio_listener = null;
					Utils.showToast(null, R.string.audio_listener_failed);
				}
			}
		}
	}
	
	public void stopAudioListeners(boolean wait_until_done) {
		freeAudioListener(wait_until_done);
		if( speech_recognizer_handler != null && speech_recognizer_runnable != null ) {
			speech_recognizer_handler.removeCallbacks(speech_recognizer_runnable);

			speech_recognizer_handler = null;
			speech_recognizer_runnable = null;
		}
		if( speechRecognizer != null ) {
			// no need to free the speech recognizer, just stop it
			speechRecognizer.stopListening();
			freeSpeechRecognizer();
		}
	}
	
	private void initLocation() {
		if( MyDebug.LOG )
			Log.d(TAG, "initLocation");
		if( !applicationInterface.getLocationSupplier().setupLocationListener() ) {
			if( MyDebug.LOG )
				Log.d(TAG, "location permission not available, so request permission");
			requestLocationPermission();
		}
	}

	// Android 6+ permission handling:
	
	final private int MY_PERMISSIONS_REQUEST_CAMERA = 0;
	final private int MY_PERMISSIONS_REQUEST_STORAGE = 1;
	final private int MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 2;
	final private int MY_PERMISSIONS_REQUEST_LOCATION = 3;

	/** Show a "rationale" to the user for needing a particular permission, then request that permission again
	 *  once they close the dialog.
	 */
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	private void showRequestPermissionRationale(final int permission_code) {
		if( MyDebug.LOG )
			Log.d(TAG, "showRequestPermissionRational: " + permission_code);
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.M ) {
			if( MyDebug.LOG )
				Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!");
			return;
		}

		boolean ok = true;
		String [] permissions = null;
		int message_id = 0;
		if( permission_code == MY_PERMISSIONS_REQUEST_CAMERA ) {
			if( MyDebug.LOG )
				Log.d(TAG, "display rationale for camera permission");
			permissions = new String[]{Manifest.permission.CAMERA};
			message_id = R.string.permission_rationale_camera;
		}
		else if( permission_code == MY_PERMISSIONS_REQUEST_STORAGE ) {
			if( MyDebug.LOG )
				Log.d(TAG, "display rationale for storage permission");
			permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE};
			message_id = R.string.permission_rationale_storage;
		}
		else if( permission_code == MY_PERMISSIONS_REQUEST_RECORD_AUDIO ) {
			if( MyDebug.LOG )
				Log.d(TAG, "display rationale for record audio permission");
			permissions = new String[]{Manifest.permission.RECORD_AUDIO};
			message_id = R.string.permission_rationale_record_audio;
		}
		else if( permission_code == MY_PERMISSIONS_REQUEST_LOCATION ) {
			if( MyDebug.LOG )
				Log.d(TAG, "display rationale for location permission");
			permissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
			message_id = R.string.permission_rationale_location;
		}
		else {
			if( MyDebug.LOG )
				Log.e(TAG, "showRequestPermissionRational unknown permission_code: " + permission_code);
			ok = false;
		}

		if( ok ) {
			final String [] permissions_f = permissions;
			new AlertDialog.Builder(this)
			.setTitle(R.string.permission_rationale_title)
			.setMessage(message_id)
			.setIcon(android.R.drawable.ic_dialog_alert)
			.setPositiveButton(android.R.string.ok, null)
			.setOnDismissListener(new OnDismissListener() {
				public void onDismiss(DialogInterface dialog) {
					if( MyDebug.LOG )
						Log.d(TAG, "requesting permission...");
					ActivityCompat.requestPermissions(MainActivity.this, permissions_f, permission_code);
				}
			}).show();
		}
	}

	void requestCameraPermission() {
		if( MyDebug.LOG )
			Log.d(TAG, "requestCameraPermission");
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.M ) {
			if( MyDebug.LOG )
				Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!");
			return;
		}

		if( ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) ) {
			// Show an explanation to the user *asynchronously* -- don't block
			// this thread waiting for the user's response! After the user
			// sees the explanation, try again to request the permission.
			showRequestPermissionRationale(MY_PERMISSIONS_REQUEST_CAMERA);
		}
		else {
			// Can go ahead and request the permission
			if( MyDebug.LOG )
				Log.d(TAG, "requesting camera permission...");
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, MY_PERMISSIONS_REQUEST_CAMERA);
		}
	}

	void requestStoragePermission() {
		if( MyDebug.LOG )
			Log.d(TAG, "requestStoragePermission");
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.M ) {
			if( MyDebug.LOG )
				Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!");
			return;
		}

		if( ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ) {
			// Show an explanation to the user *asynchronously* -- don't block
			// this thread waiting for the user's response! After the user
			// sees the explanation, try again to request the permission.
			showRequestPermissionRationale(MY_PERMISSIONS_REQUEST_STORAGE);
		}
		else {
			// Can go ahead and request the permission
			if( MyDebug.LOG )
				Log.d(TAG, "requesting storage permission...");
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, MY_PERMISSIONS_REQUEST_STORAGE);
		}
	}

	void requestRecordAudioPermission() {
		if( MyDebug.LOG )
			Log.d(TAG, "requestRecordAudioPermission");
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.M ) {
			if( MyDebug.LOG )
				Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!");
			return;
		}

		if( ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO) ) {
			// Show an explanation to the user *asynchronously* -- don't block
			// this thread waiting for the user's response! After the user
			// sees the explanation, try again to request the permission.
			showRequestPermissionRationale(MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
		}
		else {
			// Can go ahead and request the permission
			if( MyDebug.LOG )
				Log.d(TAG, "requesting record audio permission...");
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
		}
	}

	private void requestLocationPermission() {
		if( MyDebug.LOG )
			Log.d(TAG, "requestLocationPermission");
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.M ) {
			if( MyDebug.LOG )
				Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!");
			return;
		}

		if( ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION) ||
				ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION) ) {
			// Show an explanation to the user *asynchronously* -- don't block
			// this thread waiting for the user's response! After the user
			// sees the explanation, try again to request the permission.
			showRequestPermissionRationale(MY_PERMISSIONS_REQUEST_LOCATION);
		}
		else {
			// Can go ahead and request the permission
			if( MyDebug.LOG )
				Log.d(TAG, "requesting loacation permissions...");
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, MY_PERMISSIONS_REQUEST_LOCATION);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		if( MyDebug.LOG )
			Log.d(TAG, "onRequestPermissionsResult: requestCode " + requestCode);
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.M ) {
			if( MyDebug.LOG )
				Log.e(TAG, "shouldn't be requesting permissions for pre-Android M!");
			return;
		}

		switch( requestCode ) {
			case MY_PERMISSIONS_REQUEST_CAMERA:
			{
				// If request is cancelled, the result arrays are empty.
				if( grantResults.length > 0
					&& grantResults[0] == PackageManager.PERMISSION_GRANTED ) {
					// permission was granted, yay! Do the
					// contacts-related task you need to do.
					if( MyDebug.LOG )
						Log.d(TAG, "camera permission granted");
					preview.retryOpenCamera();
				}
				else {
					if( MyDebug.LOG )
						Log.d(TAG, "camera permission denied");
					// permission denied, boo! Disable the
					// functionality that depends on this permission.
					// Open Camera doesn't need to do anything: the camera will remain closed
				}
				return;
			}
			case MY_PERMISSIONS_REQUEST_STORAGE:
			{
				// If request is cancelled, the result arrays are empty.
				if( grantResults.length > 0
					&& grantResults[0] == PackageManager.PERMISSION_GRANTED ) {
					// permission was granted, yay! Do the
					// contacts-related task you need to do.
					if( MyDebug.LOG )
						Log.d(TAG, "storage permission granted");
					preview.retryOpenCamera();
				}
				else {
					if( MyDebug.LOG )
						Log.d(TAG, "storage permission denied");
					// permission denied, boo! Disable the
					// functionality that depends on this permission.
					// Open Camera doesn't need to do anything: the camera will remain closed
				}
				return;
			}
			case MY_PERMISSIONS_REQUEST_RECORD_AUDIO:
			{
				// If request is cancelled, the result arrays are empty.
				if( grantResults.length > 0
					&& grantResults[0] == PackageManager.PERMISSION_GRANTED ) {
					// permission was granted, yay! Do the
					// contacts-related task you need to do.
					if( MyDebug.LOG )
						Log.d(TAG, "record audio permission granted");
					// no need to do anything
				}
				else {
					if( MyDebug.LOG )
						Log.d(TAG, "record audio permission denied");
					// permission denied, boo! Disable the
					// functionality that depends on this permission.
					// no need to do anything
					// note that we don't turn off record audio option, as user may then record video not realising audio won't be recorded - best to be explicit each time
				}
				return;
			}
			case MY_PERMISSIONS_REQUEST_LOCATION:
			{
				// If request is cancelled, the result arrays are empty.
				if( grantResults.length > 0
					&& grantResults[0] == PackageManager.PERMISSION_GRANTED ) {
					// permission was granted, yay! Do the
					// contacts-related task you need to do.
					if( MyDebug.LOG )
						Log.d(TAG, "location permission granted");
					initLocation();
				}
				else {
					if( MyDebug.LOG )
						Log.d(TAG, "location permission denied");
					// permission denied, boo! Disable the
					// functionality that depends on this permission.
					// for location, seems best to turn the option back off
					if( MyDebug.LOG )
						Log.d(TAG, "location permission not available, so switch location off");
					Utils.showToast(null, R.string.permission_location_not_available);
					Prefs.setBoolean(Prefs.LOCATION, false);
				}
				return;
			}
			default:
			{
				if( MyDebug.LOG )
					Log.e(TAG, "unknown requestCode " + requestCode);
			}
		}
	}

	// for testing:
	public SaveLocationHistory getSaveLocationHistory() {
		return this.save_location_history;
	}
	
	public SaveLocationHistory getSaveLocationHistorySAF() {
		return this.save_location_history_saf;
	}
	
	public void usedFolderPicker() {
		if( applicationInterface.getStorageUtils().isUsingSAF() ) {
			save_location_history_saf.updateFolderHistory(getStorageUtils().getSaveLocationSAF(), true);
		}
		else {
			save_location_history.updateFolderHistory(getStorageUtils().getSaveLocation(), true);
		}
	}
	
	public boolean hasThumbnailAnimation() {
		return this.applicationInterface.hasThumbnailAnimation();
	}
	
	public RenderScript getRenderScript() {
		if( rs == null ) {
			// initialise renderscript
			this.rs = RenderScript.create(this);
			if( MyDebug.LOG )
				Log.d(TAG, "create renderscript object");
		}
		return rs;
	}

	public void forceCloseSettings() {
		MyPreferenceFragment fragment = getPreferenceFragment();
		if (fragment != null) {
		    int backStackEntry = getFragmentManager().getBackStackEntryCount();
		    if (backStackEntry > 0) {
		        for (int i = 0; i < backStackEntry; i++) {
		            getFragmentManager().popBackStackImmediate();
		        }
		    }
			getFragmentManager().beginTransaction().remove(fragment).commitAllowingStateLoss();
		}
	}

	public void restartActivity() {
		forceCloseSettings();
		recreate();
	}
}
