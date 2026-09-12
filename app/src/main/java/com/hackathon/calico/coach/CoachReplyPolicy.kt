package com.hackathon.calico.coach

/** End at complete sentences rather than cutting speech off at an arbitrary word count. */
object CoachReplyPolicy {
    private val closing=Regex("(?i)\\b(let me know if|feel free to ask|i hope this helps)\\b")
    private val sentenceEnd=Regex("[.!?](?=\\s|$)")
    fun detailed(question: String)=Regex("(?i)\\b(detail|details|explain|steps|compare)\\b").containsMatchIn(question)
    fun visible(text: String): String = closing.find(text)?.let { text.substring(0,it.range.first).trimEnd() } ?: text.trimStart()
    fun finished(text: String, detailed: Boolean): Boolean {
        if(closing.containsMatchIn(text)) return true
        val trimmed=text.trimEnd()
        if(trimmed.lastOrNull() !in listOf('.', '!', '?')) return false
        return sentenceEnd.findAll(trimmed).count() >= if(detailed) 5 else 3
    }
}
