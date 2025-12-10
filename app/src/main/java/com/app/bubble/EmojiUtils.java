package com.app.bubble;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class EmojiUtils {

    // A clean list of popular modern Unicode Emojis (No text labels)
    public static final String[] EMOJIS = {
        // Smileys & Emotions
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰",
        "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩", "🥳", "😏",
        "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠",
        "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🤭", "🤫", "🤥",
        "😶", "😐", "😑", "😬", "🙄", "😯", "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐",
        "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈", "👿", "👹", "👺", "🤡", "💩", "👻",
        "💀", "☠️", "👽", "👾", "🤖", "🎃", "😺", "😸", "😹", "😻", "😼", "😽", "🙀", "😿", "😾",

        // Hands & Body
        "👋", "🤚", "🖐", "✋", "🖖", "👌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕",
        "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✍️", "💅",
        "🤳", "💪", "🦵", "🦶", "👂", "🦻", "👃", "🧠", "🦷", "🦴", "👀", "👁", "👅", "👄", "💋", "🩸",

        // Animals & Nature
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐽", "🐸", "🐵",
        "🙈", "🙉", "🙊", "🐒", "🐔", "🐧", "🐦", "🐤", "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗",
        "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞", "🐜", "🦟", "🦗", "🕷", "🕸", "🐢", "🐍", "🦎", "🦖",
        "🦕", "🐙", "🦑", "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐬", "🐳", "🐋", "🦈", "🐊", "🐅", "🐆",
        "🦓", "🦍", "🦧", "🐘", "🦛", "🦏", "🐪", "🐫", "🦒", "🦘", "🐃", "🐂", "🐄", "🐖", "🐏",
        "🐑", "🦙", "🐐", "🦌", "🐕", "🐩", "🦮", "🐕‍🦺", "🐈", "🐓", "🦃", "🦚", "🦜", "🦢", "🦩", "🕊",
        "🐇", "🦝", "🦨", "🦡", "🦦", "🦥", "🐁", "🐀", "🐿", "🦔", "🌵", "🌲", "🌳", "🌴", "🌱", "🌿",
        "☘️", "🍀", "🎍", "🎋", "🍃", "🍂", "🍁", "🍄", "🐚", "🌾", "💐", "🌷", "🌹", "🥀", "🌺", "🌸",
        "🌼", "🌻", "🌞", "🌝", "🌛", "🌜", "🌚", "🌕", "🌖", "🌗", "🌘", "🌑", "🌒", "🌓", "🌔", "🌙",
        "🌎", "🌍", "🌏", "🪐", "💫", "⭐️", "🌟", "✨", "⚡️", "☄️", "💥", "🔥", "🌪", "🌈", "☀️", "🌤",
        "⛅️", "🌥", "☁️", "🌦", "🌧", "⛈", "🌩", "🌨", "❄️", "☃️", "⛄️", "🌬", "💨", "💧", "💦", "☔️",
        "☂️", "🌊",

        // Objects & Symbols
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖",
        "💘", "💝", "💯", "💢", "💥", "💫", "💦", "💨", "🕳", "💣", "💬", "👁️‍🗨️", "🗨", "🗯", "💭", "💤",
        "💡", "🔦", "🕯", "🪔", "📔", "📕", "📖", "📗", "📘", "📙", "📚", "📓", "📒", "📃", "📜", "📄",
        "📰", "🗞", "📑", "🔖", "🏷", "💰", "💴", "💵", "💶", "💷", "💸", "💳", "🧾", "💹", "✉️", "📧",
        "📨", "📩", "📤", "📥", "📦", "📫", "📪", "📬", "📭", "📮", "🗳", "✏️", "✒️", "🖋", "🖊", "🖌",
        "🖍", "📝", "💼", "📁", "📂", "🗂", "📅", "📆", "🗒", "🗓", "📇", "📈", "📉", "📊", "📋", "📌",
        "📍", "📎", "🖇", "📏", "📐", "✂️", "🗃", "🗄", "🗑", "🔒", "🔓", "🔏", "🔐", "🔑", "🗝", "🔨",
        "🪓", "⛏", "⚒", "🛠", "🗡", "⚔️", "🔫", "🪃", "🏹", "🛡", "🔧", "🔩", "⚙️", "🗜", "⚖️", "🔗",
        "⛓", "🪝", "🧰", "🧲", "🪜", "⚗️", "🧪", "🧫", "🧬", "🔬", "🔭", "📡", "💉", "🩸", "💊", "🩹",
        "🩺", "🚪", "🪑", "🚽", "🚿", "🛁", "🪒", "🧴", "🧷", "🧹", "🧺", "🧻", "🧼", "🧽", "🧯", "🛒"
    };

    /**
     * Interface to handle emoji clicks in the Service
     */
    public interface EmojiListener {
        void onEmojiClick(String emoji);
    }

    /**
     * Sets up the Emoji list with the adapter and click listeners.
     * Uses RecyclerView for Horizontal Scrolling.
     * 
     * @param context The application context
     * @param rootView The root view of the emoji palette layout
     * @param listener The callback to handle emoji selection
     */
    public static void setupEmojiGrid(final Context context, View rootView, final EmojiListener listener) {
        RecyclerView recyclerView = rootView.findViewById(R.id.emoji_grid);
        
        if (recyclerView == null) return;

        // FIX: Set spanCount to 4. In Horizontal Grid, this creates 4 Rows.
        // This solves the "too many rows" stacking issue.
        GridLayoutManager layoutManager = new GridLayoutManager(context, 4, GridLayoutManager.HORIZONTAL, false);
        recyclerView.setLayoutManager(layoutManager);

        // Set Adapter
        EmojiAdapter adapter = new EmojiAdapter(EMOJIS, listener);
        recyclerView.setAdapter(adapter);

        Button btnSmileys = rootView.findViewById(R.id.tab_smileys);
        if (btnSmileys != null) {
            btnSmileys.setOnClickListener(v -> {
                recyclerView.scrollToPosition(0);
            });
        }
    }

    /**
     * Inner Adapter Class for the RecyclerView
     */
    private static class EmojiAdapter extends RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder> {

        private final String[] data;
        private final EmojiListener listener;

        public EmojiAdapter(String[] data, EmojiListener listener) {
            this.data = data;
            this.listener = listener;
        }

        @NonNull
        @Override
        public EmojiViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            
            // FIX: Dynamic Width Calculation for Professional Look
            // Calculate width to fit exactly 7 emojis per screen width
            int screenWidth = parent.getResources().getDisplayMetrics().widthPixels;
            int itemWidth = screenWidth / 7;
            
            // Set Height to 130px (approx 45dp) to allow 4 clear rows
            tv.setLayoutParams(new ViewGroup.LayoutParams(itemWidth, 130)); 
            
            // FIX: Set Text Size to 25 (Balanced, not too big)
            tv.setTextSize(25); 
            
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(Color.BLACK);
            return new EmojiViewHolder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull EmojiViewHolder holder, int position) {
            final String emoji = data[position];
            holder.textView.setText(emoji);
            
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onEmojiClick(emoji);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return data.length;
        }

        static class EmojiViewHolder extends RecyclerView.ViewHolder {
            TextView textView;

            EmojiViewHolder(View itemView) {
                super(itemView);
                this.textView = (TextView) itemView;
            }
        }
    }
}