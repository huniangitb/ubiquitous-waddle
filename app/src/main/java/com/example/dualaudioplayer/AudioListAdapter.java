package com.example.dualaudioplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class AudioListAdapter extends RecyclerView.Adapter<AudioListAdapter.ViewHolder> {
    private final List<AudioItem> audioList;
    private final OnItemClickListener listener;

    interface OnItemClickListener { void onItemClick(AudioItem item); }

    AudioListAdapter(List<AudioItem> audioList, OnItemClickListener listener) {
        this.audioList = audioList;
        this.listener = listener;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_audio, parent, false));
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        holder.bind(audioList.get(position), listener);
    }

    @Override
    public int getItemCount() { return audioList.size(); }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView titleTextView;
        final TextView artistTextView;
        final ImageView albumArtImageView;
        
        ViewHolder(View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.titleTextView);
            artistTextView = itemView.findViewById(R.id.artistTextView);
            albumArtImageView = itemView.findViewById(R.id.albumArtImageView);
        }
        void bind(final AudioItem item, final OnItemClickListener listener) {
            titleTextView.setText(item.title);
            artistTextView.setText(item.artist);
            if (item.getAlbumArtUri() != null) {
                albumArtImageView.setImageURI(item.getAlbumArtUri());
            } else {
                albumArtImageView.setImageResource(android.R.drawable.ic_media_play);
            }
            itemView.setOnClickListener(v -> listener.onItemClick(item));
        }
    }
}
