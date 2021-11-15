package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LongSparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ChatListItemAnimator;
import androidx.recyclerview.widget.GridLayoutManagerFixed;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ForwardingMessagesParams;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.MessageCalendarActivity;

import java.util.ArrayList;
import java.util.Calendar;

public class ChatPreview extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {
    private static ChatPreview preview;
    private final int min_date, max_date;
    private final long dialogId;
    private final MessageCalendarActivity parent;
    private final boolean hasCaption = true;
    private final int _year, _month, _day;
    private boolean isLoading = false;
    private boolean allMessageLoaded;

    public ArrayList<MessageObject> messages = new ArrayList<>();
    public LongSparseArray<MessageObject.GroupedMessages> groupedMessagesMap = new LongSparseArray<>();
    SizeNotifierFrameLayout chatPreviewContainer;
    ActionBar actionBar;
    RecyclerListView chatListView;
    ChatListItemAnimator itemAnimator;
    GridLayoutManagerFixed chatLayoutManager;

    Adapter adapter;

    ScrollView menuScrollView;
    LinearLayout menuContainer;
    LinearLayout buttonsLayout;


    ActionBarMenuSubItem jumpToDateView;
    ActionBarMenuSubItem selectThisDayView;
    ActionBarMenuSubItem clearHistoryView;

    int chatTopOffset;
    float yOffset;
    int currentTopOffset;
    float currentYOffset;
    int firstOffsetId;


    ArrayList<ActionBarMenuSubItem> actionItems = new ArrayList<>();


    private boolean firstLayout = true;
    ValueAnimator offsetsAnimator;
    TLRPC.User currentUser;
    TLRPC.Chat currentChat;
    boolean showing;
    boolean isLandscapeMode;
    private final int currentAccount;

    Runnable changeBoundsRunnable = new Runnable() {
        @Override
        public void run() {
            if (offsetsAnimator != null && !offsetsAnimator.isRunning()) {
                offsetsAnimator.start();
            }
        }
    };

    private final ArrayList<MessageObject.GroupedMessages> drawingGroups = new ArrayList<>(10);
    private final ForwardingPreviewView.ResourcesDelegate resourcesProvider;
    private int classGuid;
    private boolean awaitScroll;
    private int messagesCount;
    private final int LoadLimit = 10;


    public static void clear() {
        if (preview != null && preview.parent != null) {
            try {
                if (preview.getParent() == preview.parent.getLayoutContainer()) {
                    preview.parent.getLayoutContainer().removeView(preview);
                }
            } catch (Exception ignore) {
            }
        }
        preview = null;
    }

