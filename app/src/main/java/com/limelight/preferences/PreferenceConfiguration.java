package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;

public class PreferenceConfiguration {
    static final String RES_FPS_PREF_STRING = "list_resolution_fps";
    private static final String DECODER_PREF_STRING = "list_decoders";
    static final String BITRATE_PREF_STRING = "seekbar_bitrate";
    private static final String STRETCH_PREF_STRING = "checkbox_stretch_video";
    private static final String SOPS_PREF_STRING = "checkbox_enable_sops";
    private static final String DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings";
    private static final String HOST_AUDIO_PREF_STRING = "checkbox_host_audio";
    private static final String DEADZONE_PREF_STRING = "seekbar_deadzone";
    private static final String LANGUAGE_PREF_STRING = "list_languages";
    private static final String LIST_MODE_PREF_STRING = "checkbox_list_mode";
    private static final String SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode";
    private static final String MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller";
    private static final String NO_LATENCY_TOAST_PREF_STRING = "checkbox_no_latency_toast";

    private static final int BITRATE_DEFAULT_720_30 = 5;
    private static final int BITRATE_DEFAULT_720_60 = 10;
    private static final int BITRATE_DEFAULT_1080_30 = 10;
    private static final int BITRATE_DEFAULT_1080_60 = 20;

    private static final String DEFAULT_RES_FPS = "720p60";
    private static final String DEFAULT_DECODER = "auto";
    private static final int DEFAULT_BITRATE = BITRATE_DEFAULT_720_60;
    private static final boolean DEFAULT_STRETCH = false;
    private static final boolean DEFAULT_SOPS = true;
    private static final boolean DEFAULT_DISABLE_TOASTS = false;
    private static final boolean DEFAULT_HOST_AUDIO = false;
    private static final int DEFAULT_DEADZONE = 15;
    public static final String DEFAULT_LANGUAGE = "default";
    private static final boolean DEFAULT_LIST_MODE = false;
    private static final boolean DEFAULT_MULTI_CONTROLLER = true;
    private static final boolean DEFAULT_NO_LATENCY_TOAST = false;

    public static final int FORCE_HARDWARE_DECODER = -1;
    public static final int AUTOSELECT_DECODER = 0;
    public static final int FORCE_SOFTWARE_DECODER = 1;

    public int width, height, fps;
    public int bitrate;
    public int decoder;
    public int deadzonePercentage;
    public boolean stretchVideo, enableSops, playHostAudio, disableWarnings;
    public String language;
    public boolean listMode, smallIconMode, multiController;
    public boolean suppressLatencyToast;

    public static int getDefaultBitrate(String resFpsString) {
        if (resFpsString.equals("720p30")) {
            return BITRATE_DEFAULT_720_30;
        }
        else if (resFpsString.equals("720p60")) {
            return BITRATE_DEFAULT_720_60;
        }
        else if (resFpsString.equals("1080p30")) {
            return BITRATE_DEFAULT_1080_30;
        }
        else if (resFpsString.equals("1080p60")) {
            return BITRATE_DEFAULT_1080_60;
        }
        else {
            // Should never get here
            return DEFAULT_BITRATE;
        }
    }

