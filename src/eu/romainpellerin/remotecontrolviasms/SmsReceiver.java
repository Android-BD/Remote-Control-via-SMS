package eu.romainpellerin.remotecontrolviasms;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;


/* Google Analytics */
import com.google.analytics.tracking.android.GAServiceManager;
import com.google.analytics.tracking.android.GoogleAnalytics;
import com.google.analytics.tracking.android.Tracker;

/* LOCATION */
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.GooglePlayServicesClient.ConnectionCallbacks;
import com.google.android.gms.common.GooglePlayServicesClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationClient;

public class SmsReceiver extends BroadcastReceiver implements ConnectionCallbacks, OnConnectionFailedListener {

	private Context cont;
	private String phoneNumber;
	private static MediaPlayer mp;
	private static AudioManager audioManager;
	private static int val_ini; // volume
	private LocationClient locationClient;
	
	@Override
	public void onReceive(Context context, Intent intent) {
		cont = context;
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		PreferenceManager.setDefaultValues(context, R.xml.prefs_wifi, true); // Met les settings par défaut si l'utilsateur n'a rien personnalisé
		PreferenceManager.setDefaultValues(context, R.xml.prefs_data, true);
		PreferenceManager.setDefaultValues(context, R.xml.prefs_beep, true);
		PreferenceManager.setDefaultValues(context, R.xml.prefs_gps, true);
        Bundle extras = intent.getExtras();
         
        if (extras != null) {
        	GoogleAnalytics mGaInstance = GoogleAnalytics.getInstance(context);
        	Tracker mGaTracker = mGaInstance.getTracker("UA-39228475-2");
            Object[] smsExtra = (Object[]) extras.get("pdus");
            SmsMessage sms = SmsMessage.createFromPdu((byte[])smsExtra[0]);
            phoneNumber = sms.getOriginatingAddress();
            String body = sms.getMessageBody().toString();
            
			if (body.equalsIgnoreCase(prefs.getString("wifi_sms", "wifi")) && prefs.getBoolean("wifi_enable", true)) { // WIFI
				mGaTracker.sendEvent("received_sms", "received_sms", "WIFI", null);
				
				WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE); 
				wifi.setWifiEnabled(true);
            }
			if (body.equalsIgnoreCase(prefs.getString("data_sms", "data")) && prefs.getBoolean("data_enable", true)) { // DATA
            	mGaTracker.sendEvent("received_sms", "received_sms", "DATA", null);
            	
				final ConnectivityManager conman = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
				try {
					final Class<?> conmanClass = Class.forName(conman.getClass().getName());
					final Field iConnectivityManagerField = conmanClass.getDeclaredField("mService");
					iConnectivityManagerField.setAccessible(true);
					final Object iConnectivityManager = iConnectivityManagerField.get(conman);
					final Class<?> iConnectivityManagerClass = Class.forName(iConnectivityManager.getClass().getName());
					final Method setMobileDataEnabledMethod = iConnectivityManagerClass.getDeclaredMethod("setMobileDataEnabled", Boolean.TYPE);
					setMobileDataEnabledMethod.setAccessible(true);
					setMobileDataEnabledMethod.invoke(iConnectivityManager, true);
				} catch (Exception e) { // many
					e.printStackTrace();
				}
            }
			if (body.equalsIgnoreCase(prefs.getString("beep_sms", "beep")) && prefs.getBoolean("beep_enable", true)) { // BEEP
				mGaTracker.sendEvent("received_sms", "received_sms", "BEEP", null);
				
				if (mp==null) {
					// Volume
					audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
					val_ini = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
					int volumeToSet = ((prefs.getInt("volume", 100) * audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))/100);
					audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeToSet, AudioManager.FLAG_VIBRATE);
					
					final boolean loopmode = prefs.getBoolean("beep_play_again", false);
					
					mp = MediaPlayer.create(context, R.raw.alarm);
	                mp.setOnCompletionListener(new OnCompletionListener() {
	                    @Override
	                    public void onCompletion(MediaPlayer mp) {
	                    	SmsReceiver.mp.reset();
	                    	SmsReceiver.mp.release();
	                    	SmsReceiver.mp = null;
	                        // Remet le volume tel qu'il était avant
	                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, val_ini, AudioManager.FLAG_VIBRATE);
	                    }
	                });
	                
	                mp.setLooping(loopmode);
	                mp.start();
	                Intent inte = new Intent(context, CancelAlarm.class);
	                inte.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
	                if (loopmode) context.startActivity(inte);
				}
            }
			if (body.equalsIgnoreCase(prefs.getString("gps_sms", "wifi")) && prefs.getBoolean("gps_enable", false) && GooglePlayServicesUtil.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) { // GPS
				
				mGaTracker.sendEvent("received_sms", "received_sms", "GPS", null);
				
				locationClient = new LocationClient(context, this, this);
				locationClient.connect();
            }
			GAServiceManager.getInstance().dispatch(); // commit les données vers GA
        }
	}
	
	public static void stopMP() {
		mp.stop();
		mp.reset();
		mp.release();
        mp = null;
		// Remet le volume tel qu'il était avant
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, val_ini, AudioManager.FLAG_VIBRATE);
	}

	@Override
	public void onConnectionFailed(ConnectionResult arg0) {
		SmsManager manager = SmsManager.getDefault();
		manager.sendTextMessage(phoneNumber, null, "Connection failed", null, null);
	}

	@Override
	public void onConnected(Bundle arg0) {
		Thread a = new Thread() {
			@Override
			public void run() {
				Location loc = null;
				int counter = 0;
				do {
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {}
					loc = locationClient.getLastLocation();
					counter++;
					if (counter>120) return;
				}while(loc==null);
				
				String url = cont.getResources().getString(R.string.location_bug);
				if (loc != null) {
					String lat = String.valueOf(loc.getLatitude());
					String lon = String.valueOf(loc.getLongitude());
					url = "http://maps.google.com/maps?q="+lat+","+lon;
				}
					
				String adress = cont.getResources().getString(R.string.location_no_acuracy);
				Geocoder geocoder = new Geocoder(cont);
				try {
					List<Address> result = geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
					adress = addressToText(result.get(0)).toString();
				} catch (IOException e) {
				}
				SmsManager manager = SmsManager.getDefault();
				String msg = adress+" (precision="+loc.getAccuracy()+"m)";
				manager.sendTextMessage(phoneNumber, null, msg, null, null);
				manager.sendTextMessage(phoneNumber, null, url, null, null);
				if (locationClient.isConnected()) locationClient.disconnect();
			}
		};
		a.start();
	}

	@Override
	public void onDisconnected() {}
	
	private StringBuffer addressToText(Address address) {
		final StringBuffer addressText = new StringBuffer();
		for (int i = 0, max = address.getMaxAddressLineIndex(); i < max; ++i) {
			addressText.append(address.getAddressLine(i));
			if ((i+1) < max) {
				addressText.append(", ");
			}
		}
		addressText.append(", ");
		addressText.append(address.getCountryName());
		return addressText;
	}
}