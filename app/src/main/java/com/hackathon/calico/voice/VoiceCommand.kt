package com.hackathon.calico.voice

import com.hackathon.calico.Exercise

data class VoiceCommand(val action: String, val exercise: Exercise? = null, val target: Int? = null, val label: String = "")

/** Commands are explicit app actions, never executable model output. */
object VoiceCommands {
    fun normalize(text: String) = text.lowercase().replace('-', ' ').replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    fun parse(raw: String): VoiceCommand? {
        var text = normalize(raw)
        if (Regex("\\b(don t|do not|never|should i|how do i|when should|why)\\b").containsMatchIn(text)) return null
        repeat(6) {
            text = text.replace(Regex("^(hey|okay|ok|calico|please|can you|can we|could you|would you|will you|i want you to|i want to|i would like to|let s|lets|just)\\s+"), "")
                .replace(Regex("\\s+(please|for me|right now|now|thanks)$"), "")
        }
        text = text.replace("work out", "workout").replace("work outs", "workouts")
            .replace(Regex("^(paws|pose) (workout|the workout|session)$"), "pause workout")
            .replace(Regex("\\b(the|a|an|my|this|current)\\s+(workout|session|exercise)\\b"), "$2")
            .replace(Regex("^(halt|end|finish|cancel) (workout|session)$"), "stop workout")
            .replace(Regex("^(stop|pause) (session|exercise)$"), "$1 workout")
        if (text in setOf("take a break", "take break", "give me a break", "hold on", "hold up", "wait", "pause it", "stop it")) return VoiceCommand("pause")
        if (text in setOf("keep going", "carry on", "continue workout", "continue session", "resume it", "play workout", "unpause", "unpause workout")) return VoiceCommand("resume")
        if (text in setOf("next", "next one", "skip this", "skip this one", "move on", "move to next exercise")) return VoiceCommand("skip")
        if (text in setOf("i m done", "im done", "i am done", "done", "stop working out")) return VoiceCommand("end")
        if (Regex("^(shut|turn) (?:the )?(workout|session) (down|off)$").matches(text)) return VoiceCommand("end")
        if (Regex("^(start|begin|do|launch|start up|kick off|get started with) (?:(?:a|the|my|any|some|today s|todays|default|quick|normal) )*(workout|session|training)$").matches(text) ||
            text in setOf("workout", "work out", "get started", "get me started", "start something", "start working out")) return VoiceCommand("today")
        if (text in setOf("open overview", "show overview", "overview", "show progress", "open progress")) return VoiceCommand("journey")
        if (text in setOf("open exercises", "show exercises", "exercises")) return VoiceCommand("exercises")
        val ones=listOf("zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen")
        val tens=mapOf("twenty" to 20,"thirty" to 30,"forty" to 40,"fifty" to 50,"sixty" to 60,"seventy" to 70,"eighty" to 80,"ninety" to 90)
        for((word,value) in tens) {
            for(i in 1..9) text=text.replace(Regex("\\b$word ${ones[i]}\\b"),(value+i).toString())
            text=text.replace(Regex("\\b$word\\b"),value.toString())
        }
        ones.forEachIndexed { i,word -> text=text.replace(Regex("\\b$word\\b"),i.toString()) }
        val actions = mapOf(
            "pause" to "pause", "pause workout" to "pause", "pause the workout" to "pause", "stop" to "pause",
            "resume" to "resume", "resume workout" to "resume", "resume the workout" to "resume", "play" to "resume", "continue" to "resume",
            "skip" to "skip", "skip exercise" to "skip", "next exercise" to "skip", "skip rest" to "skip",
            "end workout" to "end", "stop workout" to "end", "finish workout" to "end",
            "go home" to "home", "open home" to "home", "open workouts" to "home", "show workouts" to "home",
            "open journey" to "journey", "show journey" to "journey",
            "open scanner" to "scan", "scan room" to "scan", "open scan" to "scan",
            "open coach" to "coach", "open voice" to "voice",
            "start workout" to "today", "start session" to "today", "start todays workout" to "today", "start today s workout" to "today",
            "go back" to "back", "back" to "back", "close voice" to "close", "cancel" to "close",
            "disable hands free" to "disable", "stop listening" to "disable",
            "start recording" to "record", "stop recording" to "record_stop",
            "voice commands" to "help", "help" to "help", "what can you do" to "help"
        )
        actions[text]?.let { return VoiceCommand(it) }
        if(text in setOf("start","start again","resume session")) return VoiceCommand("resume")
        if(text in setOf("restart","restart exercise","restart this exercise","reset reps")) return VoiceCommand("restart")
        if(text in setOf("restart workout","restart session","restart the workout")) return VoiceCommand("restart_session")
        if(text in setOf("status","how many reps","what is my rep count","what exercise am i doing")) return VoiceCommand("status")
        val targetMatch=Regex("^(?:set|change|make)(?: the| my)? (?:rep count|reps|target|rep target|hold|hold time)(?: to)? (\\d+)(?: (reps|seconds|minutes))?$").matchEntire(text)
            ?: Regex("^(?:set|change)(?: the| my)? (?:target to )?(\\d+) (reps|seconds|minutes)$").matchEntire(text)
        if(targetMatch!=null) {
            val n=targetMatch.groupValues[1].toIntOrNull()
            val unit=targetMatch.groupValues[2].ifBlank { if("hold" in text) "seconds" else "reps" }
            val value=n?.toLong()?.times(if(unit=="minutes") 60 else 1)
            return if(value!=null && value in 1..300) VoiceCommand("target",target=value.toInt(),label=if(unit=="minutes") "seconds" else unit)
                else VoiceCommand("invalid",label="Choose a target between 1 and 300.")
        }
        if(Regex("^(change|switch)\\b").containsMatchIn(text)) {
            val exerciseText=text.replace(Regex("^(change|switch)(?: the| my)?(?: workout type| workout| exercise)?(?: to)? "),"")
            val parsed=parse("start $exerciseText")
            return if(parsed?.action=="exercise") parsed.copy(action="change_exercise")
                else VoiceCommand("invalid",label="Name an exercise, for example change exercise to squats.")
        }
        if (Regex("^(tap|click|press|hit) ").containsMatchIn(text)) {
            val label=text.substringAfter(' ').removePrefix("the ").removeSuffix(" button")
            val control=parse(label)
            if(control!=null && control.action in setOf("pause","resume","skip","end","today","restart","restart_session")) return control
            return VoiceCommand("button", label=label)
        }
        if (Regex("^(start|begin|do|show|preview)\\b").containsMatchIn(text)) {
            val normalized = text.replace("push ups", "pushups").replace("push up", "pushup")
                .replace("sit ups", "situps").replace("sit up", "situp").replace("pull ups", "pullups").replace("pull up", "pullup")
            val exercise = Exercise.entries.sortedByDescending { it.name.length }.firstOrNull {
                val name = it.name.lowercase().replace('_', ' ')
                Regex("\\b${Regex.escape(name)}s?\\b").containsMatchIn(normalized)
            }
            if (exercise != null) {
                val remaining=normalized.replace(Regex("\\b${Regex.escape(exercise.name.lowercase().replace('_',' '))}s?\\b"),"")
                if(Exercise.entries.any { Regex("\\b${Regex.escape(it.name.lowercase().replace('_',' '))}s?\\b").containsMatchIn(remaining) })
                    return VoiceCommand("invalid",label="Please start one exercise at a time, or name a saved session.")
                val numbers = Regex("\\b\\d+\\b").findAll(text).toList()
                if(numbers.size>1) return VoiceCommand("invalid",label="Please start one exercise and one target at a time.")
                var number = numbers.firstOrNull()?.value?.toIntOrNull()
                if(numbers.isNotEmpty() && number==null) return VoiceCommand("invalid",label="That target is too large.")
                val minutes=Regex("\\bminutes?\\b").containsMatchIn(text)
                val seconds=Regex("\\bseconds?\\b").containsMatchIn(text)
                if((minutes || seconds) && exercise.holdSec==0) return VoiceCommand("invalid",label="That exercise uses a rep target. Say the number of reps you want.")
                if(minutes && number!=null) {
                    if(number !in 1..5) return VoiceCommand("invalid",label="Choose a hold of up to five minutes.")
                    number*=60
                }
                if(number!=null && number !in 1..300) return VoiceCommand("invalid",label="Choose a target between 1 and 300.")
                return VoiceCommand(if(text.startsWith("show ") || text.startsWith("preview ")) "demo" else "exercise",exercise,number)
            }
            if(text.startsWith("start ") || text.startsWith("begin ")) return VoiceCommand("level",label=text.substringAfter(' ').removeSuffix(" session").removeSuffix(" workout"))
        }
        return null
    }

    /** A small spelling error may select one unique existing label; never guess destructive actions. */
    fun similarLabel(raw: String, labels: Set<String>): String? {
        val query=normalize(raw)
        if(query in labels) return query
        if(query.length<4) return null
        fun distance(a: String,b: String): Int {
            var prev=IntArray(b.length+1) { it }
            a.forEachIndexed { i,c ->
                val next=IntArray(b.length+1); next[0]=i+1
                b.forEachIndexed { j,d -> next[j+1]=minOf(next[j]+1,prev[j+1]+1,prev[j]+if(c==d) 0 else 1) }
                prev=next
            }
            return prev.last()
        }
        val matches=labels.filterNot { Regex("\\b(delete|clear|forget|reset|disable)\\b").containsMatchIn(it) }
            .filter { distance(query,it)<=if(query.length>=8) 2 else 1 }
        return matches.singleOrNull()
    }
}