    public static void create(@NonNull Context context, MessageCalendarActivity parent, ForwardingPreviewView.ResourcesDelegate delegate, long dialogId, int year, int month, int day) {
        clear();
        preview = new ChatPreview(context, parent, delegate, dialogId, year, month, day);
        preview.loadMessages();
        parent.getLayoutContainer().addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    public static boolean isVisible() {
        return preview != null;
    }

    private int messageCount() {
        return messagesCount;
    }

    private void loadMessagesCount() {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();

        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.add_offset = -1;
        req.limit = 1;
        req.offset_date = max_date;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response != null) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                messagesCount = firstOffsetId - res.offset_id_offset;
                actionBar.setSubtitle(LocaleController.formatPluralString("messages", messagesCount));
            }
        }));
    }

    private void loadMessages() {
        if (allMessageLoaded || isLoading) {
            return;
        }

        isLoading = true;
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.add_offset = -Math.max(messages.size() - 1, 0) - LoadLimit;
        req.limit = LoadLimit;
        req.offset_date = min_date;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (response != null) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                isLoading = false;
                if (res.messages != null) {
                    if (res.messages.size() == 0) {
                        allMessageLoaded = true;
                        return;
                    }

                    int addedCount = 0;
                    ArrayList<MessageObject> previewList = new ArrayList<>(res.messages.size());
                    for (int a = res.messages.size() - 1; a >= 0; a--) {
                        TLRPC.Message message = res.messages.get(a);
                        if(message instanceof TLRPC.TL_messageService){
                            TLRPC.Message m  = new TLRPC.TL_message();
                            m.date =  message.date;
                            m.message = getServiceMessage(message.action);// "service message";
                            m.id = message.id;
                            m.dialog_id = message.dialog_id;
                            m.peer_id = message.peer_id;

                            message = m;
                        }

                        MessageObject previewMessage = new MessageObject(currentAccount, message, true, true);
                        if (previewMessage.messageOwner.date > max_date) {
                            allMessageLoaded = true;
                            break;
                        }
                        if (previewMessage.getGroupId() != 0) {
                            MessageObject.GroupedMessages groupedMessages = groupedMessagesMap.get(previewMessage.getGroupId(), null);
                            if (groupedMessages == null) {
                                groupedMessages = new MessageObject.GroupedMessages();
                                groupedMessagesMap.put(previewMessage.getGroupId(), groupedMessages);
                            }
                            groupedMessages.messages.add(previewMessage);
                        }

                        previewList.add(previewMessage);
                        addedCount++;
                    }

                    if (addedCount == 0) {
                        return;
                    }

                    //add empty for date
                    if (messages.size() == 0 && previewList.size() > 0) {
                        TLRPC.Message p = previewList.get(0).messageOwner;
                        TLRPC.Message m = new TLRPC.TL_message();
                        m.date = 0;
                        m.dialog_id = previewList.get(0).getDialogId();
                        m.peer_id = p.peer_id;

                        MessageObject o = new MessageObject(currentAccount, m, false, false);
                        o.isDateObject = true;
                        messages.add(o);
                        addedCount++;
                        awaitScroll = true;
                        firstOffsetId = res.offset_id_offset;
                        if (res.messages.size() == LoadLimit) {
                            loadMessagesCount();
                        }
                    }


                    for (int a = 0; a < previewList.size(); ++a) {
                        messages.add(0, previewList.get(a));
                    }

                    if (messages.size() > 3000) {
                        //TODO make two-side loading.
                        allMessageLoaded = true;
                    }

                    int finalAddedCount = addedCount;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (finalAddedCount < LoadLimit) {
                            allMessageLoaded = true;
                            messagesCount = messages.size() - 1;
                            actionBar.setSubtitle(LocaleController.formatPluralString("messages", messages.size() - 1));
                        }

                        if (chatListView.getAdapter() != null) {
                            chatListView.getAdapter().notifyItemRangeInserted(0, finalAddedCount);
                        }

                        invalidate();
                        chatListView.scrollToPosition(finalAddedCount);
                    });

                }
            } else {

            }
        });


    }

    private String getServiceMessage(TLRPC.MessageAction action){
        String message = "service message";
        if(action instanceof TLRPC.TL_messageActionInviteToGroupCall){
            message = "Invite to group call";
        }else  if(action instanceof TLRPC.TL_messageActionGroupCall){
            message = "Group call";
        }
        else  if(action instanceof TLRPC.TL_messageActionChatAddUser){
            message = "User added";
        }
        else if(action instanceof TLRPC.TL_messageActionHistoryClear){
            message = "History cleaned";
        }else  if(action instanceof TLRPC.TL_messageActionUserJoined){
            message = "User joined";
        }
        else  if(action instanceof TLRPC.TL_messageActionUserUpdatedPhoto){
            message = "User updated photo";
        }
        else  if(action instanceof TLRPC.TL_messageActionChannelCreate){
            message = "Channel created";
        }
        else  if(action instanceof TLRPC.TL_messageActionPinMessage){
            message = "Message pinned";
        }
        else  if(action instanceof TLRPC.TL_messageActionPhoneCall){
            message = "Phone call";
        }
        else  if(action instanceof TLRPC.TL_messageActionChatEditTitle){
            message = "Chat title edited";
        }
        return message;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        //    NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagesDidLoad);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
    }

    @SuppressLint("ClickableViewAccessibility")
    private ChatPreview(@NonNull Context context, MessageCalendarActivity parent, ForwardingPreviewView.ResourcesDelegate delegate, long dialogId, int year, int month, int day) {
        super(context);

        this.parent = parent;
        this.currentAccount = UserConfig.selectedAccount;
        currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
        currentChat = null;
        this.dialogId = dialogId;
        this.resourcesProvider = delegate;
        this._year = year;
        this._month = month;
        this._day = day;
        int timeLimit = (int) (System.currentTimeMillis() / 1000L);


        classGuid = ConnectionsManager.generateClassGuid();
        //    NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagesDidLoad);
        setBackgroundColor(getThemedColor(Theme.key_divider));

        chatPreviewContainer = new SizeNotifierFrameLayout(context) {
            @Override
            protected Drawable getNewDrawable() {
                Drawable drawable = resourcesProvider.getWallpaperDrawable();
                return drawable != null ? drawable : super.getNewDrawable();
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getY() < currentTopOffset) {
                    return false;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        chatPreviewContainer.setBackgroundImage(resourcesProvider.getWallpaperDrawable(), resourcesProvider.isWallpaperMotion());
        chatPreviewContainer.setOccupyStatusBar(false);

        if (Build.VERSION.SDK_INT >= 21) {
            chatPreviewContainer.setOutlineProvider(new ViewOutlineProvider() {
                @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, (int) (currentTopOffset + 1), view.getMeasuredWidth(), view.getMeasuredHeight(), AndroidUtilities.dp(6));
                }
            });
            chatPreviewContainer.setClipToOutline(true);
            chatPreviewContainer.setElevation(AndroidUtilities.dp(4));
        }

        actionBar = new ActionBar(context, resourcesProvider);
        actionBar.setBackgroundColor(getThemedColor(Theme.key_actionBarDefault));
        actionBar.setOccupyStatusBar(false);
        Calendar calendar = Calendar.getInstance();
        int offset = 0;// -(calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)) / (60 * 1000);

        calendar.set(year, month, day, 0, 0, 0);

        min_date = (int) (calendar.getTimeInMillis() / 1000) + offset * 60;

        // actionBar.setTitle(LocaleController.formatDateChat(min_date) + ", " + year);//calendar.getTime().getTime() / 1000
        //   actionBar.setTitle( LocaleController.getInstance().chatFullDate.format(calendar.getTimeInMillis() - offset * 60*1000));

        //getInstance().chatFullDate.format(date)

        calendar.set(year, month, day + 1, 0, 0, 0);
        max_date = (int) ((calendar.getTimeInMillis()) / 1000) + offset * 60;
        //actionBar.setTitle(LocaleController.formatDateChat(calendar.getTime().getTime() / 1000) + ", " + year);
        actionBar.setTitle(LocaleController.getInstance().chatFullDate.format(min_date * 1000L));
        actionBar.setSubtitle(LocaleController.formatPluralString("messages", 0));


        chatListView = new RecyclerListView(context, resourcesProvider) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                if (awaitScroll) {
                    awaitScroll = false;
                    scrollToPosition(messages.size() - 1);
                }
            }

            @Override
            public boolean drawChild(Canvas canvas, View child, long drawingTime) {
                if (child instanceof ChatMessageCell) {
                    ChatMessageCell cell = (ChatMessageCell) child;
                    boolean r = super.drawChild(canvas, child, drawingTime);
                    cell.drawCheckBox(canvas);
                    canvas.save();
                    canvas.translate(cell.getX(), cell.getY());
                    cell.drawMessageText(canvas, cell.getMessageObject().textLayoutBlocks, true, 1f, false);

                    if (cell.getCurrentMessagesGroup() != null || cell.getTransitionParams().animateBackgroundBoundsInner) {
                        cell.drawNamesLayout(canvas, 1f);
                    }
                    if ((cell.getCurrentPosition() != null && cell.getCurrentPosition().last) || cell.getTransitionParams().animateBackgroundBoundsInner) {
                        cell.drawTime(canvas, 1f, true);
                    }
                    if (cell.getCurrentPosition() == null || cell.getCurrentPosition().last) {
                        cell.drawCaptionLayout(canvas, false, 1f);
                    }
                    cell.getTransitionParams().recordDrawingStatePreview();
                    canvas.restore();
                    return r;
                } else if (child instanceof ChatActionCell) {

                    ChatActionCell cell = (ChatActionCell) child;
                    canvas.save();
                    canvas.translate(cell.getX(), cell.getY());
                    //    canvas.scale(cell.getScaleX(), cell.getScaleY(), cell.getMeasuredWidth() / 2f, cell.getMeasuredHeight() / 2f);
                    //    cell.drawBackground(canvas, true);
                    //    cell.draw
                    cell.draw(canvas);

                    canvas.restore();
                }
                return true;
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child instanceof ChatMessageCell) {
                        ChatMessageCell cell = (ChatMessageCell) child;
                        cell.setParentViewSize(chatPreviewContainer.getMeasuredWidth(), chatPreviewContainer.getBackgroundSizeY());
                    }
                }
                drawChatBackgroundElements(canvas);
                super.dispatchDraw(canvas);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                updatePositions();
            }

            private void drawChatBackgroundElements(Canvas canvas) {
                int count = getChildCount();
                MessageObject.GroupedMessages lastDrawnGroup = null;

                for (int a = 0; a < count; a++) {
                    View child = getChildAt(a);
                    if (child instanceof ChatMessageCell) {
                        ChatMessageCell cell = (ChatMessageCell) child;
                        MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
                        if (group != null && group == lastDrawnGroup) {
                            continue;
                        }
                        lastDrawnGroup = group;
                    }
                }
                MessageObject.GroupedMessages scrimGroup = null;
                for (int k = 0; k < 3; k++) {
                    drawingGroups.clear();
                    if (k == 2 && !chatListView.isFastScrollAnimationRunning()) {
                        continue;
                    }
                    for (int i = 0; i < count; i++) {
                        View child = chatListView.getChildAt(i);
                        if (child instanceof ChatMessageCell) {
                            ChatMessageCell cell = (ChatMessageCell) child;
                            if (child.getY() > chatListView.getHeight() || child.getY() + child.getHeight() < 0) {
                                continue;
                            }
                            MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
                            if (group == null || (k == 0 && group.messages.size() == 1) || (k == 1 && !group.transitionParams.drawBackgroundForDeletedItems)) {
                                continue;
                            }
                            if ((k == 0 && cell.getMessageObject().deleted) || (k == 1 && !cell.getMessageObject().deleted)) {
                                continue;
                            }
                            if ((k == 2 && !cell.willRemovedAfterAnimation()) || (k != 2 && cell.willRemovedAfterAnimation())) {
                                continue;
                            }

                            if (!drawingGroups.contains(group)) {
                                group.transitionParams.left = 0;
                                group.transitionParams.top = 0;
                                group.transitionParams.right = 0;
                                group.transitionParams.bottom = 0;

                                group.transitionParams.pinnedBotton = false;
                                group.transitionParams.pinnedTop = false;
                                group.transitionParams.cell = cell;
                                drawingGroups.add(group);
                            }

                            group.transitionParams.pinnedTop = cell.isPinnedTop();
                            group.transitionParams.pinnedBotton = cell.isPinnedBottom();

                            int left = (cell.getLeft() + cell.getBackgroundDrawableLeft());
                            int right = (cell.getLeft() + cell.getBackgroundDrawableRight());
                            int top = (cell.getTop() + cell.getBackgroundDrawableTop());
                            int bottom = (cell.getTop() + cell.getBackgroundDrawableBottom());

                            if ((cell.getCurrentPosition().flags & MessageObject.POSITION_FLAG_TOP) == 0) {
                                top -= AndroidUtilities.dp(10);
                            }

                            if ((cell.getCurrentPosition().flags & MessageObject.POSITION_FLAG_BOTTOM) == 0) {
                                bottom += AndroidUtilities.dp(10);
                            }

                            if (cell.willRemovedAfterAnimation()) {
                                group.transitionParams.cell = cell;
                            }

                            if (group.transitionParams.top == 0 || top < group.transitionParams.top) {
                                group.transitionParams.top = top;
                            }
                            if (group.transitionParams.bottom == 0 || bottom > group.transitionParams.bottom) {
                                group.transitionParams.bottom = bottom;
                            }
                            if (group.transitionParams.left == 0 || left < group.transitionParams.left) {
                                group.transitionParams.left = left;
                            }
                            if (group.transitionParams.right == 0 || right > group.transitionParams.right) {
                                group.transitionParams.right = right;
                            }
                        }
                    }

                    for (int i = 0; i < drawingGroups.size(); i++) {
                        MessageObject.GroupedMessages group = drawingGroups.get(i);
                        if (group == scrimGroup) {
                            continue;
                        }
                        float x = group.transitionParams.cell.getNonAnimationTranslationX(true);
                        float l = (group.transitionParams.left + x + group.transitionParams.offsetLeft);
                        float t = (group.transitionParams.top + group.transitionParams.offsetTop);
                        float r = (group.transitionParams.right + x + group.transitionParams.offsetRight);
                        float b = (group.transitionParams.bottom + group.transitionParams.offsetBottom);

                        if (!group.transitionParams.backgroundChangeBounds) {
                            t += group.transitionParams.cell.getTranslationY();
                            b += group.transitionParams.cell.getTranslationY();
                        }

                        if (t < -AndroidUtilities.dp(20)) {
                            t = -AndroidUtilities.dp(20);
                        }

                        if (b > chatListView.getMeasuredHeight() + AndroidUtilities.dp(20)) {
                            b = chatListView.getMeasuredHeight() + AndroidUtilities.dp(20);
                        }

                        boolean useScale = group.transitionParams.cell.getScaleX() != 1f || group.transitionParams.cell.getScaleY() != 1f;
                        if (useScale) {
                            canvas.save();
                            canvas.scale(group.transitionParams.cell.getScaleX(), group.transitionParams.cell.getScaleY(), l + (r - l) / 2, t + (b - t) / 2);
                        }

                        group.transitionParams.cell.drawBackground(canvas, (int) l, (int) t, (int) r, (int) b, group.transitionParams.pinnedTop, group.transitionParams.pinnedBotton, false, 0);
                        group.transitionParams.cell = null;
                        group.transitionParams.drawCaptionLayout = group.hasCaption;
                        if (useScale) {
                            canvas.restore();
                            for (int ii = 0; ii < count; ii++) {
                                View child = chatListView.getChildAt(ii);
                                if (child instanceof ChatMessageCell && ((ChatMessageCell) child).getCurrentMessagesGroup() == group) {
                                    ChatMessageCell cell = ((ChatMessageCell) child);
                                    int left = cell.getLeft();
                                    int top = cell.getTop();
                                    child.setPivotX(l - left + (r - l) / 2);
                                    child.setPivotY(t - top + (b - t) / 2);
                                }
                            }
                        }
                    }
                }
            }

        };


        chatListView.setItemAnimator(itemAnimator = new ChatListItemAnimator(null, chatListView, resourcesProvider) {

            int scrollAnimationIndex = -1;

            @Override
            public void onAnimationStart() {
                super.onAnimationStart();
                AndroidUtilities.cancelRunOnUIThread(changeBoundsRunnable);
                changeBoundsRunnable.run();

                if (scrollAnimationIndex == -1) {
                    scrollAnimationIndex = NotificationCenter.getInstance(currentAccount).setAnimationInProgress(scrollAnimationIndex, null, false);
                }
                if (finishRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(finishRunnable);
                    finishRunnable = null;
                }
            }

            Runnable finishRunnable;

            @Override
            protected void onAllAnimationsDone() {
                super.onAllAnimationsDone();
                if (finishRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(finishRunnable);
                }
                AndroidUtilities.runOnUIThread(finishRunnable = () -> {
                    if (scrollAnimationIndex != -1) {
                        NotificationCenter.getInstance(currentAccount).onAnimationFinish(scrollAnimationIndex);
                        scrollAnimationIndex = -1;
                    }
                });

                if (updateAfterAnimations) {
                    updateAfterAnimations = false;
                    AndroidUtilities.runOnUIThread(() -> updateMessages());
                }
            }

            @Override
            public void endAnimations() {
                super.endAnimations();
                if (finishRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(finishRunnable);
                }
                AndroidUtilities.runOnUIThread(finishRunnable = () -> {
                    if (scrollAnimationIndex != -1) {
                        NotificationCenter.getInstance(currentAccount).onAnimationFinish(scrollAnimationIndex);
                        scrollAnimationIndex = -1;
                    }
                });
            }
        });
        chatListView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                for (int i = 0; i < chatListView.getChildCount(); i++) {
                    if (chatListView.getChildAt(i) instanceof ChatMessageCell) {
                        ChatMessageCell cell = (ChatMessageCell) chatListView.getChildAt(i);
                        cell.setParentViewSize(chatPreviewContainer.getMeasuredWidth(), chatPreviewContainer.getBackgroundSizeY());
                    }
                }


                if (!chatListView.canScrollVertically(1)) {
                    loadMessages();
                }
            }
        });

