package com.example.ftp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R

class FtpViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val ivIcon: ImageView = view.findViewById(R.id.iv_ftp_icon)
    val tvName: TextView = view.findViewById(R.id.tv_ftp_name)
    val tvInfo: TextView = view.findViewById(R.id.tv_ftp_info)
    val tvHint: TextView = view.findViewById(R.id.tv_ftp_action_hint)
}

class FtpAdapter(
    private val items: List<FtpFileItem>,
    private val onItemClicked: (FtpFileItem) -> Unit
) : RecyclerView.Adapter<FtpViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FtpViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ftp_file, parent, false)
        return FtpViewHolder(view)
    }

    override fun onBindViewHolder(holder: FtpViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvInfo.text = item.formattedSize

        if (item.isDirectory) {
            holder.ivIcon.setImageResource(R.drawable.ic_folder)
            holder.tvHint.text = "▶ Enter"
        } else if (item.isVideo) {
            holder.ivIcon.setImageResource(R.drawable.ic_video_placeholder)
            holder.tvHint.text = "▶ Play / Transfer"
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_file)
            holder.tvHint.text = "Transfer"
        }

        holder.itemView.setOnClickListener {
            onItemClicked(item)
        }
    }

    override fun getItemCount(): Int = items.size
}
