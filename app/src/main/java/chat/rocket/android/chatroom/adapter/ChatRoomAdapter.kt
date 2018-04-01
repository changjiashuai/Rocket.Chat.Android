package chat.rocket.android.chatroom.adapter

import android.support.v7.widget.RecyclerView
import android.view.MenuItem
import android.view.ViewGroup
import chat.rocket.android.R
import chat.rocket.android.chatroom.presentation.ChatRoomPresenter
import chat.rocket.android.chatroom.viewmodel.*
import chat.rocket.android.util.extensions.inflate
import chat.rocket.android.widget.emoji.EmojiReactionListener
import chat.rocket.core.model.Message
import chat.rocket.core.model.isSystemMessage
import timber.log.Timber
import java.security.InvalidParameterException

class ChatRoomAdapter(
        private val roomType: String,
        private val roomName: String,
        private val presenter: ChatRoomPresenter?,
        private val enableActions: Boolean = true,
        private val reactionListener: EmojiReactionListener? = null
) : RecyclerView.Adapter<BaseViewHolder<*>>() {

    private val dataSet = ArrayList<BaseViewModel<*>>()

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder<*> {
        return when (viewType.toViewType()) {
            BaseViewModel.ViewType.MESSAGE -> {
                val view = parent.inflate(R.layout.item_message)
                MessageViewHolder(view, actionsListener, reactionListener)
            }
            BaseViewModel.ViewType.IMAGE_ATTACHMENT -> {
                val view = parent.inflate(R.layout.message_attachment)
                ImageAttachmentViewHolder(view, actionsListener, reactionListener)
            }
            BaseViewModel.ViewType.AUDIO_ATTACHMENT -> {
                val view = parent.inflate(R.layout.message_attachment)
                AudioAttachmentViewHolder(view, actionsListener, reactionListener)
            }
            BaseViewModel.ViewType.VIDEO_ATTACHMENT -> {
                val view = parent.inflate(R.layout.message_attachment)
                VideoAttachmentViewHolder(view, actionsListener, reactionListener)
            }
            BaseViewModel.ViewType.URL_PREVIEW -> {
                val view = parent.inflate(R.layout.message_url_preview)
                UrlPreviewViewHolder(view, actionsListener, reactionListener)
            }
            BaseViewModel.ViewType.MESSAGE_ATTACHMENT -> {
                val view = parent.inflate(R.layout.item_message_attachment)
                MessageAttachmentViewHolder(view, actionsListener, reactionListener)
            }
            else -> {
                throw InvalidParameterException("TODO - implement for ${viewType.toViewType()}")
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return dataSet[position].viewType
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    override fun onBindViewHolder(holder: BaseViewHolder<*>, position: Int) {
        if (holder !is MessageViewHolder) {
            if (position + 1 < itemCount) {
                val messageAbove = dataSet[position + 1]
                if (messageAbove.messageId == dataSet[position].messageId) {
                    messageAbove.nextDownStreamMessage = dataSet[position]
                }
            }
        } else {
            if (position == 0) {
                dataSet[0].nextDownStreamMessage = null
            } else if (position - 1 > 0) {
                if (dataSet[position - 1].messageId != dataSet[position].messageId) {
                    dataSet[position].nextDownStreamMessage = null
                }
            }
        }

        when (holder) {
            is MessageViewHolder -> holder.bind(dataSet[position] as MessageViewModel)
            is ImageAttachmentViewHolder -> holder.bind(dataSet[position] as ImageAttachmentViewModel)
            is AudioAttachmentViewHolder -> holder.bind(dataSet[position] as AudioAttachmentViewModel)
            is VideoAttachmentViewHolder -> holder.bind(dataSet[position] as VideoAttachmentViewModel)
            is UrlPreviewViewHolder -> holder.bind(dataSet[position] as UrlPreviewViewModel)
            is MessageAttachmentViewHolder -> holder.bind(dataSet[position] as MessageAttachmentViewModel)
        }
    }

    override fun getItemId(position: Int): Long {
        val model = dataSet[position]
        return when (model) {
            is MessageViewModel -> model.messageId.hashCode().toLong()
            is BaseFileAttachmentViewModel -> model.id
            else -> return position.toLong()
        }
    }

    fun appendData(dataSet: List<BaseViewModel<*>>) {
        val previousDataSetSize = this.dataSet.size
        this.dataSet.addAll(dataSet)
        notifyItemChanged(previousDataSetSize, dataSet.size)
    }

    fun prependData(dataSet: List<BaseViewModel<*>>) {
        val item = dataSet.firstOrNull { newItem ->
            this.dataSet.indexOfFirst { it.messageId == newItem.messageId && it.viewType == newItem.viewType } > -1
        }
        if (item == null) {
            var index = -1
            dataSet.forEach {
                val previewIndex = findPreviewIndex(it)
                if (previewIndex > -1) {
                    index = previewIndex
                    this.dataSet[index] = it
                    notifyItemChanged(index)
                }
            }
            if (index == -1) {
                this.dataSet.addAll(0, dataSet)
                notifyItemRangeInserted(0, dataSet.size)
            } else {
                val message = dataSet.firstOrNull { it is MessageViewModel }
                if (message != null && index + 1 < this.dataSet.size) {
                    this.dataSet[index + 1] = message
                    notifyItemChanged(index + 1)
                }
            }
        } else {
            if (item is BaseFileAttachmentViewModel) {
                val first = dataSet.first() as BaseFileAttachmentViewModel
                item.progress = first.progress
                notifyItemChanged(this.dataSet.indexOfFirst { it.messageId == item.messageId })
            }
        }
    }

    fun updateUploadPreviewItem(messageId: String, progress: Long, fileSize: Long, completed: Boolean = false) {
        val index = dataSet.indexOfFirst { it.messageId == messageId }
        if (index > -1) {
            val item = dataSet[index]
            item as BaseFileAttachmentViewModel
            item.completed = completed
            item.progress = progress
            item.fileSize = fileSize
            notifyItemChanged(index)
        }
    }

    fun updateItem(message: BaseViewModel<*>) {
        var index = dataSet.indexOfLast { it.messageId == message.messageId }
        val indexOfNext = dataSet.indexOfFirst { it.messageId == message.messageId }
        Timber.d("index: $index")
        if (index > -1) {
            dataSet[index] = message
            dataSet.forEachIndexed { index, viewModel ->
                if (viewModel.messageId == message.messageId) {
                    if (viewModel.nextDownStreamMessage == null) {
                        viewModel.reactions = message.reactions
                    }
                    notifyItemChanged(index)
                }
            }
            // Delete message only if current is a system message update, i.e.: Message Removed
            if (message.message.isSystemMessage() && indexOfNext > -1 && indexOfNext != index) {
                dataSet.removeAt(indexOfNext)
                notifyItemRemoved(indexOfNext)
            }
        }
    }

    private fun findPreviewIndex(message: BaseViewModel<*>): Int {
        if (message is BaseFileAttachmentViewModel) {
            return dataSet.indexOfFirst {
                if (it !is BaseFileAttachmentViewModel) {
                    return@indexOfFirst false
                }
                val hash = it.attachmentUrl
                it.completed && it.attachmentTitle == message.attachmentTitle && it.isPreview
            }
        }
        return -1
    }

    fun removeItem(messageId: String) {
        val index = dataSet.indexOfFirst { it.messageId == messageId }
        if (index > -1) {
            val oldSize = dataSet.size
            val newSet = dataSet.filterNot { it.messageId == messageId }
            dataSet.clear()
            dataSet.addAll(newSet)
            val newSize = dataSet.size
            notifyItemRangeRemoved(index, oldSize - newSize)
        }
    }

    override fun onViewRecycled(holder: BaseViewHolder<*>?) {
        (holder as? VideoAttachmentViewHolder)?.resetState()
        super.onViewRecycled(holder)
    }

    val actionsListener = object : BaseViewHolder.ActionsListener {
        override fun isActionsEnabled(): Boolean = enableActions

        override fun onActionSelected(item: MenuItem, message: Message) {
            message.apply {
                when (item.itemId) {
                    R.id.action_menu_msg_delete -> presenter?.deleteMessage(roomId, id)
                    R.id.action_menu_msg_quote -> presenter?.citeMessage(roomType, roomName, id, false)
                    R.id.action_menu_msg_reply -> presenter?.citeMessage(roomType, roomName, id, true)
                    R.id.action_menu_msg_copy -> presenter?.copyMessage(id)
                    R.id.action_menu_msg_edit -> presenter?.editMessage(roomId, id, message.message)
                    R.id.action_menu_msg_pin_unpin -> {
                        with(item) {
                            if (!isChecked) {
                                presenter?.pinMessage(id)
                            } else {
                                presenter?.unpinMessage(id)
                            }
                        }
                    }
                    R.id.action_menu_msg_react -> presenter?.showReactions(id)
                    else -> TODO("Not implemented")
                }
            }
        }
    }
}