//        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(context,LinearLayoutManager.VERTICAL,true);
//        linearLayoutManager.setStackFromEnd(true);
//        chatListView.setLayoutManager(linearLayoutManager);

        chatListView.setAdapter(adapter = new Adapter());
        chatListView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        chatLayoutManager = new GridLayoutManagerFixed(context, 1000, LinearLayoutManager.VERTICAL, true) {

            @Override
            public boolean shouldLayoutChildFromOpositeSide(View child) {
                return false;
            }

            @Override
            protected boolean hasSiblingChild(int position) {
                MessageObject message = messages.get(position);
                MessageObject.GroupedMessages group = getValidGroupedMessage(message);
                if (group != null) {
                    MessageObject.GroupedMessagePosition pos = group.positions.get(message);
                    if (pos.minX == pos.maxX || pos.minY != pos.maxY || pos.minY == 0) {
                        return false;
                    }
                    int count = group.posArray.size();
                    for (int a = 0; a < count; a++) {
                        MessageObject.GroupedMessagePosition p = group.posArray.get(a);
                        if (p == pos) {
                            continue;
                        }
                        if (p.minY <= pos.minY && p.maxY >= pos.minY) {
                            return true;
                        }
                    }
                }
                return false;
            }

            @Override
            public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
                if (BuildVars.DEBUG_PRIVATE_VERSION) {
                    super.onLayoutChildren(recycler, state);
                } else {
                    try {
                        super.onLayoutChildren(recycler, state);
                    } catch (Exception e) {
                        FileLog.e(e);
                        AndroidUtilities.runOnUIThread(() -> adapter.notifyDataSetChanged());
                    }
                }
            }
        };
        chatLayoutManager.setSpanSizeLookup(new GridLayoutManagerFixed.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int idx = position;
                if (idx >= 0 && idx < messages.size()) {
                    MessageObject message = messages.get(idx);
                    MessageObject.GroupedMessages groupedMessages = getValidGroupedMessage(message);
                    if (groupedMessages != null) {
                        return groupedMessages.positions.get(message).spanSize;
                    }
                }
                return 1000;
            }
        });
        chatListView.setClipToPadding(false);
        chatListView.setLayoutManager(chatLayoutManager);
        chatListView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                outRect.bottom = 0;
                if (view instanceof ChatMessageCell) {
                    ChatMessageCell cell = (ChatMessageCell) view;
                    MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
                    if (group != null) {
                        MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
                        if (position != null && position.siblingHeights != null) {
                            float maxHeight = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 0.5f;
                            int h = cell.getExtraInsetHeight();
                            for (int a = 0; a < position.siblingHeights.length; a++) {
                                h += (int) Math.ceil(maxHeight * position.siblingHeights[a]);
                            }
                            h += (position.maxY - position.minY) * Math.round(7 * AndroidUtilities.density);
                            int count = group.posArray.size();
                            for (int a = 0; a < count; a++) {
                                MessageObject.GroupedMessagePosition pos = group.posArray.get(a);
                                if (pos.minY != position.minY || pos.minX == position.minX && pos.maxX == position.maxX && pos.minY == position.minY && pos.maxY == position.maxY) {
                                    continue;
                                }
                                if (pos.minY == position.minY) {
                                    h -= (int) Math.ceil(maxHeight * pos.ph) - AndroidUtilities.dp(4);
                                    break;
                                }
                            }
                            outRect.bottom = -h;
                        }
                    }
                }
            }
        });

        chatPreviewContainer.addView(chatListView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 0, 0, 0, 0));
        addView(chatPreviewContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 8, 0, 8, 0));
        chatPreviewContainer.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        menuScrollView = new ScrollView(context);
        // menuScrollView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(40));
        addView(menuScrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 2, 0, 40));

        menuContainer = new LinearLayout(context);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        menuScrollView.addView(menuContainer, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));


        if (hasCaption) {
            View dividerView = new View(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(2, MeasureSpec.EXACTLY));
                }
            };
            dividerView.setBackgroundColor(getThemedColor(Theme.key_divider));
        }

        buttonsLayout = new LinearLayout(context);
        buttonsLayout.setOrientation(LinearLayout.VERTICAL);
        Drawable shadowDrawable = getContext().getResources().getDrawable(R.drawable.popup_fixed_alert).mutate();
        shadowDrawable.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY));
        buttonsLayout.setBackground(shadowDrawable);
        menuContainer.addView(buttonsLayout, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 0, 0));


        jumpToDateView = new ActionBarMenuSubItem(context, true, false, resourcesProvider);
        buttonsLayout.addView(jumpToDateView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        jumpToDateView.setTextAndIcon(LocaleController.getString("JumpToDate", R.string.JumpToDate), R.drawable.msg_message);


        selectThisDayView = new ActionBarMenuSubItem(context, true, false, resourcesProvider);
        buttonsLayout.addView(selectThisDayView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        selectThisDayView.setTextAndIcon(LocaleController.getString("SelectThisDay", R.string.SelectThisDay), R.drawable.msg_select);

        clearHistoryView = new ActionBarMenuSubItem(context, true, false, resourcesProvider);
        buttonsLayout.addView(clearHistoryView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        clearHistoryView.setTextAndIcon(LocaleController.getString("ClearHistory", R.string.ClearHistory), R.drawable.msg_delete);


        actionItems.add(jumpToDateView);
        actionItems.add(selectThisDayView);
        actionItems.add(clearHistoryView);

        jumpToDateView.setOnClickListener(v -> parent.jumpToDate(_year, _month, _day));
        selectThisDayView.setOnClickListener(v -> parent.selectDays(_year, _month, _day));
        clearHistoryView.setOnClickListener(v -> parent.removeSelected(_year, _month, _day, timeLimit));

        updateMessages();

        menuScrollView.setOnTouchListener((view, motionEvent) -> {
            if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                dismiss(true);
            }
            return true;
        });
        setOnTouchListener((view, motionEvent) -> {
            if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                dismiss(true);
            }
            return true;
        });
        showing = true;
        setAlpha(0);
        setScaleX(0.95f);
        setScaleY(0.95f);
        animate().alpha(1f).scaleX(1f).setDuration(ChatListItemAnimator.DEFAULT_DURATION).setInterpolator(ChatListItemAnimator.DEFAULT_INTERPOLATOR).scaleY(1f);

        updateColors();
    }


    private void updateColors() {
    }

    public void dismiss(boolean canShowKeyboard) {
        if (showing) {
            showing = false;
            animate().alpha(0).scaleX(0.95f).scaleY(0.95f).setDuration(ChatListItemAnimator.DEFAULT_DURATION).setInterpolator(ChatListItemAnimator.DEFAULT_INTERPOLATOR).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (getParent() != null) {
                        ViewGroup parent = (ViewGroup) getParent();
                        parent.removeView(ChatPreview.this);
                    }
                }
            });
            onDismiss(canShowKeyboard);
        }
    }

    protected void onDismiss(boolean canShowKeyboard) {

    }

    boolean updateAfterAnimations;

    private void updateMessages() {
        if (itemAnimator.isRunning()) {
            updateAfterAnimations = true;
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            messageObject.forceUpdate = true;

            messageObject.messageOwner.flags |= TLRPC.MESSAGE_FLAG_FWD;
            messageObject.generateCaption();

            if (messageObject.isPoll()) {
                ForwardingMessagesParams.PreviewMediaPoll mediaPoll = (ForwardingMessagesParams.PreviewMediaPoll) messageObject.messageOwner.media;
                mediaPoll.results.total_voters = mediaPoll.totalVotersCached;
            }
        }

        adapter.notifyItemRangeChanged(0, messages.size());
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxActionWidth = 0;
        isLandscapeMode = MeasureSpec.getSize(widthMeasureSpec) > MeasureSpec.getSize(heightMeasureSpec);

        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (isLandscapeMode) {
            width = (int) (MeasureSpec.getSize(widthMeasureSpec) * 0.38f);
        }
        for (int i = 0; i < actionItems.size(); i++) {
            actionItems.get(i).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.UNSPECIFIED));
            if (actionItems.get(i).getMeasuredWidth() > maxActionWidth) {
                maxActionWidth = actionItems.get(i).getMeasuredWidth();
            }
        }

        buttonsLayout.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.UNSPECIFIED));

        ((MarginLayoutParams) chatListView.getLayoutParams()).topMargin = ActionBar.getCurrentActionBarHeight();
        if (isLandscapeMode) {
            chatPreviewContainer.getLayoutParams().height = LayoutHelper.MATCH_PARENT;
            ((MarginLayoutParams) chatPreviewContainer.getLayoutParams()).topMargin = AndroidUtilities.dp(8);
            ((MarginLayoutParams) chatPreviewContainer.getLayoutParams()).bottomMargin = AndroidUtilities.dp(8);
            chatPreviewContainer.getLayoutParams().width = (int) Math.min(MeasureSpec.getSize(widthMeasureSpec), Math.max(AndroidUtilities.dp(340), MeasureSpec.getSize(widthMeasureSpec) * 0.6f));
            menuScrollView.getLayoutParams().height = LayoutHelper.MATCH_PARENT;
        } else {
            ((MarginLayoutParams) chatPreviewContainer.getLayoutParams()).topMargin = 0;
            ((MarginLayoutParams) chatPreviewContainer.getLayoutParams()).bottomMargin = 0;
            chatPreviewContainer.getLayoutParams().height = MeasureSpec.getSize(heightMeasureSpec) - AndroidUtilities.dp(16 - 10) - buttonsLayout.getMeasuredHeight();
            if (chatPreviewContainer.getLayoutParams().height < MeasureSpec.getSize(heightMeasureSpec) * 0.5f) {
                chatPreviewContainer.getLayoutParams().height = (int) (MeasureSpec.getSize(heightMeasureSpec) * 0.5f);
            }
            chatPreviewContainer.getLayoutParams().width = LayoutHelper.MATCH_PARENT;
            menuScrollView.getLayoutParams().height = MeasureSpec.getSize(heightMeasureSpec) - chatPreviewContainer.getLayoutParams().height;
        }

        int size = MeasureSpec.getSize(widthMeasureSpec) + MeasureSpec.getSize(heightMeasureSpec) << 16;
        if (lastSize != size) {
            for (int i = 0; i < messages.size(); i++) {
                if (isLandscapeMode) {
                    messages.get(i).parentWidth = chatPreviewContainer.getLayoutParams().width;
                } else {
                    messages.get(i).parentWidth = MeasureSpec.getSize(widthMeasureSpec) - AndroidUtilities.dp(16);
                }
                messages.get(i).resetLayout();
                messages.get(i).forceUpdate = true;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
            firstLayout = true;
        }
        lastSize = size;

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    int lastSize;

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updatePositions();
        firstLayout = false;
    }


    private void updatePositions() {
        int lastTopOffset = chatTopOffset;
        float lastYOffset = yOffset;

        if (!isLandscapeMode) {
            if (chatListView.getChildCount() == 0 || chatListView.getChildCount() > messages.size()) {
                chatTopOffset = 0;
            } else {
                int minTop = chatListView.getChildAt(0).getTop();
                for (int i = 1; i < chatListView.getChildCount(); i++) {
                    if (chatListView.getChildAt(i).getTop() < minTop) {
                        minTop = chatListView.getChildAt(i).getTop();
                    }
                }
                minTop -= AndroidUtilities.dp(4);
                if (minTop < 0) {
                    chatTopOffset = 0;
                } else {
                    chatTopOffset = minTop;
                }
            }

            float totalViewsHeight = buttonsLayout.getMeasuredHeight() - AndroidUtilities.dp(8) + (chatPreviewContainer.getMeasuredHeight() - chatTopOffset);
            float totalHeight = getMeasuredHeight() - AndroidUtilities.dp(16);
            yOffset = AndroidUtilities.dp(8) + (totalHeight - totalViewsHeight) / 2 - chatTopOffset;
            if (yOffset > AndroidUtilities.dp(8)) {
                yOffset = AndroidUtilities.dp(8);
            }
            float buttonX = getMeasuredWidth() - menuScrollView.getMeasuredWidth();
            menuScrollView.setTranslationX(buttonX);
        } else {
            yOffset = 0;
            chatTopOffset = 0;
            menuScrollView.setTranslationX(chatListView.getMeasuredWidth() + AndroidUtilities.dp(8));
        }

        setOffset(0, 0);
    }

    private void setOffset(float yOffset, int chatTopOffset) {
        if (isLandscapeMode) {
            actionBar.setTranslationY(0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                chatPreviewContainer.invalidateOutline();
            }
            chatPreviewContainer.setTranslationY(0);
            menuScrollView.setTranslationY(0);
        } else {
            actionBar.setTranslationY(chatTopOffset);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                chatPreviewContainer.invalidateOutline();
            }
            chatPreviewContainer.setTranslationY(yOffset);
            menuScrollView.setTranslationY(yOffset + chatPreviewContainer.getMeasuredHeight() - AndroidUtilities.dp(2));
        }
    }

    public boolean isShowing() {
        return showing;
    }


    private class Adapter extends RecyclerView.Adapter {

        @Override
        public int getItemViewType(int position) {
            return position == messages.size() - 1 ? 0 : 1;
        }


        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 1) {
                ChatMessageCell chatMessageCell = new ChatMessageCell(parent.getContext(), false, resourcesProvider);
                return new RecyclerListView.Holder(chatMessageCell);
            } else {
                ChatActivity chatActivity = ChatPreview.this.parent.getChatActivity();
                ChatActionCell actionCell;
                if (chatActivity == null) {
                    actionCell = new ChatActionCell(parent.getContext(), false, new ChatActionCell.ThemeDelegate() {
                        @Override
                        public int getCurrentColor() {
                            return Theme.currentColor;
                        }

                        @Override
                        public Integer getColor(String key) {
                            return Theme.getColor(key);
                        }
                    });
                } else {
                    actionCell = new ChatActionCell(parent.getContext(), false, chatActivity.getThemeDelegate());
                }
                return new RecyclerListView.Holder(actionCell);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof ChatMessageCell) {
                ChatMessageCell cell = (ChatMessageCell) holder.itemView;
                cell.setParentViewSize(chatListView.getMeasuredWidth(), chatListView.getMeasuredHeight());

                cell.setMessageObject(messages.get(position),
                        groupedMessagesMap.get(messages.get(position).getGroupId()), true, true);
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {

                });
            } else if (holder.itemView instanceof ChatActionCell) {
                ChatActionCell cell = (ChatActionCell) holder.itemView;
                cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                cell.setInvalidateColors(true);

                Calendar c = Calendar.getInstance();
                c.set(_year, _month, _day, 0, 0, 0);
                cell.setCustomDate((int) (c.getTimeInMillis() / 1000), false, true);
                cell.setAlpha(1.0f);

            }


        }


        @Override
        public int getItemCount() {
            return messages.size();
        }
    }

    protected void selectAnotherChat() {

    }

    protected void didSendPressed() {

    }

    private MessageObject.GroupedMessages getValidGroupedMessage(MessageObject message) {
        MessageObject.GroupedMessages groupedMessages = null;
        if (message.getGroupId() != 0) {
            groupedMessages = groupedMessagesMap.get(message.getGroupId());
            if (groupedMessages != null && (groupedMessages.messages.size() <= 1 || groupedMessages.positions.get(message) == null)) {
                groupedMessages = null;
            }
        }
        return groupedMessages;
    }

    private int getThemedColor(String key) {
        Integer color = resourcesProvider != null ? resourcesProvider.getColor(key) : null;
        return color != null ? color : Theme.getColor(key);
    }
}
