package com.anony.bro.wser.view.vpn

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.anony.bro.wser.R
import com.anony.bro.wser.data.vpn.VpnServer
import com.anony.bro.wser.data.vpn.countryName
import com.anony.bro.wser.data.vpn.flagIconRes
import com.anony.bro.wser.databinding.ItemVpnNodeBinding

class VpnNodeListAdapter(
    private val onItemClick: (VpnServer) -> Unit,
) : RecyclerView.Adapter<VpnNodeListAdapter.NodeViewHolder>() {

    private val items = mutableListOf<VpnServer>()
    private var selectedServer: VpnServer? = null

    fun submit(servers: List<VpnServer>, selected: VpnServer?) {
        items.clear()
        items.addAll(servers)
        selectedServer = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val binding = ItemVpnNodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return NodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class NodeViewHolder(
        private val binding: ItemVpnNodeBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(server: VpnServer) {
            binding.imgFlag.setImageResource(server.flagIconRes())
            binding.tvNodeName.text = server.locationLabel
            val selected = server.isSameEndpoint(selectedServer)
            binding.imgCheck.setImageResource(
                if (selected) R.drawable.ic_check else R.drawable.ic_dis_check,
            )
            binding.root.setOnClickListener { onItemClick(server) }
        }
    }
}

internal fun VpnServer.isSameEndpoint(other: VpnServer?): Boolean {
    if (other == null) return false
    return locationLabel == other.locationLabel && username == other.username
}
