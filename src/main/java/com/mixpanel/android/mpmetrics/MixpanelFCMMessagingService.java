package com.mixpanel.android.mpmetrics;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.mixpanel.android.util.MPLog;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Service for handling Firebase Cloud Messaging callbacks.
 *
 * <p>You can use FirebaseMessagingService to report Firebase Cloud Messaging registration identifiers
 * to Mixpanel, and to display incoming notifications from Mixpanel to
 * the device status bar. This is the simplest way to get up and running with notifications from Mixpanel.
 *
 * <p>To enable FCM in your application, place your google-services.json file in your Android project
 * root directory, add firebase messaging as a dependency in your gradle file:
 *
 * <pre>
 * {@code
 * buildscript {
 *      ...
 *      dependencies {
 *          classpath 'com.google.gms:google-services:4.1.0'
 *          ...
 *      }
 * }
 *
 * dependencies {
 *     implementation 'com.google.firebase:firebase-messaging:17.3.4'
 *     ...
 * }
 *
 * apply plugin: 'com.google.gms.google-services'
 * }
 * </pre>
 *
 * And finally add a clause like the following
 * to the &lt;application&gt; tag of your AndroidManifest.xml.
 *
 *<pre>
 *{@code
 *
 * <service
 *  android:name="com.mixpanel.android.mpmetrics.MixpanelFCMMessagingService"
 *  android:enabled="true"
 *  android:exported="false">
 *      <intent-filter>
 *          <action android:name="com.google.firebase.MESSAGING_EVENT"/>
 *      </intent-filter>
 * </service>
 *}
 *</pre>
 *
 * <p>Once the FirebaseMessagingService is configured, the only thing you have to do to
 * get set up Mixpanel messages is call {@link MixpanelAPI.People#identify(String) }
 * with a distinct id for your user.
 *
 * <pre>
 * {@code
 *
 * MixpanelAPI.People people = mMixpanelAPI.getPeople();
 * people.identify("A USER DISTINCT ID");
 *
 * }
 * </pre>
 *
 * @see MixpanelAPI#getPeople()
 * @see <a href="https://mixpanel.com/docs/people-analytics/android-push">Getting Started with Android Push Notifications</a>
 */
public class MixpanelFCMMessagingService extends FirebaseMessagingService {
    private static final String LOGTAG = "MixpanelAPI.MixpanelFCMMessagingService";

    protected static class NotificationData {
        private NotificationData(int anIcon, int aLargeIcon, int aWhiteIcon, CharSequence aTitle, String aMessage, Intent anIntent, int aColor) {
            icon = anIcon;
            largeIcon = aLargeIcon;
            whiteIcon = aWhiteIcon;
            title = aTitle;
            message = aMessage;
            intent = anIntent;
            color = aColor;
        }

        public final int icon;
        public final int largeIcon;
        public final int whiteIcon;
        public final CharSequence title;
        public final String message;
        public final Intent intent;
        public final int color;

        public static final int NOT_SET = -1;
    }

    /* package */ static void init() {
        FirebaseInstanceId.getInstance().getInstanceId()
                .addOnCompleteListener(new OnCompleteListener<InstanceIdResult>() {
                    @Override
                    public void onComplete(Task<InstanceIdResult> task) {
                        if (task.isSuccessful()) {
                            String registrationId = task.getResult().getToken();
                            addToken(registrationId);
                        }
                    }
                });
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        MPLog.d(LOGTAG, "MP FCM on new message received");
        onMessageReceived(getApplicationContext(), remoteMessage.toIntent());
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        MPLog.d(LOGTAG, "MP FCM on new push token: " + token);
        addToken(token);
    }

    /**
     * Util method to let subclasses customize the payload through the push notification intent.
     *
     * @param context The application context
     * @param intent Push payload intent. Could be modified before calling super() from a sub-class.
     *
     */
    protected void onMessageReceived(Context context, Intent intent) {
        showPushNotification(context, intent);
    }

    /**
     * Only use this method if you have implemented your own custom FirebaseMessagingService. This
     * is useful when you use multiple push providers.
     * Displays a Mixpanel push notification on the device.
     *
     * @param context The application context you are tracking
     * @param messageIntent Intent that bundles the data used to build a notification. If the intent
     *                      is not valid, the notification will not be shown.
     *                      See {@link #showPushNotification(Context, Intent)}
     */
    public static void showPushNotification(Context context, Intent messageIntent) {
        final MPConfig config = MPConfig.getInstance(context);
        String resourcePackage = config.getResourcePackageName();
        if (null == resourcePackage) {
            resourcePackage = context.getPackageName();
        }

        final ResourceIds drawableIds = new ResourceReader.Drawables(resourcePackage, context);
        final Context applicationContext = context.getApplicationContext();
        final Notification notification = buildNotification(applicationContext, messageIntent, drawableIds);

        if (null != notification) {
            final NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(0, notification);
        }
    }

    /**
     * Only use this method if you have implemented your own custom FirebaseMessagingService. This
     * is useful when you use multiple push providers.
     * This method should be called from a onNewToken callback. It adds a new FCM token to a Mixpanel
     * people profile.
     *
     * @param token Firebase Cloud Messaging token to be added to the people profile.
     */
    public static void addToken(final String token) {
        MixpanelAPI.allInstances(new MixpanelAPI.InstanceProcessor() {
            @Override
            public void process(MixpanelAPI api) {
                api.getPeople().setPushRegistrationId(token);
            }
        });
    }

    private static Notification buildNotification(Context context, Intent inboundIntent, ResourceIds iconIds) {
        final PackageManager manager = context.getPackageManager();
        Intent defaultIntent =  manager.getLaunchIntentForPackage(context.getPackageName());

        final MixpanelFCMMessagingService.NotificationData notificationData = readInboundIntent(context, inboundIntent, iconIds, defaultIntent);
        if (null == notificationData) {
            return null;
        }

        MPLog.d(LOGTAG, "MP FCM notification received: " + notificationData.message);
        final PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                0,
                notificationData.intent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        final Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = makeNotificationSDK26OrHigher(context, contentIntent, notificationData);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            notification = makeNotificationSDK21OrHigher(context, contentIntent, notificationData);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            notification = makeNotificationSDK16OrHigher(context, contentIntent, notificationData);
        } else {
            notification = makeNotificationSDK11OrHigher(context, contentIntent, notificationData);
        }

        return notification;
    }

    /**
     * Mixpanel Notification Builder
     */
    @SuppressLint("NewApi")
    @TargetApi(26)
    protected static Notification makeNotificationSDK26OrHigher(Context context, PendingIntent intent, MixpanelFCMMessagingService.NotificationData notificationData) {
        NotificationManager mNotificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        String channelId = MPConfig.getInstance(context).getNotificationChannelId();
        String channelName = MPConfig.getInstance(context).getNotificationChannelName();
        int importance = MPConfig.getInstance(context).getNotificationChannelImportance();

        NotificationChannel channel = new NotificationChannel(channelId, channelName, importance);
        int notificationDefaults = MPConfig.getInstance(context).getNotificationDefaults();
        if (notificationDefaults == Notification.DEFAULT_VIBRATE || notificationDefaults == Notification.DEFAULT_ALL) {
            channel.enableVibration(true);
        }
        if (notificationDefaults == Notification.DEFAULT_LIGHTS || notificationDefaults == Notification.DEFAULT_ALL) {
            channel.enableLights(true);
            channel.setLightColor(Color.WHITE);
        }
        mNotificationManager.createNotificationChannel(channel);

        final Notification.Builder builder = new Notification.Builder(context).
                setTicker(notificationData.message).
                setWhen(System.currentTimeMillis()).
                setShowWhen(true).
                setContentTitle(notificationData.title).
                setContentText(notificationData.message).
                setContentIntent(intent).
                setStyle(new Notification.BigTextStyle().bigText(notificationData.message)).
                setChannelId(channelId);

        if (notificationData.whiteIcon != MixpanelFCMMessagingService.NotificationData.NOT_SET) {
            builder.setSmallIcon(notificationData.whiteIcon);
        } else {
            builder.setSmallIcon(notificationData.icon);
        }

        if (notificationData.largeIcon != MixpanelFCMMessagingService.NotificationData.NOT_SET) {
            builder.setLargeIcon(BitmapFactory.decodeResource(context.getResources(), notificationData.largeIcon));
        }

        if (notificationData.color != MixpanelFCMMessagingService.NotificationData.NOT_SET) {
            builder.setColor(notificationData.color);
        }

        final Notification n = builder.build();
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        return n;
    }

    @SuppressWarnings("deprecation")
    @TargetApi(11)
    protected static Notification makeNotificationSDK11OrHigher(Context context, PendingIntent intent, MixpanelFCMMessagingService.NotificationData notificationData) {
        final Notification.Builder builder = new Notification.Builder(context).
                setSmallIcon(notificationData.icon).
                setTicker(notificationData.message).
                setWhen(System.currentTimeMillis()).
                setContentTitle(notificationData.title).
                setContentText(notificationData.message).
                setContentIntent(intent).
                setDefaults(MPConfig.getInstance(context).getNotificationDefaults());

        if (notificationData.largeIcon != MixpanelFCMMessagingService.NotificationData.NOT_SET) {
            builder.setLargeIcon(BitmapFactory.decodeResource(context.getResources(), notificationData.largeIcon));
        }

        final Notification n = builder.getNotification();
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        return n;
    }

    @SuppressLint("NewApi")
    @TargetApi(16)
    protected static Notification makeNotificationSDK16OrHigher(Context context, PendingIntent intent, MixpanelFCMMessagingService.NotificationData notificationData) {
        final Notification.Builder builder = new Notification.Builder(context).
                setSmallIcon(notificationData.icon).
                setTicker(notificationData.message).
                setWhen(System.currentTimeMillis()).
                setContentTitle(notificationData.title).
                setContentText(notificationData.message).
                setContentIntent(intent).
                setStyle(new Notification.BigTextStyle().bigText(notificationData.message)).
                setDefaults(MPConfig.getInstance(context).getNotificationDefaults());

        if (notificationData.largeIcon != MixpanelFCMMessagingService.NotificationData.NOT_SET) {
            builder.setLargeIcon(BitmapFactory.decodeResource(context.getResources(), notificationData.largeIcon));
        }

        final Notification n = builder.build();
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        return n;
    }

    @SuppressLint("NewApi")
    @TargetApi(21)
    protected static Notification makeNotificationSDK21OrHigher(Context context, PendingIntent intent, MixpanelFCMMessagingService.NotificationData notificationData) {
        final Notification.Builder builder = new Notification.Builder(context).
                setTicker(notificationData.message).
                setWhen(System.currentTimeMillis()).
                setContentTitle(notificationData.title).
                setContentText(notificationData.message).
                setContentIntent(intent).
                setStyle(new Notification.BigTextStyle().bigText(notificationData.message)).
                setDefaults(MPConfig.getInstance(context).getNotificationDefaults());

        if (notificationData.whiteIcon != MixpanelFCMMessagingService.NotificationData.NOT_SET) {
            builder.setSmallIcon(notificationData.whiteIcon);
        } else {
            builder.setSmallIcon(notificationData.icon);
        }

        if (notificationData.largeIcon != MixpanelFCMMessagingService.NotificationData.NOT_SET) {
            builder.setLargeIcon(BitmapFactory.decodeResource(context.getResources(), notificationData.largeIcon));
        }

        if (notificationData.color != MixpanelFCMMessagingService.NotificationData.NOT_SET) {
            builder.setColor(notificationData.color);
        }

        final Notification n = builder.build();
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        return n;
    }

    /* package */ static NotificationData readInboundIntent(Context context, Intent inboundIntent, ResourceIds iconIds, Intent defaultIntent) {
        final PackageManager manager = context.getPackageManager();

        final String message = inboundIntent.getStringExtra("mp_message");
        final String iconName = inboundIntent.getStringExtra("mp_icnm");
        final String largeIconName = inboundIntent.getStringExtra("mp_icnm_l");
        final String whiteIconName = inboundIntent.getStringExtra("mp_icnm_w");
        final String uriString = inboundIntent.getStringExtra("mp_cta");
        CharSequence notificationTitle = inboundIntent.getStringExtra("mp_title");
        final String colorName = inboundIntent.getStringExtra("mp_color");
        final String campaignId = inboundIntent.getStringExtra("mp_campaign_id");
        final String messageId = inboundIntent.getStringExtra("mp_message_id");
        final String extraLogData = inboundIntent.getStringExtra("mp");
        int color = MixpanelFCMMessagingService.NotificationData.NOT_SET;

        trackCampaignReceived(campaignId, messageId, extraLogData);

        if (colorName != null) {
            try {
                color = Color.parseColor(colorName);
            } catch (IllegalArgumentException e) {}
        }

        if (message == null) {
            return null;
        }

        int notificationIcon = -1;
        if (null != iconName) {
            if (iconIds.knownIdName(iconName)) {
                notificationIcon = iconIds.idFromName(iconName);
            }
        }

        int largeNotificationIcon = MixpanelFCMMessagingService.NotificationData.NOT_SET;
        if (null != largeIconName) {
            if (iconIds.knownIdName(largeIconName)) {
                largeNotificationIcon = iconIds.idFromName(largeIconName);
            }
        }

        int whiteNotificationIcon = MixpanelFCMMessagingService.NotificationData.NOT_SET;
        if (null != whiteIconName) {
            if (iconIds.knownIdName(whiteIconName)) {
                whiteNotificationIcon = iconIds.idFromName(whiteIconName);
            }
        }

        ApplicationInfo appInfo;
        try {
            appInfo = manager.getApplicationInfo(context.getPackageName(), 0);
        } catch (final PackageManager.NameNotFoundException e) {
            appInfo = null;
        }

        if (notificationIcon == MixpanelFCMMessagingService.NotificationData.NOT_SET && null != appInfo) {
            notificationIcon = appInfo.icon;
        }

        if (notificationIcon == MixpanelFCMMessagingService.NotificationData.NOT_SET) {
            notificationIcon = android.R.drawable.sym_def_app_icon;
        }

        if (null == notificationTitle && null != appInfo) {
            notificationTitle = manager.getApplicationLabel(appInfo);
        }

        if (null == notificationTitle) {
            notificationTitle = "A message for you";
        }

        Uri uri = null;
        if (null != uriString) {
            uri = Uri.parse(uriString);
        }
        final Intent intent;
        if (null == uri) {
            intent = defaultIntent;
        } else {
            intent = new Intent(Intent.ACTION_VIEW, uri);
        }

        final Intent notificationIntent = buildNotificationIntent(intent, campaignId, messageId, extraLogData);

        return new MixpanelFCMMessagingService.NotificationData(notificationIcon, largeNotificationIcon, whiteNotificationIcon, notificationTitle, message, notificationIntent, color);
    }

    private static Intent buildNotificationIntent(Intent intent, String campaignId, String messageId, String extraLogData) {
        if (campaignId != null) {
            intent.putExtra("mp_campaign_id", campaignId);
        }

        if (messageId != null) {
            intent.putExtra("mp_message_id", messageId);
        }

        if (extraLogData != null) {
            intent.putExtra("mp", extraLogData);
        }

        return intent;
    }

    private static void trackCampaignReceived(final String campaignId, final String messageId, final String extraLogData) {
        if (campaignId != null && messageId != null) {
            MixpanelAPI.allInstances(new MixpanelAPI.InstanceProcessor() {
                @Override
                public void process(MixpanelAPI api) {
                    if(api.isAppInForeground()) {
                        JSONObject pushProps = new JSONObject();
                        try {
                            if (extraLogData != null) {
                                pushProps = new JSONObject(extraLogData);
                            }
                        } catch (JSONException e) {}

                        try {
                            pushProps.put("campaign_id", Integer.valueOf(campaignId).intValue());
                            pushProps.put("message_id", Integer.valueOf(messageId).intValue());
                            pushProps.put("message_type", "push");
                            api.track("$campaign_received", pushProps);
                        } catch (JSONException e) {}
                    }
                }
            });
        }
    }
}
