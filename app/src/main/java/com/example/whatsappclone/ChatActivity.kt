package com.example.whatsappclone

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.whatsappclone.utils.KeyboardVisibilityUtil
import com.example.whatsappclone.utils.isSameDayAs
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import com.squareup.picasso.Picasso
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.EmojiPopup
import com.vanniktech.emoji.google.GoogleEmojiProvider
import kotlinx.android.synthetic.main.activity_chat.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val UID = "uid"
const val NAME = "name"
const val IMAGE = "photo"

class ChatActivity : AppCompatActivity() {

    private val friendId: String? by lazy {
        intent.getStringExtra(UID)
    }
    private val name:String? by lazy {
        intent.getStringExtra(NAME)
    }
    private val image:String? by lazy {
        intent.getStringExtra(IMAGE)
    }
    private val mCurrentUid:String by lazy {
        FirebaseAuth.getInstance().uid!!
    }
    private val db:FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance()
    }
    lateinit var currentUser: User
    private val messages = mutableListOf<ChatEvent>()
    lateinit var chatAdapter: ChatAdapter
    private lateinit var keyboardVisibilityHelper: KeyboardVisibilityUtil
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmojiManager.install(GoogleEmojiProvider())
        setContentView(R.layout.activity_chat)
        keyboardVisibilityHelper = KeyboardVisibilityUtil(rootView) {
            msgRv.scrollToPosition(messages.size - 1)
        }
        FirebaseFirestore.getInstance().collection("users").document(mCurrentUid).get()
            .addOnSuccessListener {
                currentUser = it.toObject(User::class.java)!!
            }
        nameTv.text = name
        Picasso.get().load(image).into(userImgView)
        val emojiPopup = EmojiPopup.Builder.fromRootView(rootView).build(msgEdtv)
        smileBtn.setOnClickListener {
            emojiPopup.toggle()
        }
        chatAdapter = ChatAdapter(messages,mCurrentUid)
        msgRv.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = chatAdapter
        }
        swipeToLoad.setOnRefreshListener {
            val workerScope = CoroutineScope(Dispatchers.Main)
            workerScope.launch {
                delay(2000)
                swipeToLoad.isRefreshing = false
            }
        }
        listenToMessages()

        sendBtn.setOnClickListener {
            msgEdtv.text?.let {
                if(it.isNotEmpty()){
                    sendMessage(it.toString())
                    it.clear()
                }
            }
        }
        updateReadCount()
    }

    private fun updateReadCount() {
        getInbox(friendId!!,mCurrentUid).child("count").setValue(0)
    }

    private fun listenToMessages() {
        getMessages(friendId!!)
            .orderByKey()
            .addChildEventListener(object :ChildEventListener{
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val msg = snapshot.getValue(Message::class.java)
                    addMessage(msg!!)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    TODO("Not yet implemented")
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {
                    TODO("Not yet implemented")
                }

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                    TODO("Not yet implemented")
                }

                override fun onCancelled(error: DatabaseError) {
                    TODO("Not yet implemented")
                }

            })
    }

    private fun addMessage(msg: Message) {
        val eventBefore =  messages.lastOrNull()
        if(eventBefore!=null&&!eventBefore.sentAt.isSameDayAs(msg.sentAt)||eventBefore==null){
            messages.add(
                DateHeader(
                    msg.sentAt,context = this
                )
            )
        }
        messages.add(msg)
        chatAdapter.notifyItemInserted(messages.size-1)
        msgRv.scrollToPosition(messages.size-1)
    }

    private fun sendMessage(msg: String) {
        val id = getMessages(friendId!!).push().key
        checkNotNull(id){"Cannot be null"}
        val msgMap = Message(msg,mCurrentUid,id)
        getMessages(friendId!!).child(id).setValue(msgMap)
            .addOnSuccessListener {
                Log.i("CHATS","completed")
            }
            .addOnFailureListener {
                Log.i("CHATS",it.localizedMessage)
            }
        updateLastMessage(msgMap)
    }

    private fun updateLastMessage(msgMap: Message) {
        val inboxMap = Inbox(
            msgMap.msg,
            friendId!!,
            name!!,
            image!!,
            count = 0
        )
        getInbox(mCurrentUid,friendId!!).setValue(inboxMap).addOnSuccessListener {
            getInbox(friendId!!,mCurrentUid).addListenerForSingleValueEvent(object : ValueEventListener{
                override fun onDataChange(snapshot: DataSnapshot) {
                    val value = snapshot.getValue(Inbox::class.java)
                    inboxMap.apply {
                        from = msgMap.senderId
                        name = currentUser.name
                        image = currentUser.thumbImage
                        count = 1
                    }
                    value?.let {
                        if(it.from == msgMap.senderId){
                            inboxMap.count = value.count+1
                        }
                    }
                    getInbox(friendId!!,mCurrentUid).setValue(inboxMap)
                }

                override fun onCancelled(error: DatabaseError) {
                }

            })
        }
    }
    private fun markAsRead(){
        getInbox(friendId!!,mCurrentUid).child("count").setValue(0)
    }
    private fun getMessages(friendId: String) = db.reference.child("messages/{${getId(friendId)}")

    private fun getInbox(toUser:String,fromUser:String) = db.reference.child("chats/{$toUser}/{$fromUser}")

    private fun getId(friendId:String):String{
        return if(friendId>mCurrentUid){
            mCurrentUid+friendId
        }
        else{
            friendId+mCurrentUid
        }
    }
    override fun onResume() {
        super.onResume()
        rootView.viewTreeObserver
            .addOnGlobalLayoutListener(keyboardVisibilityHelper.visibilityListener)
    }


    override fun onPause() {
        super.onPause()
        rootView.viewTreeObserver
            .removeOnGlobalLayoutListener(keyboardVisibilityHelper.visibilityListener)
    }

    companion object {

        fun createChatActivity(context: Context, id: String, name: String, image: String): Intent {
            val intent = Intent(context, ChatActivity::class.java)
            intent.putExtra(UID, id)
            intent.putExtra(NAME, name)
            intent.putExtra(IMAGE, image)

            return intent
        }
    }
}