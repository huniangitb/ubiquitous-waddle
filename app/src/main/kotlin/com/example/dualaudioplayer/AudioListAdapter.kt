package com.example.dualaudioplayer
import android.view.LayoutInflater; import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil; import androidx.recyclerview.widget.ListAdapter; import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.dualaudioplayer.databinding.ListItemAudioBinding
class AudioListAdapter(private val onItemClicked: (AudioItem) -> Unit) : ListAdapter<AudioItem, AudioListAdapter.AudioViewHolder>(AudioDiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = AudioViewHolder(ListItemAudioBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) = holder.bind(getItem(position), onItemClicked)
    class AudioViewHolder(private val binding: ListItemAudioBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AudioItem, onItemClicked: (AudioItem) -> Unit) {
            binding.titleTextView.text = item.title; binding.artistTextView.text = item.artist
            binding.albumArtImageView.load(item.albumArtUri) { placeholder(android.R.drawable.ic_media_play); error(android.R.drawable.ic_media_play) }
            itemView.setOnClickListener { onItemClicked(item) }
        }
    }
    object AudioDiffCallback : DiffUtil.ItemCallback<AudioItem>() {
        override fun areItemsTheSame(oldItem: AudioItem, newItem: AudioItem): Boolean = oldItem.uri == newItem.uri
        override fun areContentsTheSame(oldItem: AudioItem, newItem: AudioItem): Boolean = oldItem == newItem
    }
}
