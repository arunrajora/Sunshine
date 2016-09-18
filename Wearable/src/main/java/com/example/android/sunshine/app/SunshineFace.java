/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.CalendarContract;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SERIF, Typeface.ITALIC);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineFace.Engine> mWeakReference;

        public EngineHandler(SunshineFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,GoogleApiClient.OnConnectionFailedListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        Paint mAmbTimePaint;
        Paint mAmbMaxTempPaint;
        int dateOffset;
        Bitmap mAmbImage;
        Rect bounds;
        boolean mAmbient;
        int mBackgroundColor;
        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                mDateFormat=new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
                mDateFormat.setCalendar(mCalendar);
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mTemp1offset;
        float mTempOffset;
        Bitmap mWeatherImage;
        int mMinTemp;
        int mMaxTemp;
        float mImageOffsetX;
        float mImageOffsetY;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        GoogleApiClient mGoogleApiClient;
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineFace.this.getResources();
            dateOffset =(int) resources.getDimension(R.dimen.digital_date_offset);
            mTempOffset =resources.getDimension(R.dimen.digital_temp_offset);
            mTemp1offset=resources.getDimension(R.dimen.digital_temp1_offset);
            bounds=new Rect();
            mBackgroundColor=Color.WHITE;
            mCalendar=Calendar.getInstance();
            mDateFormat=new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
            mDate=new Date();
            mAmbImage=null;
            mAmbTimePaint=new Paint();
            mAmbMaxTempPaint=new Paint();
            mTimePaint= new Paint();
            mDatePaint= new Paint();
            mMaxTempPaint= new Paint();
            mMinTempPaint=new Paint();
            mAmbTimePaint=createTextPaint(Color.WHITE);
            mAmbMaxTempPaint=createTextPaint(Color.WHITE);
            mTimePaint = createTextPaint(Color.BLACK);
            mDatePaint = createTextPaint(Color.BLACK);
            mMaxTempPaint = createTextPaint(Color.BLACK);
            mMinTempPaint = createTextPaint(Color.BLACK);
            mMaxTemp=mMinTemp=10000000;
            mGoogleApiClient=new GoogleApiClient.Builder(SunshineFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API).build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();
                mCalendar.setTimeZone(TimeZone.getDefault());
                mDateFormat=new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
                mDateFormat.setCalendar(mCalendar);

            } else {
                if(mGoogleApiClient!=null && mGoogleApiClient.isConnected()==false){
                    Wearable.DataApi.removeListener(mGoogleApiClient,this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            // Load resources that have alternate values for round watches.
            Resources resources = SunshineFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mYOffset = resources.getDimension(isRound? R.dimen.digital_y_offset_round: R.dimen.digital_y_offset);
            mImageOffsetY = isRound? resources.getDimension(R.dimen.digital_image_offset ):0;
            mImageOffsetX = isRound? resources.getDimension(R.dimen.digital_image_offsetX ):0;
            float timeSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateSize=timeSize/3;
            float tempSize=timeSize*4/5;
            mDatePaint.setTextSize(dateSize);
            mTimePaint.setTextSize(timeSize);
            mMaxTempPaint.setTextSize(tempSize);
            mMinTempPaint.setTextSize(tempSize-20);
            mAmbTimePaint.setTextSize(timeSize);
            mAmbMaxTempPaint.setTextSize(tempSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mAmbTimePaint.setAntiAlias(!inAmbientMode);
                    mAmbMaxTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawColor(mBackgroundColor);
            }
            int midX=bounds.width()/2;
            int midY=bounds.height()/2;

            mCalendar.setTimeInMillis(System.currentTimeMillis());
            String timeStr= mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE)
                    ,mCalendar.get(Calendar.SECOND));
            canvas.drawText(timeStr,midX, mYOffset, mAmbient?mAmbTimePaint:mTimePaint);
            if(!mAmbient)
            {
                mDate.setTime(System.currentTimeMillis());
                String dateStr=mDateFormat.format(mDate);
                canvas.drawText(dateStr,midX,mYOffset+dateOffset,mDatePaint);
            }
            if(mMaxTemp!=10000000 && mMinTemp!=10000000)
            {
                canvas.drawText(String.format("%d\u00B0",mMaxTemp)+"",mAmbient?(midX+10):(midX*3/2), mYOffset+mTemp1offset+(mAmbient?20:0),mAmbient?mAmbMaxTempPaint: mMaxTempPaint);
                if(!mAmbient)
                canvas.drawText(String.format("%d\u00B0",mMinTemp)+"",midX*3/2, mYOffset+mTempOffset, mMinTempPaint);
            }
            if(mAmbImage!=null && mAmbient)
                canvas.drawBitmap(mAmbImage,midX-25,midY-25,null);
            if(mWeatherImage!=null && !mAmbient)
                canvas.drawBitmap(mWeatherImage,mXOffset+mImageOffsetX,mYOffset+dateOffset+mImageOffsetY,null);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient,this);
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(@NonNull DataItemBuffer dataItems) {
                    for(DataItem dataItem:dataItems){
                        if(dataItem!=null && dataItem.getUri().getPath().compareTo("/weather")==0)
                        {
                            DataMapItem dataMapItem=DataMapItem.fromDataItem(dataItem);
                            DataMap dataMap=dataMapItem.getDataMap();
                            new loadBitmap(1).execute(dataMap.getAsset("WeatherImage"));
                            new loadBitmap(0).execute(dataMap.getAsset("bwWeatherImage"));
                            mMinTemp=dataMap.getInt("MinTemp");
                            mMaxTemp=dataMap.getInt("MaxTemp");

                            mDatePaint.setColor(dataMap.getInt("BodyColor"));
                            mTimePaint.setColor(dataMap.getInt("BodyColor"));
                            mMaxTempPaint.setColor(dataMap.getInt("titleColor"));
                            mMinTempPaint.setColor(dataMap.getInt("titleColor"));
                            mBackgroundColor=dataMap.getInt("backgroundColor");
                        }
                    }
                    dataItems.release();
                }
            });
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event:dataEventBuffer){
                if(event.getType()!=DataEvent.TYPE_DELETED){
                    DataItem dataItem=event.getDataItem();
                    if(dataItem.getUri().getPath().compareTo("/weather")==0)
                    {
                        DataMapItem dataMapItem=DataMapItem.fromDataItem(dataItem);
                        DataMap dataMap=dataMapItem.getDataMap();
                        new loadBitmap(1).execute(dataMap.getAsset("WeatherImage"));
                        new loadBitmap(0).execute(dataMap.getAsset("bwWeatherImage"));
                        mMinTemp=dataMap.getInt("MinTemp");
                        mMaxTemp=dataMap.getInt("MaxTemp");

                        mDatePaint.setColor(dataMap.getInt("BodyColor"));
                        mTimePaint.setColor(dataMap.getInt("BodyColor"));
                        mMaxTempPaint.setColor(dataMap.getInt("titleColor"));
                        mMinTempPaint.setColor(dataMap.getInt("titleColor"));
                        mBackgroundColor=dataMap.getInt("backgroundColor");
                    }
                }
                else{
                    mWeatherImage=null;
                    mMinTemp=10000000;
                    mMaxTemp=10000000;
                }
            }
        }
        public class loadBitmap extends AsyncTask<Asset,Void,Bitmap>
        {
            int mFlag=0;
            loadBitmap(int flag){
                mFlag=flag;
            }
            @Override
            protected Bitmap doInBackground(Asset... params) {
                Asset asset=params[0];
                if (asset == null) {
                    return null;
                }
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset).await().getInputStream();
                if (assetInputStream == null) {
                    return null;
                }
                return BitmapFactory.decodeStream(assetInputStream);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if(bitmap!=null && mFlag==1)
                    mWeatherImage=bitmap;
                else if(bitmap!=null && mFlag==0)
                    mAmbImage=bitmap;
            }
        }
        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        }
    }
}
