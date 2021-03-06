/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package android.support.v17.leanback.app;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v17.leanback.R;
import android.support.v17.leanback.animation.LogAccelerateInterpolator;
import android.support.v17.leanback.animation.LogDecelerateInterpolator;
import android.support.v17.leanback.media.PlaybackGlueHost;
import android.support.v17.leanback.widget.ArrayObjectAdapter;
import android.support.v17.leanback.widget.BaseOnItemViewClickedListener;
import android.support.v17.leanback.widget.BaseOnItemViewSelectedListener;
import android.support.v17.leanback.widget.ClassPresenterSelector;
import android.support.v17.leanback.widget.ItemAlignmentFacet;
import android.support.v17.leanback.widget.ItemBridgeAdapter;
import android.support.v17.leanback.widget.ObjectAdapter;
import android.support.v17.leanback.widget.PlaybackRowPresenter;
import android.support.v17.leanback.widget.Presenter;
import android.support.v17.leanback.widget.PresenterSelector;
import android.support.v17.leanback.widget.Row;
import android.support.v17.leanback.widget.RowPresenter;
import android.support.v17.leanback.widget.SparseArrayObjectAdapter;
import android.support.v17.leanback.widget.VerticalGridView;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;

import java.util.ArrayList;

/**
 * A fragment for displaying playback controls and related content.
 *
 * <p>
 * A PlaybackFragment renders the elements of its {@link ObjectAdapter} as a set
 * of rows in a vertical list.  The Adapter's {@link PresenterSelector} must maintain subclasses
 * of {@link RowPresenter}.
 * </p>
 * <p>
 * A playback row is a row rendered by {@link PlaybackRowPresenter}.
 * App can call {@link #setPlaybackRow(Row)} to set playback row for the first element of adapter.
 * App can call {@link #setPlaybackRowPresenter(PlaybackRowPresenter)} to set presenter for it.
 * {@link #setPlaybackRow(Row)} and {@link #setPlaybackRowPresenter(PlaybackRowPresenter)} are
 * optional, app can pass playback row and PlaybackRowPresenter in the adapter using
 * {@link #setAdapter(ObjectAdapter)}.
 * </p>
 */
public class PlaybackFragment extends Fragment {
    /**
     * No background.
     */
    public static final int BG_NONE = 0;

    /**
     * A dark translucent background.
     */
    public static final int BG_DARK = 1;
    PlaybackGlueHost.HostCallback mHostCallback;

    /**
     * Resets the focus on the button in the middle of control row.
     * @hide
     */
    public void resetFocus() {
        ItemBridgeAdapter.ViewHolder vh = (ItemBridgeAdapter.ViewHolder) getVerticalGridView()
                .findViewHolderForAdapterPosition(0);
        if (vh != null && vh.getPresenter() instanceof PlaybackRowPresenter) {
            ((PlaybackRowPresenter) vh.getPresenter()).onReappear(
                    (RowPresenter.ViewHolder) vh.getViewHolder());
        }
    }

    private class SetSelectionRunnable implements Runnable {
        int mPosition;
        boolean mSmooth = true;

        @Override
        public void run() {
            if (mRowsFragment == null) {
                return;
            }
            mRowsFragment.setSelectedPosition(mPosition, mSmooth);
        }
    }

    /**
     * A light translucent background.
     */
    public static final int BG_LIGHT = 2;
    RowsFragment mRowsFragment;
    ObjectAdapter mAdapter;
    PlaybackRowPresenter mPresenter;
    Row mRow;
    BaseOnItemViewSelectedListener mExternalItemSelectedListener;
    BaseOnItemViewClickedListener mExternalItemClickedListener;
    BaseOnItemViewClickedListener mPlaybackItemClickedListener;

