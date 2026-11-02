/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import com.xiaomi.touchservice.R;

import java.util.List;

/*
 * Draws the actions as equal slices of a ring with an icon in each, the
 * first one on top. The name of the slice under the finger or pen shows in
 * the middle. Lifting on a slice runs it, lifting anywhere else closes the
 * pie.
 */
final class PieView extends View {

    interface Listener {
        void onSelected(String action);

        void onDismiss();
    }

    private static final int SCRIM = 0x40000000;

    private static final float INNER_DP = 44;
    private static final float OUTER_DP = 116;
    private static final float GAP_DP = 4;
    private static final float ICON_DP = 24;
    private static final float MARGIN_DP = 16;
    private static final float TEXT_SP = 13;

    private final List<String> mActions;
    private final Listener mListener;
    private final float mPenX;
    private final float mPenY;

    private final float mInner;
    private final float mOuter;
    private final float mGap;
    private final int mIconSize;
    private final float mMargin;
    private final float mSweep;

    private final int mSliceColor;
    private final int mPressedColor;
    private final int mIconColor;
    private final int mPressedIconColor;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] mSlices;
    private final Drawable[] mIcons;
    private final String[] mLabels;

    private float mCenterX;
    private float mCenterY;
    private int mPressed = -1;

    PieView(Context context, List<String> actions, float penX, float penY, Listener listener) {
        super(context);
        mActions = actions;
        mListener = listener;
        mPenX = penX;
        mPenY = penY;

        float density = getResources().getDisplayMetrics().density;
        mInner = INNER_DP * density;
        mOuter = OUTER_DP * density;
        mGap = GAP_DP * density;
        mIconSize = Math.round(ICON_DP * density);
        mMargin = MARGIN_DP * density;
        mSweep = 360f / actions.size();

        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mSliceColor = context.getColor(night ? android.R.color.system_neutral1_800
                : android.R.color.system_neutral1_50);
        mPressedColor = context.getColor(night ? android.R.color.system_accent1_200
                : android.R.color.system_accent1_600);
        mIconColor = context.getColor(night ? android.R.color.system_neutral1_100
                : android.R.color.system_neutral1_900);
        mPressedIconColor = context.getColor(night ? android.R.color.system_accent1_800
                : android.R.color.system_accent1_0);

        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTextSize(TEXT_SP * getResources().getDisplayMetrics().scaledDensity);
        mTextPaint.setColor(mIconColor);

        mSlices = new Path[actions.size()];
        mIcons = new Drawable[actions.size()];
        mLabels = new String[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            mIcons[i] = context.getDrawable(iconFor(actions.get(i))).mutate();
            mLabels[i] = context.getString(labelFor(actions.get(i)));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float edge = mOuter + mMargin;
        mCenterX = clamp(mPenX < 0 ? w / 2f : mPenX, edge, w - edge);
        mCenterY = clamp(mPenY < 0 ? h / 2f : mPenY, edge, h - edge);

        RectF outer = new RectF(mCenterX - mOuter, mCenterY - mOuter,
                mCenterX + mOuter, mCenterY + mOuter);
        RectF inner = new RectF(mCenterX - mInner, mCenterY - mInner,
                mCenterX + mInner, mCenterY + mInner);
        float outerGap = (float) Math.toDegrees(mGap / mOuter);
        float innerGap = (float) Math.toDegrees(mGap / mInner);
        float mid = (mInner + mOuter) / 2;

        for (int i = 0; i < mSlices.length; i++) {
            float start = startAngle(i);
            Path path = new Path();
            path.arcTo(outer, start + outerGap / 2, mSweep - outerGap);
            path.arcTo(inner, start + mSweep - innerGap / 2, -(mSweep - innerGap));
            path.close();
            mSlices[i] = path;

            double angle = Math.toRadians(start + mSweep / 2);
            int x = Math.round(mCenterX + mid * (float) Math.cos(angle));
            int y = Math.round(mCenterY + mid * (float) Math.sin(angle));
            mIcons[i].setBounds(x - mIconSize / 2, y - mIconSize / 2,
                    x + mIconSize / 2, y + mIconSize / 2);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(SCRIM);

        for (int i = 0; i < mSlices.length; i++) {
            boolean pressed = i == mPressed;
            mPaint.setColor(pressed ? mPressedColor : mSliceColor);
            canvas.drawPath(mSlices[i], mPaint);
            mIcons[i].setTint(pressed ? mPressedIconColor : mIconColor);
            mIcons[i].draw(canvas);
        }

        mPaint.setColor(mSliceColor);
        canvas.drawCircle(mCenterX, mCenterY, mInner - mGap, mPaint);
        if (mPressed >= 0) {
            float y = mCenterY - (mTextPaint.ascent() + mTextPaint.descent()) / 2;
            canvas.drawText(mLabels[mPressed], mCenterX, y, mTextPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                setPressed(sliceAt(event.getX(), event.getY()));
                break;
            case MotionEvent.ACTION_UP:
                int slice = sliceAt(event.getX(), event.getY());
                if (slice >= 0) {
                    mListener.onSelected(mActions.get(slice));
                } else {
                    mListener.onDismiss();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mListener.onDismiss();
                break;
        }
        return true;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        mListener.onDismiss();
    }

    private void setPressed(int slice) {
        if (slice != mPressed) {
            mPressed = slice;
            invalidate();
        }
    }

    private int sliceAt(float x, float y) {
        float dx = x - mCenterX;
        float dy = y - mCenterY;
        double distance = Math.hypot(dx, dy);
        if (distance < mInner || distance > mOuter + mMargin) {
            return -1;
        }
        double angle = Math.toDegrees(Math.atan2(dy, dx)) - startAngle(0);
        angle = (angle % 360 + 360) % 360;
        return Math.min((int) (angle / mSweep), mSlices.length - 1);
    }

    private float startAngle(int slice) {
        return -90 - mSweep / 2 + slice * mSweep;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static int labelFor(String action) {
        switch (action) {
            case PenGestures.ACTION_NOTES:
                return R.string.action_notes;
            case PenGestures.ACTION_SCREENSHOT:
                return R.string.action_screenshot;
            case PenGestures.ACTION_ASSISTANT:
                return R.string.action_assistant;
            case PenGestures.ACTION_RECENTS:
                return R.string.action_recents;
            default:
                return R.string.action_play_pause;
        }
    }

    private static int iconFor(String action) {
        switch (action) {
            case PenGestures.ACTION_NOTES:
                return R.drawable.ic_pie_notes;
            case PenGestures.ACTION_SCREENSHOT:
                return R.drawable.ic_pie_screenshot;
            case PenGestures.ACTION_ASSISTANT:
                return R.drawable.ic_pie_assistant;
            case PenGestures.ACTION_RECENTS:
                return R.drawable.ic_pie_recents;
            default:
                return R.drawable.ic_pie_play_pause;
        }
    }
}
