package com.d.music.play.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.d.lib.common.module.mvp.MvpView;
import com.d.lib.common.module.mvp.base.BaseActivity;
import com.d.lib.common.module.repeatclick.ClickUtil;
import com.d.lib.common.utils.Util;
import com.d.lib.common.utils.log.ULog;
import com.d.lib.common.view.dialog.AbsSheetDialog;
import com.d.music.App;
import com.d.music.R;
import com.d.music.common.Constants;
import com.d.music.common.preferences.Preferences;
import com.d.music.module.events.MusicInfoEvent;
import com.d.music.module.greendao.bean.MusicModel;
import com.d.music.module.greendao.db.AppDB;
import com.d.music.module.media.controler.MediaControler;
import com.d.music.module.media.controler.MediaPlayerManager;
import com.d.music.module.service.MusicService;
import com.d.music.module.utils.MoreUtil;
import com.d.music.play.adapter.PlayQueueAdapter;
import com.d.music.play.presenter.PlayPresenter;
import com.d.music.play.view.IPlayView;
import com.d.music.setting.activity.ModeActivity;
import com.d.music.utils.StatusBarCompat;
import com.d.music.view.dialog.OperationDialog;
import com.d.music.view.lrc.LrcRow;
import com.d.music.view.lrc.LrcView;
import com.d.music.view.popup.PlayQueuePopup;
import com.nineoldandroids.animation.Animator;
import com.nineoldandroids.animation.AnimatorListenerAdapter;
import com.nineoldandroids.animation.ObjectAnimator;
import com.nineoldandroids.animation.ValueAnimator;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.OnClick;

/**
 * PlayActivity
 * Created by D on 2017/4/29.
 */