    private final BaseOnItemViewClickedListener mOnItemViewClickedListener =
            new BaseOnItemViewClickedListener() {
                @Override
                public void onItemClicked(Presenter.ViewHolder itemViewHolder,
                                          Object item,
                                          RowPresenter.ViewHolder rowViewHolder,
                                          Object row) {
                    if (mPlaybackItemClickedListener != null
                            && rowViewHolder instanceof PlaybackRowPresenter.ViewHolder) {
                        mPlaybackItemClickedListener.onItemClicked(
                                itemViewHolder, item, rowViewHolder, row);
                    }
                    if (mExternalItemClickedListener != null) {
                        mExternalItemClickedListener.onItemClicked(
                                itemViewHolder, item, rowViewHolder, row);
                    }
                }
            };

    private final BaseOnItemViewSelectedListener mOnItemViewSelectedListener =
            new BaseOnItemViewSelectedListener() {
                @Override
                public void onItemSelected(Presenter.ViewHolder itemViewHolder,
                                           Object item,
                                           RowPresenter.ViewHolder rowViewHolder,
                                           Object row) {
                    if (mExternalItemSelectedListener != null) {
                        mExternalItemSelectedListener.onItemSelected(
                                itemViewHolder, item, rowViewHolder, row);
                    }
                }
            };

    private final SetSelectionRunnable mSetSelectionRunnable = new SetSelectionRunnable();

    public ObjectAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Listener allowing the application to receive notification of fade in and/or fade out
     * completion events.
     * @hide
     */
    public static class OnFadeCompleteListener {
        public void onFadeInComplete() {
        }

        public void onFadeOutComplete() {
        }
    }

    private static final String TAG = "PlaybackFragment";
    private static final boolean DEBUG = false;
    private static final int ANIMATION_MULTIPLIER = 1;

    private static int START_FADE_OUT = 1;

    // Fading status
    private static final int IDLE = 0;
    private static final int IN = 1;
    private static final int OUT = 2;

    int mPaddingBottom;
    int mOtherRowsCenterToBottom;
    View mRootView;
    int mBackgroundType = BG_DARK;
    int mBgDarkColor;
    int mBgLightColor;
    int mShowTimeMs;
    int mMajorFadeTranslateY, mMinorFadeTranslateY;
    int mAnimationTranslateY;
    OnFadeCompleteListener mFadeCompleteListener;
    View.OnKeyListener mInputEventHandler;
    boolean mFadingEnabled = true;
    int mFadingStatus = IDLE;
    int mBgAlpha;
    ValueAnimator mBgFadeInAnimator, mBgFadeOutAnimator;
    ValueAnimator mControlRowFadeInAnimator, mControlRowFadeOutAnimator;
    ValueAnimator mOtherRowFadeInAnimator, mOtherRowFadeOutAnimator;

