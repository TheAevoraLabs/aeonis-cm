package com.qc.aeonis.companion.ui

/**
 * Client-side dialogue state and typewriter sequencer for the companion's floating HUD card.
 */
object CompanionSpeechState {

    data class DialogueMessage(
        val speakerName: String,
        val fullText: String,
        val emotion: String,
        val durationTicks: Int = 140
    )

    private val queue = ArrayDeque<DialogueMessage>()
    var currentMessage: DialogueMessage? = null
        private set
    var displayTicks: Int = 0
        private set
    var visibleCharCount: Int = 0
        private set

    fun push(speakerName: String, text: String, emotion: String = "FRIENDLY") {
        val msg = DialogueMessage(speakerName, text, emotion)
        if (currentMessage == null) {
            currentMessage = msg
            displayTicks = msg.durationTicks
            visibleCharCount = 0
        } else {
            queue.addLast(msg)
        }
    }

    fun tick() {
        val msg = currentMessage ?: return
        if (displayTicks > 0) {
            displayTicks--
            val targetLen = msg.fullText.length
            if (visibleCharCount < targetLen) {
                visibleCharCount = (visibleCharCount + 2).coerceAtMost(targetLen)
            }
        } else {
            if (queue.isNotEmpty()) {
                currentMessage = queue.removeFirst()
                displayTicks = currentMessage!!.durationTicks
                visibleCharCount = 0
            } else {
                currentMessage = null
                visibleCharCount = 0
            }
        }
    }

    fun clear() {
        queue.clear()
        currentMessage = null
        displayTicks = 0
        visibleCharCount = 0
    }
}
