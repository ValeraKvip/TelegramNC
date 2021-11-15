package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.graphics.Rect;
import android.graphics.Point;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GroupCreateUserCell;
import org.telegram.ui.Cells.HeaderCell;

import java.util.ArrayList;

public class SenderPickChannelAlert extends LinearLayout {
    private static boolean isShowing;
    private static PopupWindow popupWindow;
    private final Drawable shadowDrawable;
    private final RecyclerListView listView;
    private final ArrayList<TLRPC.Peer> chats;
    private final View selector;
    private final BaseFragment parentFragment;
    private int maxHeight;

    private TLRPC.Peer selectedPeer;
    private OnSelectListener onSelectListener;

    private static ArrayList<TLRPC.Peer> cachedChats;
    private static long lastCacheTime;
    private static long lastCacheDid;
    private static int lastCachedAccount;
    private AnimatorSet animationSet;
    private boolean animate;

    public static void resetCache() {
        cachedChats = null;
    }

    public static void processDeletedChat(int account, long did) {
        if (lastCachedAccount != account || cachedChats == null || did > 0) {
            return;
        }
        for (int a = 0, N = cachedChats.size(); a < N; a++) {
            if (MessageObject.getPeerId(cachedChats.get(a)) == did) {
                cachedChats.remove(a);
                break;
            }
        }
        if (cachedChats.isEmpty()) {
            cachedChats = null;
        }
    }

    public static boolean isShowing() {
        return isShowing;
    }

    public static void hide() {
        popupWindow.dismiss();
        popupWindow = null;
    }

    public interface OnDismissListener {
        public void onDismiss();
    }

    public interface OnSelectListener {
        public void onSelect(Object object, TLRPC.Peer selectedPeer);
    }

    public interface SenderPickChannelAlertDelegate {
        void didSelectChat(TLRPC.InputPeer peer, boolean hasFewPeers, boolean schedule);
    }

    public static void preload(Context context, long did, AccountInstance accountInstance) {
        show(context, did, accountInstance, null, null, 0, 0, 0, null, null, null, true);
    }

    public static void show(Context context, long did, AccountInstance accountInstance,
                            BaseFragment fragment, TLRPC.Peer defaultPeer, int x, int y, int maxHeight,
                            OnSelectListener onSelectListener, OnDismissListener onDismissListener, View selector) {

        show(context, did, accountInstance, fragment, defaultPeer, x, y, maxHeight,
                onSelectListener, onDismissListener, selector, false);
    }

