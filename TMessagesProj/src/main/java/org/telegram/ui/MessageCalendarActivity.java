package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ChatPreview;
import org.telegram.ui.Components.ForwardingPreviewView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SharedMediaLayout;

import java.text.SimpleDateFormat;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class MessageCalendarActivity extends BaseFragment {

    private final ForwardingPreviewView.ResourcesDelegate delegate;
    private int initialDate;
    private Paint hollow;
    FrameLayout contentView;

    RecyclerListView listView;
    Button bottomBtn;

    LinearLayoutManager layoutManager;
    TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    TextPaint activeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    TextPaint textPaint2 = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    Paint blackoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    RectF rect = new RectF();
    private ValueAnimator initialDateAnimator;
    private ValueAnimator riseAnimator;

    private long dialogId;
    private boolean loading;
    private boolean checkEnterItems;


    int startFromYear;
    int startFromMonth;
    int monthCount;

    CalendarAdapter adapter;
    Callback callback;


    SparseArray<SparseArray<PeriodDay>> messagesByYearMounth = new SparseArray<>();
    boolean endReached;
    int startOffset = 0;
    int lastId;
    int minMontYear;
    private int photosVideosTypeFilter;
    private boolean isOpened;
    int selectedYear;
    int selectedMonth;
    int _timeLimit = (int) (System.currentTimeMillis()/1000L);
    private boolean isSelectingDays;
    private SelectionHelper selectedDays;

    private ChatActivity chatActivity;

    public MessageCalendarActivity(Bundle args, ForwardingPreviewView.ResourcesDelegate delegate, int photosVideosTypeFilter, int selectedDate) {
        super(args);
        this.delegate = delegate;
        this.photosVideosTypeFilter = photosVideosTypeFilter;

        this.initialDate = selectedDate;
        if (selectedDate != 0) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(selectedDate * 1000L);
            selectedYear = calendar.get(Calendar.YEAR);
            selectedMonth = calendar.get(Calendar.MONTH);
        }
    }

    @Override
    public boolean onFragmentCreate() {
        dialogId = getArguments().getLong("dialog_id");
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        textPaint.setTextSize(AndroidUtilities.dp(16));
        textPaint.setTextAlign(Paint.Align.CENTER);

        textPaint2.setTextSize(AndroidUtilities.dp(11));
        textPaint2.setTextAlign(Paint.Align.CENTER);
        textPaint2.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));

        activeTextPaint.setTextSize(AndroidUtilities.dp(16));
        activeTextPaint.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        activeTextPaint.setTextAlign(Paint.Align.CENTER);

        hollow = new Paint(Paint.ANTI_ALIAS_FLAG);
        hollow.setStyle(Paint.Style.STROKE);
        hollow.setColor(Theme.getColor(Theme.key_windowBackgroundChecked));
        hollow.setStrokeWidth(6);

        contentView = new FrameLayout(context);
        createActionBar(context);
        contentView.addView(actionBar);
        actionBar.setTitle(LocaleController.getString("Calendar", R.string.Calendar));
        actionBar.setCastShadows(false);


        listView = new RecyclerListView(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                checkEnterItems = false;
            }
        };
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context));
        layoutManager.setReverseLayout(true);
        listView.setAdapter(adapter = new CalendarAdapter());
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                checkLoadNext();
            }
        });

        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 0, 36, 0, 48));

        FrameLayout btnWrapper = new FrameLayout(context);
        btnWrapper.setBackgroundColor(ColorUtils.setAlphaComponent(Color.GRAY, 50));

        bottomBtn = new Button(context);
        bottomBtn.setText(LocaleController.getString("SelectDays", R.string.SelectDays));
        bottomBtn.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bottomBtn.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));

        bottomBtn.setOnClickListener(v -> {
            if (isSelectingDays) {
                if (selectedDays == null) {
                    actionBar.setTitle(LocaleController.getString("CalendarTapToSelect", R.string.CalendarTapToSelect));
                    bottomBtn.setText(LocaleController.getString("Cancel", R.string.Cancel));
                    isSelectingDays = false;
                    actionBar.setBackButtonImage(R.drawable.ic_ab_back);
                    actionBar.setTitle(LocaleController.getString("Calendar", R.string.Calendar));
                    bottomBtn.setText(LocaleController.getString("SelectDays", R.string.SelectDays));
                    return;
                }
                removeSelected(_timeLimit);

            } else {
                //startSelectMode();
                isSelectingDays = true;
                actionBar.setTitle(LocaleController.getString("CalendarTapToSelect", R.string.CalendarTapToSelect));
                actionBar.setBackButtonImage(R.drawable.ic_close_white);
                bottomBtn.setText(LocaleController.getString("Cancel", R.string.Cancel));
            }
        });


        btnWrapper.addView(bottomBtn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48,
                0, 0.4f, 0.4f, 0.4f, 0.1f));

        contentView.addView(btnWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER | Gravity.BOTTOM, 15, 5, 15, 5));


        final String[] daysOfWeek = new String[]{
                LocaleController.getString("CalendarWeekNameShortMonday", R.string.CalendarWeekNameShortMonday),
                LocaleController.getString("CalendarWeekNameShortTuesday", R.string.CalendarWeekNameShortTuesday),
                LocaleController.getString("CalendarWeekNameShortWednesday", R.string.CalendarWeekNameShortWednesday),
                LocaleController.getString("CalendarWeekNameShortThursday", R.string.CalendarWeekNameShortThursday),
                LocaleController.getString("CalendarWeekNameShortFriday", R.string.CalendarWeekNameShortFriday),
                LocaleController.getString("CalendarWeekNameShortSaturday", R.string.CalendarWeekNameShortSaturday),
                LocaleController.getString("CalendarWeekNameShortSunday", R.string.CalendarWeekNameShortSunday),
        };

        Drawable headerShadowDrawable = ContextCompat.getDrawable(context, R.drawable.header_shadow).mutate();

        View calendarSignatureView = new View(context) {

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float xStep = getMeasuredWidth() / 7f;
                for (int i = 0; i < 7; i++) {
                    float cx = xStep * i + xStep / 2f;
                    float cy = (getMeasuredHeight() - AndroidUtilities.dp(2)) / 2f;
                    canvas.drawText(daysOfWeek[i], cx, cy + AndroidUtilities.dp(5), textPaint2);
                }
                headerShadowDrawable.setBounds(0, getMeasuredHeight() - AndroidUtilities.dp(3), getMeasuredWidth(), getMeasuredHeight());
                headerShadowDrawable.draw(canvas);
            }
        };

        contentView.addView(calendarSignatureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, 0, 0, 0, 0, 0));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (isSelectingDays) {
                        stopSelectMode();
                    } else {
                        if (ChatPreview.isVisible()) {
                            ChatPreview.clear();
                        } else {
                            finishFragment();
                        }
                    }
                }
            }
        });

        fragmentView = contentView;

        Calendar calendar = Calendar.getInstance();
        startFromYear = calendar.get(Calendar.YEAR);
        startFromMonth = calendar.get(Calendar.MONTH);

        if (selectedYear != 0) {
            monthCount = (startFromYear - selectedYear) * 12 + startFromMonth - selectedMonth + 1;
            layoutManager.scrollToPositionWithOffset(monthCount - 1, AndroidUtilities.dp(120));
        }
        if (monthCount < 3) {
            monthCount = 3;
        }

        loadNext();
        updateColors();
        activeTextPaint.setColor(Color.WHITE);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        return fragmentView;
    }


    public void removeSelected(int year, int month, int day, int timeLimit) {

        selectedDays = new SelectionHelper(year, month, day);
        _timeLimit = timeLimit;
        removeSelected(timeLimit);
    }

    public void removeSelected(int timeLimit) {
        if (selectedDays == null || chatActivity == null) {
            return;
        }

        Calendar calendar = Calendar.getInstance();
        long currentTime = calendar.getTimeInMillis();

        calendar.set(selectedDays.start_year, selectedDays.start_month, selectedDays.start_day, 0, 0, 0);
        int min_date = (int) (calendar.getTimeInMillis() / 1000);

        calendar.set(selectedDays.end_year, selectedDays.end_month, selectedDays.end_day, 0, 0, 0);
        calendar.add(Calendar.DATE, 1);

        long max = calendar.getTimeInMillis();
        max = currentTime < calendar.getTimeInMillis() ? currentTime : max;

        if(timeLimit > 0){
            max = Math.min(timeLimit*1000L,max);
        }
        int max_date = (int) (max / 1000);



        AlertsCreator.createDeleteMessagesRangeAlert(this, chatActivity.currentUser,
                selectedDays.getSelectedCount(), revoke -> {
                    MessagesController.getInstance(currentAccount).deleteMessagesInDaysRange(dialogId, min_date, max_date, revoke, (success) -> {
                        for (int i = 0; i < messagesByYearMounth.size(); ++i) {
                            int key = messagesByYearMounth.keyAt(i);
                            SparseArray<PeriodDay> month = messagesByYearMounth.get(key);

                            for (int j = 0; j < month.size(); ++j) {
                                key = month.keyAt(j);
                                PeriodDay day = month.get(key);
                                long date = day.messageObject.messageOwner.date;
                                calendar.setTimeInMillis((date) * 1000L);

                                if (selectedDays.isDaySelected(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DATE))) {
                                    month.remove(key);
                                }
                            }
                        }
                        AndroidUtilities.runOnUIThread(() -> {
                            ChatPreview.clear();
                            listView.getAdapter().notifyDataSetChanged();
                            stopSelectMode();
                        });

                    });
                }, null, getResourceProvider());
    }

    private void updateColors() {
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        activeTextPaint.setColor(Color.WHITE);
        textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textPaint2.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_listSelector), false);
    }

    private void loadNext() {
        if (loading || endReached) {
            return;
        }
        loading = true;
        TLRPC.TL_messages_getSearchResultsCalendar req = new TLRPC.TL_messages_getSearchResultsCalendar();
        if (photosVideosTypeFilter == SharedMediaLayout.FILTER_PHOTOS_ONLY) {
            req.filter = new TLRPC.TL_inputMessagesFilterPhotos();
        } else if (photosVideosTypeFilter == SharedMediaLayout.FILTER_VIDEOS_ONLY) {
            req.filter = new TLRPC.TL_inputMessagesFilterVideo();
        } else {
            req.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
        }

        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.offset_id = lastId;

        Calendar calendar = Calendar.getInstance();

        listView.setItemAnimator(null);
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error == null) {
                TLRPC.TL_messages_searchResultsCalendar res = (TLRPC.TL_messages_searchResultsCalendar) response;

                for (int i = 0; i < res.periods.size(); i++) {
                    TLRPC.TL_searchResultsCalendarPeriod period = res.periods.get(i);
                    //TODO server returns wrong date: trueDate - 1, and always same time == FIXED ON SERVER?
                   // calendar.setTimeInMillis((period.date + 86400) * 1000L);
                    calendar.setTimeInMillis((period.date ) * 1000L);
                    int month = calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH);
                    SparseArray<PeriodDay> messagesByDays = messagesByYearMounth.get(month);
                    if (messagesByDays == null) {
                        messagesByDays = new SparseArray<>();
                        messagesByYearMounth.put(month, messagesByDays);
                    }
                    PeriodDay periodDay = new PeriodDay();
                    periodDay.messageObject = new MessageObject(currentAccount, res.messages.get(i), false, false);
                    startOffset += res.periods.get(i).count;
                    periodDay.startOffset = startOffset;
                    int index = calendar.get(Calendar.DAY_OF_MONTH) - 1;
                    if (messagesByDays.get(index, null) == null) {
                        messagesByDays.put(index, periodDay);
                    }
                    if (month < minMontYear || minMontYear == 0) {
                        minMontYear = month;
                    }

                }

                loading = false;
                if (!res.messages.isEmpty()) {
                    lastId = res.messages.get(res.messages.size() - 1).id;
                    endReached = false;
                    checkLoadNext();
                } else {
                    endReached = true;
                }
                if (isOpened) {
                    checkEnterItems = true;
                }
                listView.invalidate();
                int newMonthCount = (int) (((calendar.getTimeInMillis() / 1000) - res.min_date) / 2629800) + 1;
                adapter.notifyItemRangeChanged(0, monthCount);
                if (newMonthCount > monthCount) {
                    adapter.notifyItemRangeInserted(monthCount + 1, newMonthCount);
                    monthCount = newMonthCount;
                }
                if (endReached) {
                    resumeDelayedFragmentAnimation();
                }
            }
        }));
    }

    private void checkLoadNext() {
        if (loading || endReached) {
            return;
        }
        int listMinMonth = Integer.MAX_VALUE;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof MonthView) {
                int currentMonth = ((MonthView) child).currentYear * 100 + ((MonthView) child).currentMonthInYear;
                if (currentMonth < listMinMonth) {
                    listMinMonth = currentMonth;
                }
            }
        }

        int min1 = (minMontYear / 100 * 12) + minMontYear % 100;
        int min2 = (listMinMonth / 100 * 12) + listMinMonth % 100;
        if (min1 + 3 >= min2) {
            loadNext();
        }
    }


    @Override
    public boolean onBackPressed() {
        if (ChatPreview.isVisible()) {
            ChatPreview.clear();
            return false;
        } else {
            return super.onBackPressed();
        }
    }

    public void jumpToDate(int year, int month, int day) {
        if (callback != null) {
            callback.onDateSelected(year, month, day);
        }
        ChatPreview.clear();
        finishFragment();
    }

    public void selectDays(int year, int month, int day) {
        ChatPreview.clear();
        isSelectingDays = true;
        selectedDays = new SelectionHelper(year, month, day);
        startSelectMode();
        actionBar.setTitle(LocaleController.formatPluralString("DaysSchedule", 1));
    }

    private void startSelectMode() {
        isSelectingDays = true;

        actionBar.setTitle(selectedDays != null ?
                LocaleController.formatPluralString("DaysSchedule", selectedDays.getSelectedCount()) :
                LocaleController.getString("CalendarTapToSelect", R.string.CalendarTapToSelect));

        actionBar.setBackButtonImage(R.drawable.ic_close_white);
        bottomBtn.setText(LocaleController.getString("ClearHistory", R.string.ClearHistory));
        bottomBtn.setTextColor(Theme.getColor(Theme.key_dialogTextRed2));

        invalidateAll();
    }

    private void stopSelectMode() {
        isSelectingDays = false;
        selectedDays = null;
        bottomBtn.setText(LocaleController.getString("SelectDays", R.string.SelectDays));
        bottomBtn.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString("Calendar", R.string.Calendar));

        invalidateAll();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        ChatPreview.clear();
    }

    public void setChat(ChatActivity chatActivity) {
        this.chatActivity = chatActivity;
    }

    public ChatActivity getChatActivity() {
        return chatActivity;
    }

    private class SelectionHelper {
        int start_year;
        int start_month;
        int start_day;

        int end_year;
        int end_month;
        int end_day;

        public int startSum;
        public int endSum;
        public int prevSum;
        private boolean lastChangedStart;


        public SelectionHelper(int start_year, int start_month, int start_day) {
//            setEndDate(start_year, start_month, start_day);
//            setStartDate(start_year, start_month, start_day);
            this.start_year = start_year;
            this.start_month = start_month;
            this.start_day = start_day;
            this.startSum = sum(start_year, start_month, start_day);

            this.end_year = start_year;
            this.end_month = start_month;
            this.end_day = start_day;
            this.endSum = sum(start_year, start_month, start_day);

            startRiseAnimator();
        }

        private int sum(int year, int month, int day) {
            return year * 1000 + month * 35 + day;
        }

        public boolean isSingle() {
            return startSum == endSum;
        }

        public int getSelectedCount() {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
                Date start = sdf.parse(start_day + "/" + (start_month + 1) + "/" + start_year);
                Date end = sdf.parse(end_day + "/" + (end_month + 1) + "/" + end_year);
                return (int) TimeUnit.DAYS.convert(Math.abs(end.getTime() - start.getTime()), TimeUnit.MILLISECONDS) + 1;
            } catch (Exception ex) {
                return 0;
            }
        }

        public void setStartDate(int year, int month, int day) {
            prevSum = startSum;
            lastChangedStart = true;
            this.start_year = year;
            this.start_month = month;
            this.start_day = day;
            this.startSum = sum(year, month, day);
            startRiseAnimator();
        }


        public void setEndDate(int year, int month, int day) {
            if (year < start_year || (year == start_year && month < start_month) || (year == start_year && month == start_month && day <= start_day)) {
                setStartDate(year, month, day);
                return;
            }

//            float contains = selectedAnimationProgress.get(endSum,Float.MIN_VALUE);
//            if(contains != Float.MIN_VALUE){
//                selectedAnimationProgress.setValueAt(endSum,contains*-1);
//            }
//
//            AnimationSet s = new AnimationSet();

            prevSum = endSum;
            lastChangedStart = false;
            this.end_year = year;
            this.end_month = month;
            this.end_day = day;
            this.endSum = sum(year, month, day);
            startRiseAnimator();
            //selectedAnimationProgress.setValueAt(endSum,0.01f);
        }

        private void startRiseAnimator() {


            if (riseAnimator != null) {
                riseAnimator.removeAllUpdateListeners();
//                riseAnimator.cancel();
                riseAnimator.end();
                riseAnimator = null;
//                riseAnimator.removeAllListeners();
             //  riseAnimator.removeAllUpdateListeners();
            }


            riseAnimator = ValueAnimator.ofFloat(0, 1f);
//            riseAnimator.addListener(new AnimatorListenerAdapter() {
//                @Override
//                public void onAnimationEnd(Animator animation) {
//                    super.onAnimationEnd(animation);
//                //    riseAnimator = null;
//                }
//            });
            riseAnimator.addUpdateListener(animation -> invalidateAll());
            riseAnimator.setDuration(220);
            riseAnimator.start();

        }


        public boolean isStart(int year, int month, int day) {
            return (year == start_year && month == start_month && day == start_day);
        }

        public boolean isStartOrEnd(int year, int month, int day) {
            return (year == start_year && month == start_month && day == start_day)
                    || (year == end_year && month == end_month && day == end_day);
        }