    private final Animator.AnimatorListener mFadeListener =
            new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    enableVerticalGridAnimations(false);
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (DEBUG) Log.v(TAG, "onAnimationEnd " + mBgAlpha);
                    if (mBgAlpha > 0) {
                        enableVerticalGridAnimations(true);
                        startFadeTimer();
                        if (mFadeCompleteListener != null) {
                            mFadeCompleteListener.onFadeInComplete();
                        }
                    } else {
                        VerticalGridView verticalView = getVerticalGridView();
                        // reset focus to the primary actions only if the selected row was the controls row
                        if (verticalView != null && verticalView.getSelectedPosition() == 0) {
                            ItemBridgeAdapter.ViewHolder vh = (ItemBridgeAdapter.ViewHolder)
                                    verticalView.findViewHolderForAdapterPosition(0);
                            if (vh != null && vh.getPresenter() instanceof PlaybackRowPresenter) {
                                ((PlaybackRowPresenter)vh.getPresenter()).onReappear(
                                        (RowPresenter.ViewHolder) vh.getViewHolder());
                            }
                        }
                        if (mFadeCompleteListener != null) {
                            mFadeCompleteListener.onFadeOutComplete();
                        }
                    }
                    mFadingStatus = IDLE;
                }
            };

    VerticalGridView getVerticalGridView() {
        if (mRowsFragment == null) {
            return null;
        }
        return mRowsFragment.getVerticalGridView();
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            if (message.what == START_FADE_OUT && mFadingEnabled) {
                fade(false);
            }
        }
    };

    private final VerticalGridView.OnTouchInterceptListener mOnTouchInterceptListener =
            new VerticalGridView.OnTouchInterceptListener() {
                @Override
                public boolean onInterceptTouchEvent(MotionEvent event) {
                    return onInterceptInputEvent(event);
                }
            };

    private final VerticalGridView.OnKeyInterceptListener mOnKeyInterceptListener =
            new VerticalGridView.OnKeyInterceptListener() {
                @Override
                public boolean onInterceptKeyEvent(KeyEvent event) {
                    return onInterceptInputEvent(event);
                }
            };

    private void setBgAlpha(int alpha) {
        mBgAlpha = alpha;
        if (mRootView != null) {
            mRootView.getBackground().setAlpha(alpha);
        }
    }

    private void enableVerticalGridAnimations(boolean enable) {
        if (getVerticalGridView() != null) {
            getVerticalGridView().setAnimateChildLayout(enable);
        }
    }

    /**
     * Enables or disables view fading.  If enabled,
     * the view will be faded in when the fragment starts,
     * and will fade out after a time period.  The timeout
     * period is reset each time {@link #tickle} is called.
     */
    public void setFadingEnabled(boolean enabled) {
        if (DEBUG) Log.v(TAG, "setFadingEnabled " + enabled);
        if (enabled != mFadingEnabled) {
            mFadingEnabled = enabled;
            if (mFadingEnabled) {
                if (isResumed() && mFadingStatus == IDLE
                        && !mHandler.hasMessages(START_FADE_OUT)) {
                    startFadeTimer();
                }
            } else {
                // Ensure fully opaque
                mHandler.removeMessages(START_FADE_OUT);
                fade(true);
            }
        }
    }

    /**
     * Returns true if view fading is enabled.
     */
    public boolean isFadingEnabled() {
        return mFadingEnabled;
    }

    /**
     * Sets the listener to be called when fade in or out has completed.
     * @hide
     */
    public void setFadeCompleteListener(OnFadeCompleteListener listener) {
        mFadeCompleteListener = listener;
    }

    /**
     * Returns the listener to be called when fade in or out has completed.
     * @hide
     */
    public OnFadeCompleteListener getFadeCompleteListener() {
        return mFadeCompleteListener;
    }

    /**
     * Sets the input event handler.
     */
    public final void setOnKeyInterceptListener(View.OnKeyListener handler) {
        mInputEventHandler = handler;
    }

    /**
     * Tickles the playback controls.  Fades in the view if it was faded out,
     * otherwise resets the fade out timer.  Tickling on input events is handled
     * by the fragment.
     */
    public void tickle() {
        if (DEBUG) Log.v(TAG, "tickle enabled " + mFadingEnabled + " isResumed " + isResumed());
        if (!mFadingEnabled || !isResumed()) {
            return;
        }
        if (mHandler.hasMessages(START_FADE_OUT)) {
            // Restart the timer
            startFadeTimer();
        } else {
            fade(true);
        }
    }

    /**
     * Fades out the playback overlay immediately.
     */
    public void fadeOut() {
        mHandler.removeMessages(START_FADE_OUT);
        fade(false);
    }

    /**
     * Returns true/false indicating whether playback controls are visible or not.
     */
    private boolean areControlsHidden() {
        return mFadingStatus == IDLE && mBgAlpha == 0;
    }

    private boolean onInterceptInputEvent(InputEvent event) {
        final boolean controlsHidden = areControlsHidden();
        if (DEBUG) Log.v(TAG, "onInterceptInputEvent hidden " + controlsHidden + " " + event);
        boolean consumeEvent = false;
        int keyCode = KeyEvent.KEYCODE_UNKNOWN;

        if (event instanceof KeyEvent) {
            keyCode = ((KeyEvent) event).getKeyCode();
            if (mInputEventHandler != null) {
                consumeEvent = mInputEventHandler.onKey(getView(), keyCode, (KeyEvent) event);
            }
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Event may be consumed; regardless, if controls are hidden then these keys will
                // bring up the controls.
                if (controlsHidden) {
                    consumeEvent = true;
                }
                tickle();
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                // If fading enabled and controls are not hidden, back will be consumed to fade
                // them out (even if the key was consumed by the handler).
                if (mFadingEnabled && !controlsHidden) {
                    consumeEvent = true;
                    mHandler.removeMessages(START_FADE_OUT);
                    fade(false);
                } else if (consumeEvent) {
                    tickle();
                }
                break;
            default:
                if (consumeEvent) {
                    tickle();
                }
        }
        return consumeEvent;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mFadingEnabled) {
            setBgAlpha(0);
            fade(true);
        }
        getVerticalGridView().setOnTouchInterceptListener(mOnTouchInterceptListener);
        getVerticalGridView().setOnKeyInterceptListener(mOnKeyInterceptListener);
        if (mHostCallback != null) {
            mHostCallback.onHostResume();
        }
    }

    private void startFadeTimer() {
        if (mHandler != null) {
            mHandler.removeMessages(START_FADE_OUT);
            mHandler.sendEmptyMessageDelayed(START_FADE_OUT, mShowTimeMs);
        }
    }

    private static ValueAnimator loadAnimator(Context context, int resId) {
        ValueAnimator animator = (ValueAnimator) AnimatorInflater.loadAnimator(context, resId);
        animator.setDuration(animator.getDuration() * ANIMATION_MULTIPLIER);
        return animator;
    }

    private void loadBgAnimator() {
        AnimatorUpdateListener listener = new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator arg0) {
                setBgAlpha((Integer) arg0.getAnimatedValue());
            }
        };

        Context context = FragmentUtil.getContext(this);
        mBgFadeInAnimator = loadAnimator(context, R.animator.lb_playback_bg_fade_in);
        mBgFadeInAnimator.addUpdateListener(listener);
        mBgFadeInAnimator.addListener(mFadeListener);

        mBgFadeOutAnimator = loadAnimator(context, R.animator.lb_playback_bg_fade_out);
        mBgFadeOutAnimator.addUpdateListener(listener);
        mBgFadeOutAnimator.addListener(mFadeListener);
    }

    private TimeInterpolator mLogDecelerateInterpolator = new LogDecelerateInterpolator(100, 0);
    private TimeInterpolator mLogAccelerateInterpolator = new LogAccelerateInterpolator(100, 0);

    private View getControlRowView() {
        if (getVerticalGridView() == null) {
            return null;
        }
        RecyclerView.ViewHolder vh = getVerticalGridView().findViewHolderForPosition(0);
        if (vh == null) {
            return null;
        }
        return vh.itemView;
    }

    private void loadControlRowAnimator() {
        final AnimatorListener listener = new AnimatorListener() {
            @Override
            void getViews(ArrayList<View> views) {
                View view = getControlRowView();
                if (view != null) {
                    views.add(view);
                }
            }
        };
        final AnimatorUpdateListener updateListener = new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator arg0) {
                View view = getControlRowView();
                if (view != null) {
                    final float fraction = (Float) arg0.getAnimatedValue();
                    if (DEBUG) Log.v(TAG, "fraction " + fraction);
                    view.setAlpha(fraction);
                    view.setTranslationY((float) mAnimationTranslateY * (1f - fraction));
                }
            }
        };

        Context context = FragmentUtil.getContext(this);
        mControlRowFadeInAnimator = loadAnimator(context, R.animator.lb_playback_controls_fade_in);
        mControlRowFadeInAnimator.addUpdateListener(updateListener);
        mControlRowFadeInAnimator.addListener(listener);
        mControlRowFadeInAnimator.setInterpolator(mLogDecelerateInterpolator);

        mControlRowFadeOutAnimator = loadAnimator(context,
                R.animator.lb_playback_controls_fade_out);
        mControlRowFadeOutAnimator.addUpdateListener(updateListener);
        mControlRowFadeOutAnimator.addListener(listener);
        mControlRowFadeOutAnimator.setInterpolator(mLogAccelerateInterpolator);
    }

    private void loadOtherRowAnimator() {
        final AnimatorListener listener = new AnimatorListener() {
            @Override
            void getViews(ArrayList<View> views) {
                if (getVerticalGridView() == null) {
                    return;
                }
                final int count = getVerticalGridView().getChildCount();
                for (int i = 0; i < count; i++) {
                    View view = getVerticalGridView().getChildAt(i);
                    if (view != null) {
                        views.add(view);
                    }
                }
            }
        };
        final AnimatorUpdateListener updateListener = new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator arg0) {
                if (getVerticalGridView() == null) {
                    return;
                }
                final float fraction = (Float) arg0.getAnimatedValue();
                for (View view : listener.mViews) {
                    if (getVerticalGridView().getChildPosition(view) > 0) {
                        view.setAlpha(fraction);
                        view.setTranslationY((float) mAnimationTranslateY * (1f - fraction));
                    }
                }
            }
        };

        Context context = FragmentUtil.getContext(this);
        mOtherRowFadeInAnimator = loadAnimator(context, R.animator.lb_playback_controls_fade_in);
        mOtherRowFadeInAnimator.addListener(listener);
        mOtherRowFadeInAnimator.addUpdateListener(updateListener);
        mOtherRowFadeInAnimator.setInterpolator(mLogDecelerateInterpolator);

        mOtherRowFadeOutAnimator = loadAnimator(context, R.animator.lb_playback_controls_fade_out);
        mOtherRowFadeOutAnimator.addListener(listener);
        mOtherRowFadeOutAnimator.addUpdateListener(updateListener);
        mOtherRowFadeOutAnimator.setInterpolator(new AccelerateInterpolator());
    }

    private void fade(boolean fadeIn) {
        if (DEBUG) Log.v(TAG, "fade " + fadeIn);
        if (getView() == null) {
            return;
        }
        if ((fadeIn && mFadingStatus == IN) || (!fadeIn && mFadingStatus == OUT)) {
            if (DEBUG) Log.v(TAG, "requested fade in progress");
            return;
        }
        if ((fadeIn && mBgAlpha == 255) || (!fadeIn && mBgAlpha == 0)) {
            if (DEBUG) Log.v(TAG, "fade is no-op");
            return;
        }

        mAnimationTranslateY = getVerticalGridView().getSelectedPosition() == 0
                ? mMajorFadeTranslateY : mMinorFadeTranslateY;

        if (mFadingStatus == IDLE) {
            if (fadeIn) {
                mBgFadeInAnimator.start();
                mControlRowFadeInAnimator.start();
                mOtherRowFadeInAnimator.start();
            } else {
                mBgFadeOutAnimator.start();
                mControlRowFadeOutAnimator.start();
                mOtherRowFadeOutAnimator.start();
            }
        } else {
            if (fadeIn) {
                mBgFadeOutAnimator.reverse();
                mControlRowFadeOutAnimator.reverse();
                mOtherRowFadeOutAnimator.reverse();
            } else {
                mBgFadeInAnimator.reverse();
                mControlRowFadeInAnimator.reverse();
                mOtherRowFadeInAnimator.reverse();
            }
        }
        getView().announceForAccessibility(getString(fadeIn ? R.string.lb_playback_controls_shown
                : R.string.lb_playback_controls_hidden));

        // If fading in while control row is focused, set initial translationY so
        // views slide in from below.
        if (fadeIn && mFadingStatus == IDLE) {
            final int count = getVerticalGridView().getChildCount();
            for (int i = 0; i < count; i++) {
                getVerticalGridView().getChildAt(i).setTranslationY(mAnimationTranslateY);
            }
        }

        mFadingStatus = fadeIn ? IN : OUT;
    }

    /**
     * Sets the selected row position with smooth animation.
     */
    public void setSelectedPosition(int position) {
        setSelectedPosition(position, true);
    }

    /**
     * Sets the selected row position.
     */
    public void setSelectedPosition(int position, boolean smooth) {
        mSetSelectionRunnable.mPosition = position;
        mSetSelectionRunnable.mSmooth = smooth;
        if (getView() != null && getView().getHandler() != null) {
            getView().getHandler().post(mSetSelectionRunnable);
        }
    }

    private void setupChildFragmentLayout() {
        setVerticalGridViewLayout(mRowsFragment.getVerticalGridView());
    }

    void setVerticalGridViewLayout(VerticalGridView listview) {
        if (listview == null) {
            return;
        }

        // we set the base line of alignment to -paddingBottom
        listview.setWindowAlignmentOffset(-mPaddingBottom);
        listview.setWindowAlignmentOffsetPercent(
                VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);

        // align other rows that arent the last to center of screen, since our baseline is
        // -mPaddingBottom, we need subtract that from mOtherRowsCenterToBottom.
        listview.setItemAlignmentOffset(mOtherRowsCenterToBottom - mPaddingBottom);
        listview.setItemAlignmentOffsetPercent(50);

        // Push last row to the bottom padding
        // Padding affects alignment when last row is focused
        listview.setPadding(listview.getPaddingLeft(), listview.getPaddingTop(),
                listview.getPaddingRight(), mPaddingBottom);
        listview.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_HIGH_EDGE);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mOtherRowsCenterToBottom = getResources()
                .getDimensionPixelSize(R.dimen.lb_playback_other_rows_center_to_bottom);
        mPaddingBottom =
                getResources().getDimensionPixelSize(R.dimen.lb_playback_controls_padding_bottom);
        mBgDarkColor =
                getResources().getColor(R.color.lb_playback_controls_background_dark);
        mBgLightColor =
                getResources().getColor(R.color.lb_playback_controls_background_light);
        mShowTimeMs =
                getResources().getInteger(R.integer.lb_playback_controls_show_time_ms);
        mMajorFadeTranslateY =
                getResources().getDimensionPixelSize(R.dimen.lb_playback_major_fade_translate_y);
        mMinorFadeTranslateY =
                getResources().getDimensionPixelSize(R.dimen.lb_playback_minor_fade_translate_y);

        loadBgAnimator();
        loadControlRowAnimator();
        loadOtherRowAnimator();
    }

    /**
     * Sets the background type.
     *
     * @param type One of BG_LIGHT, BG_DARK, or BG_NONE.
     */
    public void setBackgroundType(int type) {
        switch (type) {
            case BG_LIGHT:
            case BG_DARK:
            case BG_NONE:
                if (type != mBackgroundType) {
                    mBackgroundType = type;
                    updateBackground();
                }
                break;
            default:
                throw new IllegalArgumentException("Invalid background type");
        }
    }

    /**
     * Returns the background type.
     */
    public int getBackgroundType() {
        return mBackgroundType;
    }

    private void updateBackground() {
        if (mRootView != null) {
            int color = mBgDarkColor;
            switch (mBackgroundType) {
                case BG_DARK:
                    break;
                case BG_LIGHT:
                    color = mBgLightColor;
                    break;
                case BG_NONE:
                    color = Color.TRANSPARENT;
                    break;
            }
            mRootView.setBackground(new ColorDrawable(color));
        }
    }

    private final ItemBridgeAdapter.AdapterListener mAdapterListener =
            new ItemBridgeAdapter.AdapterListener() {
                @Override
                public void onAttachedToWindow(ItemBridgeAdapter.ViewHolder vh) {
                    if (DEBUG) Log.v(TAG, "onAttachedToWindow " + vh.getViewHolder().view);
                    if ((mFadingStatus == IDLE && mBgAlpha == 0) || mFadingStatus == OUT) {
                        if (DEBUG) Log.v(TAG, "setting alpha to 0");
                        vh.getViewHolder().view.setAlpha(0);
                    }
                }

                @Override
                public void onDetachedFromWindow(ItemBridgeAdapter.ViewHolder vh) {
                    if (DEBUG) Log.v(TAG, "onDetachedFromWindow " + vh.getViewHolder().view);
                    // Reset animation state
                    vh.getViewHolder().view.setAlpha(1f);
                    vh.getViewHolder().view.setTranslationY(0);
                    vh.getViewHolder().view.setAlpha(1f);
                }

                @Override
                public void onBind(ItemBridgeAdapter.ViewHolder vh) {
                }
            };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.lb_playback_fragment, container, false);
        mRowsFragment = (RowsFragment) getChildFragmentManager().findFragmentById(
                R.id.playback_controls_dock);
        if (mRowsFragment == null) {
            mRowsFragment = new RowsFragment();
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.playback_controls_dock, mRowsFragment)
                    .commit();
        }
        if (mAdapter == null) {
            setAdapter(new ArrayObjectAdapter(new ClassPresenterSelector()));
        } else {
            mRowsFragment.setAdapter(mAdapter);
        }
        mRowsFragment.setOnItemViewSelectedListener(mOnItemViewSelectedListener);
        mRowsFragment.setOnItemViewClickedListener(mOnItemViewClickedListener);

        mBgAlpha = 255;
        updateBackground();
        mRowsFragment.setExternalAdapterListener(mAdapterListener);
        return mRootView;
    }

    /**
     * Sets the {@link PlaybackGlueHost.HostCallback}. Implementor of this interface will
     * take appropriate actions to take action when the hosting fragment starts/stops processing.
     */
    public void setHostCallback(PlaybackGlueHost.HostCallback hostCallback) {
        this.mHostCallback = hostCallback;
    }

    @Override
    public void onStart() {
        super.onStart();
        setupChildFragmentLayout();
        mRowsFragment.setAdapter(mAdapter);
        if (mHostCallback != null) {
            mHostCallback.onHostStart();
        }
    }

    @Override
    public void onStop() {
        if (mHostCallback != null) {
            mHostCallback.onHostStop();
        }
        super.onStop();
    }

    @Override
    public void onPause() {
        if (mHostCallback != null) {
            mHostCallback.onHostPause();
        }
        super.onPause();
    }

    /**
     * This listener is called every time there is a selection in {@link RowsFragment}. This can
     * be used by users to take additional actions such as animations.
     */
    public void setOnItemViewSelectedListener(final BaseOnItemViewSelectedListener listener) {
        mExternalItemSelectedListener = listener;
    }

    /**
     * This listener is called every time there is a click in {@link RowsFragment}. This can
     * be used by users to take additional actions such as animations.
     */
    public void setOnItemViewClickedListener(final BaseOnItemViewClickedListener listener) {
        mExternalItemClickedListener = listener;
    }

    /**
     * Sets the {@link BaseOnItemViewClickedListener} that would be invoked for clicks
     * only on {@link android.support.v17.leanback.widget.PlaybackRowPresenter.ViewHolder}.
     */
    public void setOnPlaybackItemViewClickedListener(final BaseOnItemViewClickedListener listener) {
        mPlaybackItemClickedListener = listener;
    }

    @Override
    public void onDestroyView() {
        mRootView = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        if (mHostCallback != null) {
            mHostCallback.onHostDestroy();
        }
        super.onDestroy();
    }

    /**
     * Sets the playback row for the playback controls. The row will be set as first element
     * of adapter if the adapter is {@link ArrayObjectAdapter} or {@link SparseArrayObjectAdapter}.
     * @param row The row that represents the playback.
     */
    public void setPlaybackRow(Row row) {
        this.mRow = row;
        setupRow();
        setupPresenter();
    }

    /**
     * Sets the presenter for rendering the playback row set by {@link #setPlaybackRow(Row)}. If
     * adapter does not set a {@link PresenterSelector}, {@link #setAdapter(ObjectAdapter)} will
     * create a {@link ClassPresenterSelector} by default and map from the row object class to this
     * {@link PlaybackRowPresenter}.
     *
     * @param  presenter Presenter used to render {@link #setPlaybackRow(Row)}.
     */
    public void setPlaybackRowPresenter(PlaybackRowPresenter presenter) {
        this.mPresenter = presenter;
        setupPresenter();
        setPlaybackRowPresenterAlignment();
    }

    void setPlaybackRowPresenterAlignment() {
        if (mAdapter != null && mAdapter.getPresenterSelector() != null) {
            Presenter[] presenters = mAdapter.getPresenterSelector().getPresenters();
            if (presenters != null) {
                for (int i = 0; i < presenters.length; i++) {
                    if (presenters[i] instanceof PlaybackRowPresenter
                            && presenters[i].getFacet(ItemAlignmentFacet.class) == null) {
                        ItemAlignmentFacet itemAlignment = new ItemAlignmentFacet();
                        ItemAlignmentFacet.ItemAlignmentDef def =
                                new ItemAlignmentFacet.ItemAlignmentDef();
                        def.setItemAlignmentOffset(0);
                        def.setItemAlignmentOffsetPercent(100);
                        itemAlignment.setAlignmentDefs(new ItemAlignmentFacet.ItemAlignmentDef[]
                                {def});
                        presenters[i].setFacet(ItemAlignmentFacet.class, itemAlignment);
                    }
                }
            }
        }
    }

    /**
     * Updates the ui when the row data changes.
     */
    public void notifyPlaybackRowChanged() {
        if (mAdapter == null) {
            return;
        }
        mAdapter.notifyItemRangeChanged(0, 1);
    }

    /**
     * Sets the list of rows for the fragment. A default {@link ClassPresenterSelector} will be
     * created if {@link ObjectAdapter#getPresenterSelector()} is null. if user provides
     * {@link #setPlaybackRow(Row)} and {@link #setPlaybackRowPresenter(PlaybackRowPresenter)},
     * the row and presenter will be set onto the adapter.
     *
     * @param adapter The adapter that contains related rows and optional playback row.
     */
    public void setAdapter(ObjectAdapter adapter) {
        mAdapter = adapter;
        setupRow();
        setupPresenter();
        setPlaybackRowPresenterAlignment();

        if (mRowsFragment != null) {
            mRowsFragment.setAdapter(adapter);
        }
    }

    private void setupRow() {
        if (mAdapter instanceof ArrayObjectAdapter && mRow != null) {
            ArrayObjectAdapter adapter = ((ArrayObjectAdapter) mAdapter);
            if (adapter.size() == 0) {
                adapter.add(mRow);
            } else {
                adapter.replace(0, mRow);
            }
        } else if (mAdapter instanceof SparseArrayObjectAdapter && mRow != null) {
            SparseArrayObjectAdapter adapter = ((SparseArrayObjectAdapter) mAdapter);
            adapter.set(0, mRow);
        }
    }

    private void setupPresenter() {
        if (mAdapter != null && mRow != null && mPresenter != null) {
            PresenterSelector selector = mAdapter.getPresenterSelector();
            if (selector == null) {
                selector = new ClassPresenterSelector();
                ((ClassPresenterSelector) selector).addClassPresenter(mRow.getClass(), mPresenter);
                mAdapter.setPresenterSelector(selector);
            } else if (selector instanceof ClassPresenterSelector) {
                ((ClassPresenterSelector) selector).addClassPresenter(mRow.getClass(), mPresenter);
            }
        }
    }

    static abstract class AnimatorListener implements Animator.AnimatorListener {
        ArrayList<View> mViews = new ArrayList<View>();
        ArrayList<Integer> mLayerType = new ArrayList<Integer>();

        @Override
        public void onAnimationCancel(Animator animation) {
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }

        @Override
        public void onAnimationStart(Animator animation) {
            getViews(mViews);
            for (View view : mViews) {
                mLayerType.add(view.getLayerType());
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            for (int i = 0; i < mViews.size(); i++) {
                mViews.get(i).setLayerType(mLayerType.get(i), null);
            }
            mLayerType.clear();
            mViews.clear();
        }

        abstract void getViews(ArrayList<View> views);
    }
}
