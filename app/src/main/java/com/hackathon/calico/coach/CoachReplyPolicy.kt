package com.hackathon.calico.coach

/** End at complete sentences rather than cutting speech off at an arbitrary word count. */
object CoachReplyPolicy {
    private val closing=Regex("(?i)\\b(let me know if|feel free to ask|i hope this helps)\\b")
    private val sentenceEnd=Regex("[.!?](?=\\s|$)")
    private val speakable=Regex("(?<![0-9])(?<!\\b[A-Za-z])[.!?](?=[\\s\"')\\]]|$)")
    fun detailed(question: String)=Regex("(?i)\\b(detail|details|explain|steps|compare)\\b").containsMatchIn(question)
    fun visible(text: String): String = closing.find(text)?.let { text.substring(0,it.range.first).trimEnd() } ?: text.trimStart()
    /**
     * Where speech can safely stop: the end of the last complete sentence. A digit or a
     * single letter before the dot is a list marker or an abbreviation, not an ending.
     * Returns 0 while no sentence has finished yet.
     */
    fun speakableCut(text: String): Int = speakable.findAll(text).lastOrNull()?.let { it.range.last + 1 } ?: 0

    fun finished(text: String, detailed: Boolean): Boolean {
        if(closing.containsMatchIn(text)) return true
        val trimmed=text.trimEnd()
        if(trimmed.lastOrNull() !in listOf('.', '!', '?')) return false
        return sentenceEnd.findAll(trimmed).count() >= if(detailed) 5 else 3
    }
}
