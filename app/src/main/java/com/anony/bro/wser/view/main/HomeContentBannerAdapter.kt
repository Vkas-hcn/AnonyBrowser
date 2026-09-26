package com.anony.bro.wser.view.main

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import com.anony.bro.wser.R
import com.anony.bro.wser.data.home.BannerVisual
import com.anony.bro.wser.data.home.HomeContentItem
import com.anony.bro.wser.databinding.ItemNewsBannerBinding

class HomeContentBannerAdapter(
    private val onClick: (HomeContentItem) -> Unit,
    private val onBindRemoteImage: (String, ImageView, () -> Unit) -> Unit,
    private val onItemsChanged: (Int) -> Unit,
) : RecyclerView.Adapter<HomeContentBannerAdapter.Holder>() {
    private val items = mutableListOf<HomeContentItem?>()
    private val candidates = mutableListOf<HomeContentItem?>()

    fun submit(content: List<HomeContentItem?>) {
        candidates.clear(); candidates.addAll(content)
        rebuildItems(); notifyDataSetChanged()
    }

    fun realCount() = items.size

    fun startPosition(): Int {
        val real = items.size
        if (real <= 1) return 0
        val mid = Int.MAX_VALUE / 2
        return mid - mid % real
    }

    fun realPosition(position: Int): Int {
        val real = items.size
        return if (real == 0) 0 else position % real
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemNewsBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (items.isEmpty()) return
        holder.bind(items[position % items.size])
    }

    override fun getItemCount() = when {
        items.isEmpty() -> 0
        items.size == 1 -> 1
        else -> Int.MAX_VALUE
    }

    inner class Holder(private val binding: ItemNewsBannerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeContentItem?) {
            binding.root.clipToOutline = true
            binding.newsImage.setImageDrawable(null)
            binding.newsImage.setBackgroundResource(R.drawable.bg_news_placeholder)
            binding.newsImage.clipToOutline = true
            binding.newsTitle.text = item?.title.orEmpty()
            binding.newsSource.text = item?.subtitle.orEmpty()
            binding.newsType.text = item?.type?.name.orEmpty()
            binding.root.setOnClickListener(if (item == null) null else { _ -> onClick(item) })
            when (val visual = item?.visual) {
                is BannerVisual.Local -> {
                    // Local artwork supplies its own visual; the item root supplies the shared rounded clip.
                    binding.newsImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    binding.newsImage.setBackgroundColor(Color.TRANSPARENT)
                    binding.newsImage.setImageResource(visual.resId)
                }
                is BannerVisual.Remote -> {
                    binding.newsImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    binding.newsImage.setBackgroundResource(R.drawable.bg_news_placeholder)
                    onBindRemoteImage(visual.url, binding.newsImage) {
                        if (candidates.remove(item)) {
                            rebuildItems()
                            notifyDataSetChanged()
                            onItemsChanged(realCount())
                        }
                    }
                }
                null -> Unit
            }
        }
    }

    private fun rebuildItems() {
        items.clear()
        items.addAll(candidates.take(MAX_BANNER_ITEMS))
    }

    private companion object {
        const val MAX_BANNER_ITEMS = 5
    }
}