//        public boolean isMonthSelected(int year, int month) {
//            int nowSum = sum(year, month, 0);
//            int sSum = sum(start_year, start_month, 0);
//            return nowSum >= startSum && nowSum <= endSum;
//        }

        public boolean isDaySelected(int year, int month, int day) {
            int nowSum = sum(year, month, day);
            return nowSum >= startSum && nowSum <= endSum;
        }

        public boolean isEnd(int year, int month, int day) {
            return (year == end_year && month == end_month && day == end_day);
        }
    }

    private class CalendarAdapter extends RecyclerView.Adapter {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new MonthView(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            MonthView monthView = (MonthView) holder.itemView;

            int year = startFromYear - position / 12;
            int month = startFromMonth - position % 12;
            if (month < 0) {
                month += 12;
                year--;
            }
            boolean animated = monthView.currentYear == year && monthView.currentMonthInYear == month;
            monthView.setDate(year, month, messagesByYearMounth.get(year * 100 + month), animated);
        }

        @Override
        public long getItemId(int position) {
            int year = startFromYear - position / 12;
            int month = startFromMonth - position % 12;
            return year * 100L + month;
        }

        @Override
        public int getItemCount() {
            return monthCount;
        }
    }


    private class MonthView extends FrameLayout {

        SimpleTextView titleView;
        int currentYear;
        int currentMonthInYear;
        int daysInMonth;
        int startDayOfWeek;
        int cellCount;
        int startMonthTime;

        float pressedX;
        float pressedY;

        SparseArray<PeriodDay> messagesByDays = new SparseArray<>();
        SparseArray<ImageReceiver> imagesByDays = new SparseArray<>();

        SparseArray<PeriodDay> animatedFromMessagesByDays = new SparseArray<>();
        SparseArray<ImageReceiver> animatedFromImagesByDays = new SparseArray<>();

        boolean attached;
        float animationProgress = 1f;
        private GestureDetector gestureDetector;

        private int animatedDay;

        public MonthView(Context context) {
            super(context);
            setWillNotDraw(false);
            titleView = new SimpleTextView(context);
            titleView.setTextSize(15);
            titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            titleView.setGravity(Gravity.CENTER);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 28, 0, 0, 12, 0, 4));


            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                public void onLongPress(MotionEvent e) {
                    float xStep = getMeasuredWidth() / 7f;
                    float yStep = AndroidUtilities.dp(44 + 8);
                    RectF rect = new RectF();
                    int currentRow = 0;
                    int currentColumn = startDayOfWeek;
                    int nowTime = (int) (System.currentTimeMillis() / 1000L);
                    int day;
                    float cx, cy;

                    for (int i = 0; i < daysInMonth; i++) {
                        cx = xStep * currentColumn;
                        cy = yStep * currentRow + AndroidUtilities.dp(44);
                        day = i + 1;
                        rect.set(cx, cy, cx + xStep, cy + yStep);


                        if (rect.contains(pressedX, pressedY)) {
                            if (nowTime < startMonthTime + (day) * 86400) {
                                //disabled cells.
                                break;
                            }
                            ChatPreview.create(getContext(), MessageCalendarActivity.this, delegate, dialogId, currentYear, currentMonthInYear, day);
                            stopSelectMode();
                            break;
                        }

                        currentColumn++;
                        if (currentColumn >= 7) {
                            currentColumn = 0;
                            currentRow++;
                        }
                    }
                    super.onLongPress(e);
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                   return onSingleTapUp(e);
                }

                @Override
                public boolean onDown(MotionEvent e) {
                    pressedX = e.getX();
                    pressedY = e.getY();
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    float xStep = getMeasuredWidth() / 7f;
                    float yStep = AndroidUtilities.dp(44 + 8);
                    RectF rect = new RectF();
                    int currentRow = 0;
                    int currentColumn = startDayOfWeek;
                    int nowTime = (int) (System.currentTimeMillis() / 1000L);
                    float cx, cy;
                    int day;

                    for (int i = 0; i < daysInMonth; i++) {
                        cx = xStep * currentColumn;
                        cy = yStep * currentRow + AndroidUtilities.dp(44);
                        day = i + 1;
                        rect.set(cx, cy, cx + xStep, cy + yStep);

                        if (rect.contains(pressedX, pressedY)) {
                            if (nowTime < startMonthTime + (day) * 86400) {
                                //disabled cells show shake animation.
                                break;
                            }

                            if (isSelectingDays) {
                                if (selectedDays == null) {
                                    selectedDays = new SelectionHelper(currentYear, currentMonthInYear, day);
                                    startSelectMode();
                                } else {
                                    selectedDays.setEndDate(currentYear, currentMonthInYear, day);
                                    actionBar.setTitle(LocaleController.formatPluralString("DaysSchedule", selectedDays.getSelectedCount()));
                                }
                                //invalidateAll();
                            } else {
                                jumpToDate(currentYear, currentMonthInYear, day);
                            }
                            break;
                        }

                        currentColumn++;
                        if (currentColumn >= 7) {
                            currentColumn = 0;
                            currentRow++;
                        }
                    }
                    return true;
                }
            });
        }

        public void setDate(int year, int monthInYear, SparseArray<PeriodDay> messagesByDays, boolean animated) {
            boolean dateChanged = year != currentYear && monthInYear != currentMonthInYear;
            currentYear = year;
            currentMonthInYear = monthInYear;
            this.messagesByDays = messagesByDays;

            if (dateChanged) {
                if (imagesByDays != null) {
                    for (int i = 0; i < imagesByDays.size(); i++) {
                        imagesByDays.valueAt(i).onDetachedFromWindow();
                        imagesByDays.valueAt(i).setParentView(null);
                    }
                    imagesByDays = null;
                }
            }
            if (messagesByDays != null) {
                if (imagesByDays == null) {
                    imagesByDays = new SparseArray<>();
                }

                for (int i = 0; i < messagesByDays.size(); i++) {
                    int key = messagesByDays.keyAt(i);
                    if (imagesByDays.get(key, null) != null) {
                        continue;
                    }
                    ImageReceiver receiver = new ImageReceiver();
                    receiver.setParentView(this);
                    PeriodDay periodDay = messagesByDays.get(key);
                    MessageObject messageObject = periodDay.messageObject;
                    if (messageObject != null) {
                        if (messageObject.isVideo()) {
                            TLRPC.Document document = messageObject.getDocument();
                            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 50);
                            TLRPC.PhotoSize qualityThumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320);
                            if (thumb == qualityThumb) {
                                qualityThumb = null;
                            }
                            if (thumb != null) {
                                if (messageObject.strippedThumb != null) {
                                    receiver.setImage(ImageLocation.getForDocument(qualityThumb, document), "44_44", messageObject.strippedThumb, null, messageObject, 0);
                                } else {
                                    receiver.setImage(ImageLocation.getForDocument(qualityThumb, document), "44_44", ImageLocation.getForDocument(thumb, document), "b", (String) null, messageObject, 0);
                                }
                            }
                        } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto && messageObject.messageOwner.media.photo != null && !messageObject.photoThumbs.isEmpty()) {
                            TLRPC.PhotoSize currentPhotoObjectThumb = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 50);
                            TLRPC.PhotoSize currentPhotoObject = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 320, false, currentPhotoObjectThumb, false);
                            if (messageObject.mediaExists || DownloadController.getInstance(currentAccount).canDownloadMedia(messageObject)) {
                                if (currentPhotoObject == currentPhotoObjectThumb) {
                                    currentPhotoObjectThumb = null;
                                }
                                if (messageObject.strippedThumb != null) {
                                    receiver.setImage(ImageLocation.getForObject(currentPhotoObject, messageObject.photoThumbsObject), "44_44", null, null, messageObject.strippedThumb, currentPhotoObject != null ? currentPhotoObject.size : 0, null, messageObject, messageObject.shouldEncryptPhotoOrVideo() ? 2 : 1);
                                } else {
                                    receiver.setImage(ImageLocation.getForObject(currentPhotoObject, messageObject.photoThumbsObject), "44_44", ImageLocation.getForObject(currentPhotoObjectThumb, messageObject.photoThumbsObject), "b", currentPhotoObject != null ? currentPhotoObject.size : 0, null, messageObject, messageObject.shouldEncryptPhotoOrVideo() ? 2 : 1);
                                }
                            } else {
                                if (messageObject.strippedThumb != null) {
                                    receiver.setImage(null, null, messageObject.strippedThumb, null, messageObject, 0);
                                } else {
                                    receiver.setImage(null, null, ImageLocation.getForObject(currentPhotoObjectThumb, messageObject.photoThumbsObject), "b", (String) null, messageObject, 0);
                                }
                            }
                        }
                        receiver.setRoundRadius(AndroidUtilities.dp(22));
                        imagesByDays.put(key, receiver);
                    }
                }
            }

            YearMonth yearMonthObject = YearMonth.of(year, monthInYear + 1);
            daysInMonth = yearMonthObject.lengthOfMonth();

            Calendar calendar = Calendar.getInstance();

            calendar.set(year, monthInYear, 0);
            startDayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 6) % 7;
            startMonthTime = (int) (calendar.getTimeInMillis() / 1000L);

            int totalColumns = daysInMonth + startDayOfWeek;
            cellCount = (int) (totalColumns / 7f) + (totalColumns % 7 == 0 ? 0 : 1);
            calendar.set(year, monthInYear + 1, 0);
            titleView.setText(LocaleController.formatYearMont(calendar.getTimeInMillis() / 1000, true));

            if (initialDate > 0) {
                calendar.setTimeInMillis(initialDate * 1000L);
                if (calendar.get(Calendar.YEAR) == year && calendar.get(Calendar.MONTH) == monthInYear) {
                    animatedDay = calendar.get(Calendar.DATE);
                    initialDateAnimator = ValueAnimator.ofFloat(0f, 1f);
                    initialDateAnimator.setDuration(2000);
                    initialDateAnimator.addUpdateListener(animation -> invalidate());
                    initialDateAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationCancel(Animator animation) {
                            super.onAnimationCancel(animation);
                            initialDate = 0;
                            initialDateAnimator = null;
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            initialDate = 0;
                            initialDateAnimator = null;
                        }
                    });
                    initialDateAnimator.start();

                }
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(cellCount * (44 + 8) + 44), MeasureSpec.EXACTLY));
        }


        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return gestureDetector.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int currentCell = 0;
            int currentColumn = startDayOfWeek;
            selectedPaint.setColor(Theme.getColor(Theme.key_windowBackgroundChecked));


            int selectionColorLight = ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundChecked), Theme.isCurrentThemeDay() ? Color.WHITE : Color.BLACK, 0.8f);
            int selectionColorDark = Theme.getColor(Theme.key_windowBackgroundChecked);
            int selectionPadding = 7;
            int day;
            float cx, cy, x, y;
            boolean isDaySelected;

            float xStep = getMeasuredWidth() / 7f;
            float yStep = AndroidUtilities.dp(44 + 8);
            float rectPadding = 10;
            int nowTime = (int) (System.currentTimeMillis() / 1000L);

            for (int i = 0; i < daysInMonth; i++) {
                day = i + 1;

                cx = xStep * currentColumn + xStep / 2f;
                cy = yStep * currentCell + yStep / 2f + AndroidUtilities.dp(44);

                x = xStep * currentColumn;
                y = yStep * currentCell + AndroidUtilities.dp(44);
                rect.set(x - 1, y + rectPadding, x + xStep + 1, y + yStep - rectPadding);

                textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
                activeTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

                isDaySelected = isSelectingDays && selectedDays != null && selectedDays.isDaySelected(currentYear, currentMonthInYear, day);

                if (nowTime < startMonthTime + (i + 1) * 86400) {
                    int oldAlpha = textPaint.getAlpha();
                    textPaint.setAlpha((int) (oldAlpha * 0.3f));
                    canvas.drawText(Integer.toString(day), cx, cy + AndroidUtilities.dp(5), textPaint);
                    textPaint.setAlpha(oldAlpha);
                } else if (messagesByDays != null && messagesByDays.get(i, null) != null) {
                    float alpha = 1f;
                    if (imagesByDays.get(i) != null) {
                        if (checkEnterItems && !messagesByDays.get(i).wasDrawn) {
                            messagesByDays.get(i).enterAlpha = 0f;
                            messagesByDays.get(i).startEnterDelay = (cy + getY()) / listView.getMeasuredHeight() * 150;
                        }
                        if (messagesByDays.get(i).startEnterDelay > 0) {
                            messagesByDays.get(i).startEnterDelay -= 16;
                            if (messagesByDays.get(i).startEnterDelay < 0) {
                                messagesByDays.get(i).startEnterDelay = 0;
                            } else {
                                invalidate();
                            }
                        }
                        if (messagesByDays.get(i).startEnterDelay == 0 && messagesByDays.get(i).enterAlpha != 1f) {
                            messagesByDays.get(i).enterAlpha += 16 / 220f;
                            if (messagesByDays.get(i).enterAlpha > 1f) {
                                messagesByDays.get(i).enterAlpha = 1f;
                            } else {
                                invalidate();
                            }
                        }
                        alpha = messagesByDays.get(i).enterAlpha;
                        if (alpha != 1f) {
                            canvas.save();
                            float s = 0.8f + 0.2f * alpha;
                            canvas.scale(s, s, cx, cy);
                        }
                        imagesByDays.get(i).setAlpha(messagesByDays.get(i).enterAlpha);
                        //IMAGE DRAW
                        int size = 44;
                        boolean isAnimated = false;
                        if (selectedDays != null) {

                            int sum = selectedDays.sum(currentYear, currentMonthInYear, day);
                            if (riseAnimator != null && (sum == selectedDays.prevSum)) {
                                if (!isDaySelected) {
                                    size = (int) (size - (selectionPadding * (1f - riseAnimator.getAnimatedFraction())));
                                    isAnimated = true;
                                }
                            } else if (riseAnimator != null && ((sum == selectedDays.endSum && !selectedDays.lastChangedStart)
                                    || (sum == selectedDays.startSum && selectedDays.lastChangedStart))) {
                                if (!(!selectedDays.lastChangedStart && selectedDays.endSum < selectedDays.prevSum)) {
                                    size = (int) (size - (selectionPadding * riseAnimator.getAnimatedFraction()));
                                    isAnimated = true;
                                }

                            }
                        }

                        if (isDaySelected) {
                            if (!isAnimated) {
                                size -= selectionPadding;
                            }

                            if (selectedDays.isStartOrEnd(currentYear, currentMonthInYear, day) || currentColumn == 0 || currentColumn == 6) {

                                selectedPaint.setColor(selectionColorLight);

                                if (!selectedDays.isSingle()) {
                                    //draw selection avoiding sides ans start/end
                                    if ((currentColumn == 0 || selectedDays.isStart(currentYear, currentMonthInYear, day))
                                            && !selectedDays.isEnd(currentYear, currentMonthInYear, day)) {

                                        rect.set(rect.centerX(), rect.top, rect.right, rect.bottom);
                                        canvas.drawRect(rect, selectedPaint);
                                    } else if ((currentColumn >= 6 || selectedDays.isEnd(currentYear, currentMonthInYear, day))
                                            && !selectedDays.isStart(currentYear, currentMonthInYear, day)) {

                                        rect.set(rect.left, rect.top, rect.centerX(), rect.bottom);
                                        canvas.drawRect(rect, selectedPaint);
                                    }
                                }

                                if (!selectedDays.isStartOrEnd(currentYear, currentMonthInYear, day)) {
                                    //draw rounded sides
                                    canvas.drawCircle(cx, cy, AndroidUtilities.dp(44) / 2f, selectedPaint);

                                } else {
                                    //draw start/end round on start or end
                                    canvas.drawCircle(cx, cy, AndroidUtilities.dp(44) / 2f, hollow);
                                }
                            } else {
                                selectedPaint.setColor(selectionColorLight);
                                canvas.drawRect(rect, selectedPaint);
                            }
                        }
                        imagesByDays.get(i).setImageCoords(cx - AndroidUtilities.dp(size) / 2f,
                                cy - AndroidUtilities.dp(size) / 2f, AndroidUtilities.dp(size), AndroidUtilities.dp(size));

                        imagesByDays.get(i).draw(canvas);
                        blackoutPaint.setColor(ColorUtils.setAlphaComponent(Color.BLACK, (int) (messagesByDays.get(i).enterAlpha * 80)));
                        canvas.drawCircle(cx, cy, AndroidUtilities.dp(size) / 2f, blackoutPaint);
                        messagesByDays.get(i).wasDrawn = true;
                        if (alpha != 1f) {
                            canvas.restore();
                        }
                    }
                    if (alpha != 1f) {
                        int oldAlpha = textPaint.getAlpha();
                        textPaint.setAlpha((int) (oldAlpha * (1f - alpha)));
                        canvas.drawText(Integer.toString(i + 1), cx, cy + AndroidUtilities.dp(5), textPaint);
                        textPaint.setAlpha(oldAlpha);

                        oldAlpha = textPaint.getAlpha();
                        activeTextPaint.setAlpha((int) (oldAlpha * alpha));
                        canvas.drawText(Integer.toString(day), cx, cy + AndroidUtilities.dp(5), activeTextPaint);
                        activeTextPaint.setAlpha(oldAlpha);
                    } else {
                        //text over image
                        if (isDaySelected) {
                            activeTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                        }
                        canvas.drawText(Integer.toString(day), cx, cy + AndroidUtilities.dp(5), activeTextPaint);
                    }

                } else {
                    // text without images
                    //chosen day when calendar opend.
                    if (initialDateAnimator != null && animatedDay == day && initialDateAnimator.isRunning()) {
                        int alpha = selectedPaint.getAlpha();
                        selectedPaint.setAlpha((int) (180 - 180 * initialDateAnimator.getAnimatedFraction()));
                        canvas.drawCircle(cx, cy, AndroidUtilities.dp(44) / 2f, selectedPaint);
                        selectedPaint.setAlpha(alpha);
                    }


                    if (isDaySelected) {
                        if (selectedDays.isStartOrEnd(currentYear, currentMonthInYear, day) || currentColumn == 0 || currentColumn == 6) {

                            selectedPaint.setColor(selectionColorLight);

                            if (!selectedDays.isSingle()) {
                                //draw selection avoiding sides ans start/end
                                if ((currentColumn == 0 || selectedDays.isStart(currentYear, currentMonthInYear, day)) && !selectedDays.isEnd(currentYear, currentMonthInYear, day)) {
                                    rect.set(rect.centerX(), rect.top, rect.right, rect.bottom);
                                    canvas.drawRect(rect, selectedPaint);
                                } else if ((currentColumn >= 6 || selectedDays.isEnd(currentYear, currentMonthInYear, day)) && !selectedDays.isStart(currentYear, currentMonthInYear, day)) {
                                    rect.set(rect.left, rect.top, rect.centerX(), rect.bottom);
                                    canvas.drawRect(rect, selectedPaint);
                                }
                            }

                            if (!selectedDays.isStartOrEnd(currentYear, currentMonthInYear, day)) {

                                //draw rounded sides
                                canvas.drawCircle(cx, cy, AndroidUtilities.dp(44) / 2f, selectedPaint);
                                textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                                canvas.drawText(Integer.toString(day), cx, cy + AndroidUtilities.dp(5), textPaint);
                            } else {
                                //draw start/end round on start or end
                                selectedPaint.setColor(selectionColorDark);
                                int size = 44;
                                boolean isAnimated = false;
                                if (selectedDays != null) {
                                    int sum = (selectedDays.sum(currentYear, currentMonthInYear, day));
//                                    if (riseAnimator != null && (sum == selectedDays.prevSum)) {
//                                        size = (int) (size - (selectionPadding * (1 - riseAnimator.getAnimatedFraction())));
//                                        isAnimated = true;
//                                    } else
                                    if (riseAnimator != null && ((sum == selectedDays.endSum && !selectedDays.lastChangedStart)
                                            || (sum == selectedDays.startSum && selectedDays.lastChangedStart))) {

                                        size = (int) (size - (selectionPadding * riseAnimator.getAnimatedFraction()));
                                        isAnimated = true;
                                    } else {
                                        size = 44 - selectionPadding;
                                    }
                                }
                                // if(!is)

                                canvas.drawCircle(cx, cy, AndroidUtilities.dp(size) / 2f, selectedPaint);
                                canvas.drawCircle(cx, cy, AndroidUtilities.dp(44) / 2f, hollow);
                                activeTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                                canvas.drawText(Integer.toString(day), cx, cy + AndroidUtilities.dp(5), activeTextPaint);
                            }
                        } else {
                            selectedPaint.setColor(selectionColorLight);
                            canvas.drawRect(rect, selectedPaint);
                            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                            canvas.drawText(Integer.toString(day), cx, cy + AndroidUtilities.dp(5), textPaint);
                        }
                    } else {
                        canvas.drawText(Integer.toString(day), cx, cy + AndroidUtilities.dp(5), textPaint);
                    }
                }

                currentColumn++;
                if (currentColumn >= 7) {
                    currentColumn = 0;
                    currentCell++;
                }
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            if (imagesByDays != null) {
                for (int i = 0; i < imagesByDays.size(); i++) {
                    imagesByDays.valueAt(i).onAttachedToWindow();
                }
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            attached = false;
            if (imagesByDays != null) {
                for (int i = 0; i < imagesByDays.size(); i++) {
                    imagesByDays.valueAt(i).onDetachedFromWindow();
                }
            }
        }
    }

    private void invalidateRiseAnim() {
        if(selectedDays == null){
            return;
        }
        int startSum = selectedDays.sum(selectedDays.start_year,selectedDays.start_month, 0);
        int endSum = selectedDays.sum(selectedDays.end_year, selectedDays.end_month, 0);
        for (int s = 0; s < listView.getChildCount(); s++) {
            MonthView child = (MonthView) listView.getChildAt(s);
            int sum = selectedDays.sum(child.currentYear,child.currentMonthInYear, 0);
            if(startSum < sum && endSum > sum){
              continue;
            }
            child.invalidate();
        }
    }

    private void invalidateAll() {
        for (int s = 0; s < listView.getChildCount(); s++) {
            View child = listView.getChildAt(s);
            child.invalidate();
        }
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public interface Callback {
        void onDateSelected(int year, int month, int date);
    }

    private class PeriodDay {
        MessageObject messageObject;
        int startOffset;
        float enterAlpha = 1f;
        float startEnterDelay = 1f;
        boolean wasDrawn;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {

        ThemeDescription.ThemeDescriptionDelegate descriptionDelegate = new ThemeDescription.ThemeDescriptionDelegate() {
            @Override
            public void didSetColor() {
                updateColors();
            }
        };
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        new ThemeDescription(null, 0, null, null, null, descriptionDelegate, Theme.key_windowBackgroundWhite);
        new ThemeDescription(null, 0, null, null, null, descriptionDelegate, Theme.key_windowBackgroundWhiteBlackText);
        new ThemeDescription(null, 0, null, null, null, descriptionDelegate, Theme.key_listSelector);
        new ThemeDescription(null, 0, null, null, null, descriptionDelegate, Theme.key_windowBackgroundChecked);
        new ThemeDescription(null, 0, null, null, null, descriptionDelegate, Theme.key_dialogTextRed2);
        new ThemeDescription(null, 0, null, null, null, descriptionDelegate, Theme.key_dialogTextBlue);

        return super.getThemeDescriptions();
    }

    @Override
    public boolean needDelayOpenAnimation() {
        return true;
    }

    @Override
    protected void onTransitionAnimationStart(boolean isOpen, boolean backward) {
        super.onTransitionAnimationStart(isOpen, backward);
        isOpened = true;
    }
}
