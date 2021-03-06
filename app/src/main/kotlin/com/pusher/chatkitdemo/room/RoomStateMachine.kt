//package com.pusher.chatkitdemo.room
//
//import com.pusher.chatkit.ChatManager
//import com.pusher.chatkit.CurrentUser
//import com.pusher.chatkit.Message
//import com.pusher.chatkit.Room
//import com.pusher.chatkit.rooms.RoomState.Item
//import com.pusher.chatkit.rooms.RoomState.Type.*
//import com.pusher.chatkitdemo.room.RoomState.*
//import com.pusher.platform.Cancelable
//import com.pusher.platform.Scheduler
//import com.pusher.platform.network.Promise
//import com.pusher.util.Result
//import com.pusher.util.flatMapResult
//import com.pusher.util.mapResult
//import com.pusher.util.recover
//import elements.Error
//import elements.Errors
//import elements.Subscription
//import kotlinx.coroutines.experimental.Job
//import kotlinx.coroutines.experimental.channels.Channel
//import kotlinx.coroutines.experimental.channels.consumeEach
//import kotlinx.coroutines.experimental.launch
//import java.util.concurrent.BlockingQueue
//import java.util.concurrent.ConcurrentLinkedQueue
//import java.util.concurrent.LinkedBlockingDeque
//
//class RoomStateMachine internal constructor(
//    internal val chatManager: ChatManager
//) {
//
//    private val listeners = ConcurrentLinkedQueue<(RoomState) -> Unit>()
//
//    private val updates = Channel<(RoomState) -> RoomState>(capacity = Channel.UNLIMITED)
//
//    private var _state: RoomState = Initial(::handleAction)
//    val state: RoomState
//        get() = _state
//
//    private val rootJob = Job()
//
//    init {
//        launch(parent = rootJob) {
//            updates.consumeEach { update ->
//                _state = update(state)
//                chatManager.logger.verbose("Room state updated to $state")
//                listeners.forEach { report -> report(state) }
//            }
//        }
//    }
//
//    internal fun handleAction(action: RoomAction): RoomTask = when (action) {
//        is RoomAction.LoadRoom -> LoadRoomTask(this, action.roomId)
//        is RoomAction.LoadMessages -> LoadMessagesTask(this, action.room)
//        is RoomAction.Join -> JoinTask(this)
//        is RoomAction.AddMessage -> AddMessageTask(this, action.room, action.message)
//        is RoomAction.Retry -> RetryTask(this)
//        is RoomAction.LoadMore -> LoadMoreTask(this, action.messageCount)
//    }.also { task: InternalRoomTask ->
//        chatManager.logger.verbose("Room action triggered state updated to $action")
//        backgroundScheduler.schedule(task)
//    }
//
//    internal fun update(stateUpdate: (RoomState) -> RoomState) {
//        updates.offer(stateUpdate)
//    }
//
//    fun onStateChanged(listener: (RoomState) -> Unit) {
//        listeners += listener
//        listener(state)
//    }
//
//    fun cancel() {
//        rootJob.cancel()
//    }
//
//    internal fun whenLoaded(block: RoomState.Ready.() -> RoomState) = { state: RoomState ->
//        when (state) {
//            is RoomState.Ready -> block(state)
//        // TODO: replace with soft failure
//            else -> Failed(::handleAction, Errors.other("Can't add message with state: $state"))
//        }
//    }
//
//}
//
//interface RoomTask : Cancelable
//
//private sealed class InternalRoomTask(
//    val machine: RoomStateMachine
//) : RoomTask, () -> Unit {
//
//    private var _active = true
//    protected val active = _active
//
//    override fun cancel() {
//        _active = false
//        onCancel()
//    }
//
//    abstract fun onCancel()
//
//}
//
//private class LoadRoomTask(
//    machine: RoomStateMachine,
//    val roomId: Int
//) : InternalRoomTask(machine) {
//
//    var loadRoomPromise: Promise<RoomState>? = null
//
//    override fun invoke() {
//        loadRoomPromise = machine.chatManager.currentUser
//            .flatMapResult { user -> loadRoom(user, roomId) }
//            .recover { Failed(machine::handleAction, it) }
//            .onReady { state -> machine.update { state } }
//    }
//
//    private fun loadRoom(user: CurrentUser, roomId: Int): Promise<Result<RoomState, Error>> =
//        machine.chatManager.roomService().fetchRoomBy(user.id, roomId)
//            .mapResult { room -> validateMembership(room, user.id) }
//
//    private fun validateMembership(room: Room, userId: String) = when {
//        room.memberUserIds.contains(userId) -> RoomLoaded(machine::handleAction, room)
//        else -> NoMembership(machine::handleAction, room)
//    }
//
//    override fun onCancel() {
//        loadRoomPromise?.cancel()
//    }
//
//}
//
//private class LoadMessagesTask(
//    machine: RoomStateMachine,
//    val room: Room
//) : InternalRoomTask(machine) {
//
//    private var subscription: Subscription? = null
//
//    override fun invoke() = TODO()
//
//    fun processNewMessage(message: Message): Promise<Result<Item.Loaded, Error>> =
//        message.asDetails().mapResult { details -> Item.Loaded(details) }
//            .onReady { result ->
//                machine.update { oldState ->
//                    result.map { item -> oldState + item }
//                        .recover { error -> Failed(machine::handleAction, error) }
//                }
//
//            }
//
//    private fun Message.asDetails(): Promise<Result<Item.Details, Error>> =
//        machine.chatManager.userService().userFor(this)
//            .mapResult { it.name ?: "???" }
//            .mapResult { userName -> Item.Details(userName, text ?: "") }
//
//    private operator fun RoomState.plus(item: RoomState.Item): RoomState = when (this) {
//        is RoomState.RoomLoaded -> Ready(machine::handleAction, room, listOf(item))
//        is RoomState.Ready -> copy(items = listOf(item) + items)
//        else -> Failed(machine::handleAction, prematureMessageError())
//    }
//
//    private fun prematureMessageError(): Error =
//        Errors.other("Received message before being ready. [${machine.state}]")
//
//    override fun onCancel() {
//        subscription?.unsubscribe()
//    }
//
//}
//
//private class JoinTask(machine: RoomStateMachine) : InternalRoomTask(machine) {
//    override fun invoke() = Unit
//    override fun onCancel() = Unit
//}
//
//private class AddMessageTask(machine: RoomStateMachine, val room: Room, val messageText: CharSequence) : InternalRoomTask(machine) {
//
//    var pendingPromise: Promise<Any>? = null
//
//    override fun invoke() {
//        TODO()
//    }
//
//    override fun onCancel() {
//        pendingPromise?.cancel()
//    }
//
//}
//
//private class RetryTask(machine: RoomStateMachine) : InternalRoomTask(machine) {
//    override fun invoke() = Unit
//    override fun onCancel() = Unit
//}
//
//private class LoadMoreTask(machine: RoomStateMachine, val messageCount: Int) : InternalRoomTask(machine) {
//    override fun invoke() = Unit
//    override fun onCancel() = Unit
//}
//
//internal sealed class RoomAction {
//    data class LoadRoom(val roomId: Int) : RoomAction()
//    data class LoadMessages(val room: Room) : RoomAction()
//    data class Join(val room: Room) : RoomAction()
//    data class AddMessage(val room: Room, val message: CharSequence) : RoomAction()
//    data class LoadMore(val messageCount: Int) : RoomAction()
//    object Retry : RoomAction()
//}
//
//private typealias Actor = (RoomAction) -> RoomTask
//
//sealed class RoomState() {
//
//    class Initial internal constructor(private val handle: Actor) : RoomState() {
//        fun loadRoom(roomId: Int): RoomTask = handle(RoomAction.LoadRoom(roomId))
//    }
//
//    data class Idle internal constructor(val roomId: Int) : RoomState()
//
//    data class NoMembership internal constructor(
//        private val handle: Actor,
//        val room: Room
//    ) : RoomState() {
//        fun join(): RoomTask = handle(RoomAction.Join(room))
//    }
//
//    data class RoomLoaded internal constructor(
//        private val handle: Actor,
//        val room: Room
//    ) : RoomState() {
//        fun loadMessages() = handle(RoomAction.LoadMessages(room))
//    }
//
//    data class Ready internal constructor(
//        private val handle: Actor,
//        val room: Room,
//        val items: List<Item>
//    ) : RoomState() {
//        fun addMessage(text: CharSequence): RoomTask = handle(RoomAction.AddMessage(room, text))
//        @JvmOverloads
//        fun loadMore(messageCount: Int = 10): RoomTask = handle(RoomAction.LoadMore(messageCount))
//    }
//
//    data class Failed internal constructor(private val handle: Actor, val error: Error) : RoomState() {
//        fun retry(): RoomTask = handle(RoomAction.Retry)
//    }
//
//    sealed class Item(val type: Type) {
//        abstract val details: Details
//
//        data class Loaded internal constructor(override val details: Details) : Item(Type.LOADED)
//        data class Pending internal constructor(override val details: Details) : Item(Type.PENDING)
//        data class Failed internal constructor(override val details: Details, val error: Error) : Item(Type.FAILED)
//
//        enum class Type {
//            LOADED, PENDING, FAILED
//        }
//
//        data class Details(val userName: CharSequence, val message: CharSequence)
//
//    }
//
//}