    public static void show(Context context, long did, AccountInstance accountInstance,
                            BaseFragment fragment, TLRPC.Peer defaultPeer, int x, int y, int maxHeight,
                            OnSelectListener onSelectListener, OnDismissListener onDismissListener, View selector, boolean preload) {
        if (context == null) {
            return;
        }

        if (!preload && lastCachedAccount == accountInstance.getCurrentAccount() && lastCacheDid == did && cachedChats != null && SystemClock.elapsedRealtime() - lastCacheTime < 5 * 60 * 1000) {
            showAlert(context, did, cachedChats, fragment, defaultPeer, x, y, maxHeight, selector, onSelectListener, onDismissListener);
            return;
        }

        final AlertDialog progressDialog = new AlertDialog(context, 3);
        TLRPC.TL_channels_getSendAs req = new TLRPC.TL_channels_getSendAs();
        req.peer = accountInstance.getMessagesController().getInputPeer(did);
        int reqId = accountInstance.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            try {
                progressDialog.dismiss();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (response != null) {
                TLRPC.TL_channels_sendAsPeers res = (TLRPC.TL_channels_sendAsPeers) response;
                cachedChats = res.peers;
                lastCacheDid = did;
                lastCacheTime = SystemClock.elapsedRealtime();
                lastCachedAccount = accountInstance.getCurrentAccount();
                accountInstance.getMessagesController().putChats(res.chats, false);
                accountInstance.getMessagesController().putUsers(res.users, false);
                if (!preload) {
                    showAlert(context, did, res.peers, fragment, defaultPeer, x, y, maxHeight, selector, onSelectListener, onDismissListener);
                }
            }
        }));
        progressDialog.setOnCancelListener(dialog -> accountInstance.getConnectionsManager().cancelRequest(reqId, true));
        try {
            if (!preload) {
                progressDialog.showDelayed(500);
            }
        } catch (Exception ignore) {

        }
    }


    private static void showAlert(Context context, long dialogId, ArrayList<TLRPC.Peer> peers, BaseFragment fragment,
                                  TLRPC.Peer defaultPeer, int x, int y, int maxHeight, View selector,
                                  OnSelectListener onSelectListener, OnDismissListener onDismissListener) {
        SenderPickChannelAlert alert = new SenderPickChannelAlert(context, dialogId, peers, fragment, defaultPeer, maxHeight, selector, onSelectListener);
        alert.setAnimate(x == 0);

        popupWindow = new PopupWindow(alert, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, true);
        popupWindow.setAnimationStyle(R.style.PopupAnimation);
        popupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        popupWindow.setClippingEnabled(true);
        popupWindow.setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);

        int gravity = x > 0 ? Gravity.CENTER : Gravity.LEFT | Gravity.BOTTOM;

        popupWindow.showAtLocation(alert, gravity, 0, y);
        isShowing = true;
        popupWindow.setOnDismissListener(() -> {
            if (onDismissListener != null) {
                isShowing = false;
                onDismissListener.onDismiss();
            }
        });

    }

    private void setAnimate(boolean animate) {
        this.animate = animate;
    }


    private SenderPickChannelAlert(Context context, long dialogId, ArrayList<TLRPC.Peer> arrayList, BaseFragment fragment,
                                   TLRPC.Peer defaultPeer, int maxHeight, View selector, OnSelectListener onSelectListener) {
        super(context);
        this.parentFragment = fragment;
        this.selector = selector;
        this.maxHeight = maxHeight;
        chats = new ArrayList<>(arrayList);
        this.onSelectListener = onSelectListener;
        this.selectedPeer = defaultPeer;
        this.setClipChildren(true);


        shadowDrawable = context.getResources().getDrawable(R.drawable.popup_fixed_alert).mutate();
        shadowDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY));
        //  selectedPeer = chats.get(0);
        this.setBackground(shadowDrawable);


        this.setOrientation(LinearLayout.VERTICAL);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(new ListAdapter(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setEnabled(true);
        listView.setGlowColor(Theme.getColor(Theme.key_dialogScrollGlow));
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // updateLayout();
            }
        });
        listView.setOnItemClickListener((view, position) -> {

            selectedPeer = chats.get(position);
            MessagesController.getInstance(UserConfig.selectedAccount).getInputPeer(MessageObject.getPeerId(selectedPeer));

            if (onSelectListener != null) {

                if (DialogObject.isUserDialog(selectedPeer.user_id)) {
                    TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(selectedPeer.user_id);
                    if (user != null) {
                        moveAvatarToSelector(view, user, selectedPeer);
                    }
                } else {
                    TLRPC.Chat chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(selectedPeer.chat_id);
                    if (chat == null) {
                        chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(selectedPeer.channel_id);
                    }
                    if (chat != null) {
                        moveAvatarToSelector(view, chat, selectedPeer);
                    }
                }
            }

            if (view instanceof GroupCreateUserCell) {
                ((GroupCreateUserCell) view).setChecked(true, true);
            } else if (view instanceof DialogCell) {
                DialogCell d = ((DialogCell) view);
                d.setChecked(true, true);


                view.invalidate();
            }
            for (int a = 0, N = listView.getChildCount(); a < N; a++) {
                View child = listView.getChildAt(a);
                if (child != view) {
                    if (view instanceof GroupCreateUserCell) {
                        ((GroupCreateUserCell) child).setChecked(false, true);
                    } else if (view instanceof DialogCell) {
                        ((DialogCell) child).setChecked(false, true);
                    }
                }
            }

        });

        listView.setSelectorDrawableColor(0);
        listView.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);


        HeaderCell headerCell = new HeaderCell(context, AndroidUtilities.dp(8));
        headerCell.setText(LocaleController.getString("SendMessageAs", R.string.SendMessageAs));
        addView(headerCell, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 0, 12, 0, 0));
    }


    private void moveAvatarToSelector(View view, TLObject selected, TLRPC.Peer selectedPeer) {
        if (selector == null) {
            return;
        }

        try {
            View _v =  ((DialogCell) view).imageView;
            Runnable done = () -> AndroidUtilities.runOnUIThread(()->{
                try {
                    onSelectListener.onSelect(selected, selectedPeer);
                    ((ViewGroup) _v.getParent()).removeView(_v);
                }catch (Exception ex){
                    FileLog.e(ex);
                }
            });

            if(!animate){
                done.run();
                SenderPickChannelAlert.hide();
                return;
            }


            int[] location = new int[2];
            Point offset = new Point();
            android.graphics.Rect viewRect = new Rect();
            _v.getGlobalVisibleRect(viewRect, offset);
            _v.getLocationOnScreen(location);

            android.graphics.Rect containerRect = new Rect();

            this.getGlobalVisibleRect(containerRect);

            android.graphics.Rect rect = new Rect();
            selector.getGlobalVisibleRect(rect, offset);

            _v.setY(0);
            _v.setX(0);
            ((ViewGroup) _v.getParent()).removeView(_v);
            FrameLayout.LayoutParams params = LayoutHelper.createFrame(viewRect.width(), viewRect.height());
            params.width = viewRect.width();
            params.height = viewRect.height();

            parentFragment.getLayoutContainer().addView(_v);

            _v.setY(location[1]);
            _v.setX(location[0]);

            selector.getLocationOnScreen(location);


            float scaleX = Math.max(_v.getMeasuredWidth(), selector.getMeasuredWidth());
            scaleX = 1f / scaleX * Math.min(_v.getMeasuredWidth(), selector.getMeasuredWidth());

            float scaleY = Math.max(_v.getMeasuredHeight(), selector.getMeasuredHeight());
            scaleY = 1f / scaleY * Math.min(_v.getMeasuredHeight(), selector.getMeasuredHeight());


            animationSet = new AnimatorSet();
            ArrayList<Animator> animators = new ArrayList<>();
            animators.add(ObjectAnimator.ofFloat(_v, "X", location[0] - 25));
            animators.add(ObjectAnimator.ofFloat(_v, "Y", location[1] - 25));
            animators.add(ObjectAnimator.ofFloat(_v, "scaleX", scaleY));
            animators.add(ObjectAnimator.ofFloat(_v, "scaleY", scaleY));

            animationSet.playTogether(animators);
            animationSet.setDuration(350);



            animationSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    done.run();
                }
            });

            animationSet.start();
        } catch (Exception ex) {
            try {
                FileLog.e(ex);
                if (animationSet.isRunning()) {
                    animationSet.cancel();
                    onSelectListener.onSelect(selected, selectedPeer);
                }
            } catch (Exception ignore) {
                FileLog.e(ignore);
            }
        }

        SenderPickChannelAlert.hide();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
        //    heightMeasureSpec = Math.max(heightMeasureSpec, 0);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        public ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return chats.size();
        }

        @Override
        public int getItemViewType(int position) {
            return 0;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            view = new DialogCell(context, null);
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            long did = MessageObject.getPeerId(selectedPeer);
            if (holder.itemView instanceof GroupCreateUserCell) {
                GroupCreateUserCell cell = (GroupCreateUserCell) holder.itemView;
                Object object = cell.getObject();
                long id = 0;
                if (object != null) {
                    if (object instanceof TLRPC.Chat) {
                        id = -((TLRPC.Chat) object).id;
                    } else {
                        id = ((TLRPC.User) object).id;
                    }
                }
                cell.setChecked(did == id, false);
            } else {
                DialogCell cell = (DialogCell) holder.itemView;
                long id = cell.getCurrentDialog();
                cell.setChecked(did == id, false);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            long did = MessageObject.getPeerId(chats.get(position));
            DialogCell cell = (DialogCell) holder.itemView;
            cell.setDialog(did, did == MessageObject.getPeerId(selectedPeer), null);
        }
    }


    private class DialogCell extends FrameLayout {

        private final BackupImageView imageView;
        private final TextView nameTextView;
        private final TextView infoTextView;
        private final CheckBox2 checkBox;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();

        private long currentDialog;

        private final int currentAccount = UserConfig.selectedAccount;
        private final Theme.ResourcesProvider resourcesProvider;

        public DialogCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            setWillNotDraw(false);

            imageView = new BackupImageView(context);
            imageView.setRoundRadius(AndroidUtilities.dp(28));

            addView(imageView, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.LEFT, 5, 7, 0, 0));

            nameTextView = new TextView(context);
            nameTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameTextView.setMaxLines(1);
            nameTextView.setGravity(Gravity.TOP | Gravity.LEFT);
            nameTextView.setLines(1);
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 70, 6, 6, 0));

            infoTextView = new TextView(context);
            infoTextView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
            infoTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            infoTextView.setMaxLines(1);
            infoTextView.setGravity(Gravity.TOP | Gravity.LEFT);
            infoTextView.setLines(1);
            infoTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(infoTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 70, 30, 6, 0));

            checkBox = new CheckBox2(context, 21, resourcesProvider);
            checkBox.setColor(Theme.key_dialogRoundCheckBox, Theme.key_dialogBackground, Theme.key_dialogRoundCheckBoxCheck);
            checkBox.setDrawUnchecked(false);
            checkBox.setDrawBackgroundAsArc(4);
            checkBox.setProgressDelegate(progress -> {
                float scale = 1.0f - (1.0f - 0.857f) * checkBox.getProgress();
                imageView.setScaleX(scale);
                imageView.setScaleY(scale);
                invalidate();
            });
            addView(checkBox, LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 19, -40, 0, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(70), MeasureSpec.EXACTLY));
        }

        public void setDialog(long uid, boolean checked, CharSequence name) {
            TLRPC.User user;
            if (DialogObject.isUserDialog(uid)) {
                user = MessagesController.getInstance(currentAccount).getUser(uid);
                avatarDrawable.setInfo(user);
                if (name != null) {
                    nameTextView.setText(name);
                } else if (user != null) {
                    nameTextView.setText(ContactsController.formatName(user.first_name, user.last_name));
                    infoTextView.setText(LocaleController.getString("PersonalAccount", R.string.PersonalAccount));
                } else {
                    nameTextView.setText("");
                }
                imageView.setForUserOrChat(user, avatarDrawable);
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-uid);
                if (name != null) {
                    nameTextView.setText(name);
                } else if (chat != null) {
                    nameTextView.setText(chat.title);
                    infoTextView.setText(LocaleController.formatString("SubscribersCount", R.string.SubscribersCount, chat.participants_count));
                } else {
                    nameTextView.setText("");
                }
                avatarDrawable.setInfo(chat);
                imageView.setForUserOrChat(chat, avatarDrawable);
            }
            currentDialog = uid;
            checkBox.setChecked(checked, false);
        }

        public long getCurrentDialog() {
            return currentDialog;
        }

        public void setChecked(boolean checked, boolean animated) {
            checkBox.setChecked(checked, animated);
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            return super.drawChild(canvas, child, drawingTime);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int cx = imageView.getLeft() + imageView.getMeasuredWidth() / 2;
            int cy = imageView.getTop() + imageView.getMeasuredHeight() / 2;
            Theme.checkboxSquare_checkPaint.setColor(getThemedColor(Theme.key_dialogRoundCheckBox));
            Theme.checkboxSquare_checkPaint.setAlpha((int) (checkBox.getProgress() * 255));
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(24), Theme.checkboxSquare_checkPaint);
        }

        private int getThemedColor(String key) {
            Integer color = resourcesProvider != null ? resourcesProvider.getColor(key) : null;
            return color != null ? color : Theme.getColor(key);
        }
    }
}