    public static boolean getDefaultSmallMode(Context context) {
        PackageManager manager = context.getPackageManager();
        if (manager != null) {
            // TVs shouldn't use small mode by default
            if (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                return false;
            }

            // API 21 uses LEANBACK instead of TELEVISION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    return false;
                }
            }
        }

        // Use small mode on anything smaller than a 7" tablet
        return context.getResources().getConfiguration().smallestScreenWidthDp < 600;
    }

    public static int getDefaultBitrate(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(RES_FPS_PREF_STRING, DEFAULT_RES_FPS);
        if (str.equals("720p30")) {
            return BITRATE_DEFAULT_720_30;
        }
        else if (str.equals("720p60")) {
            return BITRATE_DEFAULT_720_60;
        }
        else if (str.equals("1080p30")) {
            return BITRATE_DEFAULT_1080_30;
        }
        else if (str.equals("1080p60")) {
            return BITRATE_DEFAULT_1080_60;
        }
        else {
            // Should never get here
            return DEFAULT_BITRATE;
        }
    }

    private static int getDecoderValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(DECODER_PREF_STRING, DEFAULT_DECODER);
        if (str.equals("auto")) {
            return AUTOSELECT_DECODER;
        }
        else if (str.equals("software")) {
            return FORCE_SOFTWARE_DECODER;
        }
        else if (str.equals("hardware")) {
            return FORCE_HARDWARE_DECODER;
        }
        else {
            // Should never get here
            return AUTOSELECT_DECODER;
        }
    }

    public static PreferenceConfiguration readPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PreferenceConfiguration.Builder builder = new PreferenceConfiguration.Builder();

        builder.setBitrate(prefs.getInt(BITRATE_PREF_STRING, getDefaultBitrate(context)));
        String str = prefs.getString(RES_FPS_PREF_STRING, DEFAULT_RES_FPS);
        if (str.equals("720p30")) {
            builder.setResolution(1280, 720);
            builder.setFps(30);
        }
        else if (str.equals("720p60")) {
            builder.setResolution(1280, 720);
            builder.setFps(60);
        }
        else if (str.equals("1080p30")) {
            builder.setResolution(1920, 1080);
            builder.setFps(30);
        }
        else if (str.equals("1080p60")) {
            builder.setResolution(1920, 1080);
            builder.setFps(60);
        }

        // Standard streaming preferences
        builder.setDisableWarnings(prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS));
        builder.setEnableSops(prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS));
        builder.setStretchVideo(prefs.getBoolean(STRETCH_PREF_STRING, DEFAULT_STRETCH));
        builder.setPlayHostAudio(prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO));
        builder.setMultiController(prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER));
        builder.setSuppressLatencyToast(prefs.getBoolean(NO_LATENCY_TOAST_PREF_STRING, DEFAULT_NO_LATENCY_TOAST));
        builder.setDeadzonePercentage(prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE));

        // Internal preferences
        PreferenceConfiguration config = builder.build();
        config.decoder = getDecoderValue(context);
        config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE);
        config.smallIconMode = prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context));
        config.listMode = prefs.getBoolean(LIST_MODE_PREF_STRING, DEFAULT_LIST_MODE);

        return config;
    }

    public static class Builder {
        private PreferenceConfiguration config;

        public Builder() {
            this.config = new PreferenceConfiguration();

            // Set defaults
            config.width = 1280;
            config.height = 720;
            config.fps = 60;
            config.bitrate = DEFAULT_BITRATE;
            config.disableWarnings = DEFAULT_DISABLE_TOASTS;
            config.enableSops = DEFAULT_SOPS;
            config.stretchVideo = DEFAULT_STRETCH;
            config.playHostAudio = DEFAULT_HOST_AUDIO;
            config.listMode = DEFAULT_LIST_MODE;
            config.multiController = DEFAULT_MULTI_CONTROLLER;
            config.deadzonePercentage = DEFAULT_DEADZONE;
            config.suppressLatencyToast = DEFAULT_NO_LATENCY_TOAST;

            // Internal preferences
            config.decoder = AUTOSELECT_DECODER;
            config.language = DEFAULT_LANGUAGE;
            config.smallIconMode = false;
        }

        public Builder setResolution(int width, int height) {
            config.width = width;
            config.height = height;
            return this;
        }

        public Builder setFps(int fps) {
            config.fps = fps;
            return this;
        }

        public Builder setDisableWarnings(boolean disabled) {
            config.disableWarnings = disabled;
            return this;
        }

        public Builder setBitrate(int bitrate) {
            config.bitrate = bitrate;
            return this;
        }

        public Builder setEnableSops(boolean sops) {
            config.enableSops = sops;
            return this;
        }

        public Builder setStretchVideo(boolean stretch) {
            config.stretchVideo = stretch;
            return this;
        }

        public Builder setPlayHostAudio(boolean hostAudio) {
            config.playHostAudio = hostAudio;
            return this;
        }

        public Builder setMultiController(boolean enabled) {
            config.multiController = enabled;
            return this;
        }

        public Builder setDeadzonePercentage(int percentage) {
            config.deadzonePercentage = percentage;
            return this;
        }

        public Builder setSuppressLatencyToast(boolean suppress) {
            config.suppressLatencyToast = suppress;
            return this;
        }

        public PreferenceConfiguration build() {
            return config;
        }
    }
}