public class PlayActivity extends BaseActivity<PlayPresenter> implements IPlayView,
        SeekBar.OnSeekBarChangeListener, PlayQueueAdapter.IQueueListener {
    @BindView(R.id.tv_title)
    TextView tvTitle;
    @BindView(R.id.lrcv_lrc)
    LrcView lrc;
    @BindView(R.id.iv_album)
    ImageView ivAlbum;
    @BindView(R.id.tv_time_start)
    TextView tvTimeStart;
    @BindView(R.id.tv_time_end)
    TextView tvTimeEnd;
    @BindView(R.id.sb_progress)
    SeekBar seekBar;

    @BindView(R.id.iv_play_collect)
    ImageView ivColect;
    @BindView(R.id.iv_play_play_pause)
    ImageView ivPlayPause;
    @BindView(R.id.iv_play_queue)
    ImageView ivPlayQueue;

    private int type = AppDB.MUSIC;
    private MediaControler control;
    private ObjectAnimator animator;
    private PlayQueuePopup queuePopup;
    private PlayerReceiver playerReceiver;
    private boolean isRegisterReceiver; // 是否注册了广播监听器
    public static boolean isNeedReLoad; // 为了同步收藏状态，需要重新加载数据

    public static void openActivity(Context context) {
        Intent intent = new Intent(context, PlayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        if (Constants.PlayerMode.mode == Constants.PlayerMode.PLAYER_MODE_NORMAL) {
            if (context instanceof Activity) {
                ((Activity) context).overridePendingTransition(R.anim.module_common_push_bottom_in, R.anim.module_common_push_stay);
            }
        }
    }

    @OnClick({R.id.iv_back, R.id.iv_more, R.id.iv_play_collect, R.id.iv_play_prev,
            R.id.iv_play_play_pause, R.id.iv_play_next, R.id.iv_play_queue})
    public void onClickListener(View v) {
        if (ClickUtil.isFastDoubleClick()) {
            return;
        }
        switch (v.getId()) {
            case R.id.iv_back:
                finish();
                break;
            case R.id.iv_more:
                showMore();
                break;
            case R.id.iv_play_collect:
                collect(false);
                break;
            case R.id.iv_play_prev:
                if (control.list().size() <= 0) {
                    return;
                }
                Intent prev = new Intent(Constants.PlayFlag.PLAYER_CONTROL_PREV);
                prev.putExtra("flag", Constants.PlayFlag.PLAY_FLAG_PRE);
                sendBroadcast(prev);
                break;
            case R.id.iv_play_play_pause:
                if (control.list().size() <= 0) {
                    return;
                }
                Intent playPause = new Intent(Constants.PlayFlag.PLAYER_CONTROL_PLAY_PAUSE);
                playPause.putExtra("flag", Constants.PlayFlag.PLAY_FLAG_PLAY_PAUSE);
                sendBroadcast(playPause);
                break;
            case R.id.iv_play_next:
                if (control.list().size() <= 0) {
                    return;
                }
                Intent next = new Intent(Constants.PlayFlag.PLAYER_CONTROL_NEXT);
                next.putExtra("flag", Constants.PlayFlag.PLAY_FLAG_NEXT);
                sendBroadcast(next);
                break;
            case R.id.iv_play_queue:
                showQueue();
                break;
        }
    }

    private void collect(boolean isTip) {
        if (control != null && control.getModel() != null) {
            MusicModel item = control.getModel();
            MoreUtil.collect(getApplicationContext(), type, item, isTip);
            resetFav(item.isCollected);
        }
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.module_play_activity_play;
    }

    @Override
    public PlayPresenter getPresenter() {
        return new PlayPresenter(getApplicationContext());
    }

    @Override
    protected MvpView getMvpView() {
        return this;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (App.toFinish(intent)) {
            finish();
        }
    }

    @Override
    protected void init() {
        if (App.toFinish(getIntent())) {
            finish();
            return;
        }
        StatusBarCompat.compat(this, Color.parseColor("#ff000000"));
        EventBus.getDefault().register(this);
        registerReceiver();
        control = MediaControler.getIns(getApplicationContext());
        seekBar.setOnSeekBarChangeListener(this);
        initLrcListener();
        onPlayModeChange(Preferences.getIns(getApplicationContext()).getPlayMode());
        initAlbum();
    }

    private void initAlbum() {
        tvTitle.setText(control.getSongName());
        MediaPlayerManager mediaManager = control.getMediaManager();
        final int status = control.getStatus();
        if (mediaManager != null && (status == Constants.PlayStatus.PLAY_STATUS_PLAYING
                || status == Constants.PlayStatus.PLAY_STATUS_PAUSE)) {
            final int duration = mediaManager.getDuration();
            final int currentPosition = mediaManager.getCurrentPosition();
            setProgress(currentPosition, duration);
            tvTimeStart.setText(Util.formatTime(currentPosition));
            MusicModel model = control.getModel();
            resetFav(model != null ? model.isCollected : false);
            mPresenter.getLrcRows(model);
        } else {
            setProgress(0, 0);
        }

        if (status == Constants.PlayStatus.PLAY_STATUS_PLAYING) {
            // 正在播放
            ivPlayPause.setImageResource(R.drawable.module_play_ic_play_pause);
            rotationAnimator();
        } else {
            // 无列表播放/暂停
            ivPlayPause.setImageResource(R.drawable.module_play_ic_play_play);
            if (animator != null && animator.isRunning()) {
                animator.cancel();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isNeedReLoad) {
            isNeedReLoad = false;
            mPresenter.reLoad();
        }
    }

    private void resetFav(boolean isCollected) {
        int fav = isCollected ? R.drawable.module_play_ic_play_fav_cover : R.drawable.module_play_ic_play_fav;
        ivColect.setImageDrawable(getResources().getDrawable(fav));
    }

    private void initLrcListener() {
        lrc.setOnSeekChangeListener(new LrcView.OnSeekChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                if (progress >= 0 && progress <= seekBar.getMax()) {
                    seekBar.setProgress(progress);
                }
                control.seekTo(progress);
            }
        });
    }

    private void showQueue() {
        if (queuePopup == null) {
            queuePopup = new PlayQueuePopup(mActivity);
            queuePopup.setOnQueueListener(this);
        }
        queuePopup.show();
    }

    @SuppressWarnings("unused")
    private void dismissQueue() {
        if (queuePopup != null) {
            queuePopup.dismiss();
        }
    }

    private void showMore() {
        final MusicModel item = control.getModel();
        final List<OperationDialog.Bean> datas = new ArrayList<>();
        datas.add(new OperationDialog.Bean().with(mContext, OperationDialog.Bean.TYPE_ADDLIST, true));
        datas.add(new OperationDialog.Bean().with(mContext, OperationDialog.Bean.TYPE_FAV, true)
                .item(item != null && item.isCollected ? getResources().getString(R.string.module_common_collected)
                        : getResources().getString(R.string.module_common_collect)));
        datas.add(new OperationDialog.Bean().with(mContext, OperationDialog.Bean.TYPE_INFO, true));
        if (Constants.PlayerMode.mode == Constants.PlayerMode.PLAYER_MODE_MINIMALIST) {
            datas.add(new OperationDialog.Bean(OperationDialog.Bean.TYPE_CHANGE_MODE,
                    getResources().getString(R.string.module_common_mode_switch), R.drawable.module_common_ic_song_edit_m));
            datas.add(new OperationDialog.Bean(OperationDialog.Bean.TYPE_EXIT,
                    getResources().getString(R.string.module_common_exit), R.drawable.module_setting_ic_menu_exit));
        }
        OperationDialog.getOperationDialog(mContext, OperationDialog.TYPE_NIGHT, "", datas,
                new AbsSheetDialog.OnItemClickListener<OperationDialog.Bean>() {
                    @Override
                    public void onClick(Dialog dlg, int position, OperationDialog.Bean bean) {
                        if (bean.type == OperationDialog.Bean.TYPE_ADDLIST) {
                            MoreUtil.addToList(mContext, type, item);
                        } else if (bean.type == OperationDialog.Bean.TYPE_FAV) {
                            collect(true);
                        } else if (bean.type == OperationDialog.Bean.TYPE_INFO) {
                            MoreUtil.showInfo(mContext, item);
                        } else if (bean.type == OperationDialog.Bean.TYPE_CHANGE_MODE) {
                            startActivity(new Intent(mContext, ModeActivity.class));
                        } else if (bean.type == OperationDialog.Bean.TYPE_EXIT) {
                            App.exit(mContext);
                        }
                    }

                    @Override
                    public void onCancel(Dialog dlg) {

                    }
                });
    }

    private void registerReceiver() {
        // 定义和注册广播接收器
        playerReceiver = new PlayerReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.PlayFlag.MUSIC_CURRENT_POSITION);
        registerReceiver(playerReceiver, filter);
        isRegisterReceiver = true;
    }

    private void rotationAnimator() {
        animator = ObjectAnimator.ofFloat(ivAlbum, "rotation", 0f, 360f);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setDuration(5000);
        animator.start();
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                // rotationAnimator();
            }
        });
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void onProgressChanged(SeekBar seekBar, final int progress, boolean fromUser) {
        // 数值改变
        tvTimeStart.setText(String.format("%02d:%02d", progress / 1000 / 60, progress / 1000 % 60));
        lrc.seekTo(progress, fromUser);
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        // 开始拖动
        MusicService.progressLock = true; // 加锁
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        // 停止拖动
        progressChanged(seekBar.getProgress());
        lrc.seekTo(seekBar.getProgress(), true);
        ULog.v("to：" + seekBar.getProgress());
    }

    /**
     * 播放进度改变
     */
    public void progressChanged(int progress) {
        if (control == null || control.list().size() <= 0) {
            MusicService.progressLock = false; // 解锁
            return;
        }
        ULog.v("to:--" + progress);
        Intent intent = new Intent();
        intent.setAction(Constants.PlayFlag.MUSIC_SEEK_TO_TIME);
        intent.putExtra("progress", progress);
        sendBroadcast(intent);
    }

    @Override
    public void onPlayModeChange(int playMode) {
        if (ivPlayQueue != null) {
            ivPlayQueue.setImageResource(Constants.PlayMode.PLAY_MODE_DRAWABLE[playMode]);
        }
    }

    @Override
    public void onCountChange(int count) {
        if (count <= 0) {
            setProgress(0, 0);
            tvTimeStart.setText(Util.formatTime(0));
            lrc.setLrcRows(new ArrayList<LrcRow>());
        }
    }

    @Override
    public void reLoad(List<MusicModel> list) {
        if (list.size() > 0) {
            MediaControler control = MediaControler.getIns(mContext);
            control.overLoad(list);
            MusicModel model = control.list().get(control.getPosition());
            resetFav(model != null ? model.isCollected : false);
        }
    }

    @Override
    public void setLrcRows(String path, List<LrcRow> lrcRows) {
        lrc.setLrcRows(lrcRows);
        lrc.seekTo(1000, true);
    }

    @Override
    public void seekTo(int progress) {
        lrc.seekTo(progress, false);
    }

    /**
     * 用来接收从service传回来的广播的内部类
     */
    public class PlayerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || isFinishing() || mPresenter == null || !mPresenter.isViewAttached()) {
                return;
            }
            if (TextUtils.equals(intent.getAction(), Constants.PlayFlag.MUSIC_CURRENT_POSITION)) {
                int currentPosition = intent.getIntExtra("currentPosition", 0);
                int duration = intent.getIntExtra("duration", 0);
                tvTimeStart.setText(Util.formatTime(currentPosition));
                setProgress(currentPosition, duration);
                lrc.seekTo(currentPosition, false);
            }
        }
    }

    private void setProgress(int currentPosition, int duration) {
        tvTimeEnd.setText(Util.formatTime(duration));
        seekBar.setMax(duration);
        seekBar.setProgress(currentPosition);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onEventMainThread(MusicInfoEvent event) {
        if (event == null || isFinishing() || mPresenter == null || !mPresenter.isViewAttached()) {
            return;
        }
        MusicModel model = control.getModel();
        tvTitle.setText(model != null ? model.songName : "");
        resetFav(model != null ? model.isCollected : false);
        mPresenter.getLrcRows(model);
        togglePlay(model != null && event.status == Constants.PlayStatus.PLAY_STATUS_PLAYING);
    }

    private void togglePlay(boolean isPlay) {
        if (isPlay) {
            ivPlayPause.setImageResource(R.drawable.module_play_ic_play_pause);
            rotationAnimator();
        } else {
            ivPlayPause.setImageResource(R.drawable.module_play_ic_play_play);
            if (animator != null && animator.isRunning()) {
                animator.cancel();
            }
        }
    }

    @Override
    public void finish() {
        super.finish();
        if (Constants.PlayerMode.mode == Constants.PlayerMode.PLAYER_MODE_NORMAL) {
            overridePendingTransition(R.anim.module_common_push_stay, R.anim.module_common_push_bottom_out);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        if (isRegisterReceiver) {
            isRegisterReceiver = false;
            if (playerReceiver != null) {
                unregisterReceiver(playerReceiver);
            }
        }
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
