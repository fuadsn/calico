package com.hackathon.calico.voice

import com.hackathon.calico.Exercise

/** [expand] asks a demo to open with the 3D model already at full size rather than in the corner. */
data class VoiceCommand(val action: String, val exercise: Exercise? = null, val target: Int? = null,
    val label: String = "", val expand: Boolean = false)

/** Commands are explicit app actions. The model may pick one from the fixed list, never write one. */
object VoiceCommands {
    fun normalize(text: String) = text.lowercase().replace('-', ' ').replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Maps the model's JSON intent onto the same commands [parse] produces, with the same
     * bounds. Malformed JSON, an unknown action, an unknown exercise or "question" all return
     * null, so the request falls through to the coach as a question.
     */
    fun fromIntent(json: String): VoiceCommand? {
        val obj=try { org.json.JSONObject(json.substring(json.indexOf('{'),json.lastIndexOf('}')+1)) } catch(_: Exception) { return null }
        val target=obj.optInt("target",Int.MIN_VALUE).takeIf { it!=Int.MIN_VALUE }
        val unit=obj.optString("unit").takeIf { it=="seconds" || it=="reps" }.orEmpty()
        val tooLarge=VoiceCommand("invalid",label="Choose a target between 1 and 300.")
        fun exercise(): Exercise? = obj.optString("exercise").takeIf { it.isNotBlank() }?.let { raw ->
            val words=normalize(raw).replace("push ups","pushups").replace("push up","pushup").replace("sit ups","situps")
                .replace("sit up","situp").replace("pull ups","pullups").replace("pull up","pullup")
            val name=words.replace(' ','_').uppercase()
            Exercise.entries.firstOrNull { it.name==name || it.name==name.removeSuffix("S") } ?: similarExercise(words)
        }
        return when(obj.optString("action")) {
            "start_exercise" -> { val e=exercise() ?: return null; if(target!=null && target !in 1..300) tooLarge else VoiceCommand("exercise",e,target) }
            "start_session" -> { val name=normalize(obj.optString("name")); if(name.isBlank() || name.startsWith("today")) VoiceCommand("today") else VoiceCommand("level",label=name) }
            "change_exercise" -> exercise()?.let { if(target!=null && target !in 1..300) tooLarge else VoiceCommand("change_exercise",it,target) }
            "set_target" -> target?.let { if(it in 1..300) VoiceCommand("target",target=it,label=unit) else tooLarge }
            "adjust_target" -> obj.optInt("delta",0).takeIf { it!=0 && it in -300..300 }?.let { VoiceCommand("adjust",target=it,label=unit) }
            "pause","resume","skip","end","restart","restart_session","status","back","exit" -> VoiceCommand(obj.optString("action"))
            "demo" -> VoiceCommand("demo",exercise(),expand=true)
            "open" -> obj.optString("screen").takeIf { it in setOf("home","journey","exercises","scan","coach") }?.let { VoiceCommand(it) }
            else -> null
        }
    }

    /** Saying the name on its own is a reset: drop whatever came before and listen again. */
    private val wake = Regex("^(hey |hi |ok |okay |yo )?(calico|calico calico)( again| are you there| you there| listen( again)?| listening| hello| hi| yes)?$")
    fun isWakeMention(raw: String) = wake.matches(normalize(raw))

    /** Said on their own these always mean "get me out of here", whatever screen is open. */
    private val exits = setOf("close", "exit", "quit", "leave", "stop", "dismiss", "escape", "abort",
        "get out", "get me out", "go out", "close this", "close that", "exit this", "close it", "exit it",
        "close app", "exit app", "quit app", "leave app", "close the app", "exit the app", "quit the app",
        "close calico", "exit calico", "stop calico", "quit calico", "close everything", "shut it down",
        "go home", "home", "homepage", "home page", "main menu", "main page", "main screen",
        "take me home", "go to home", "back to home", "back home", "open home", "show home",
        "never mind", "nevermind", "forget it", "cancel", "cancel that", "i m out", "im out")

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
        // Checked before the control words below, so a bare "stop" leaves the screen rather than
        // pausing, while "stop workout" still ends the session.
        if (text in exits) return VoiceCommand("exit")
        if (Regex("\\b(close|exit|quit|leave|stop|dismiss|escape)\\b").containsMatchIn(text) &&
            !Regex("\\b(workout|session|recording|download|listening)\\b").containsMatchIn(text))
            return VoiceCommand("exit")
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
            "pause" to "pause", "pause workout" to "pause", "pause the workout" to "pause",
            "resume" to "resume", "resume workout" to "resume", "resume the workout" to "resume", "play" to "resume", "continue" to "resume",
            "skip" to "skip", "skip exercise" to "skip", "next exercise" to "skip", "skip rest" to "skip",
            "end workout" to "end", "stop workout" to "end", "finish workout" to "end",
            "open workouts" to "home", "show workouts" to "home",
            "open journey" to "journey", "show journey" to "journey",
            "open scanner" to "scan", "scan room" to "scan", "open scan" to "scan", "scan floor" to "scan", "scan my room" to "scan",
            "open coach" to "coach", "ask coach" to "coach", "open voice" to "voice",
            "start workout" to "today", "start session" to "today", "start todays workout" to "today", "start today s workout" to "today",
            "go back" to "back", "back" to "back", "close voice" to "close",
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
        if (Regex("^(tap|click|press|hit|select|choose) ").containsMatchIn(text)) {
            val label=text.substringAfter(' ').removePrefix("the ").removeSuffix(" button")
            val control=parse(label)
            if(control!=null && control.action in setOf("pause","resume","skip","end","today","restart","restart_session","exit")) return control
            return VoiceCommand("button", label=label)
        }
        // "demo" means watch it at full size; "AR" alone means the usual corner card.
        val wantsDemo = Regex("\\bdemos?\\b|\\bdemonstrat").containsMatchIn(text)
        val wantsAr = Regex("\\b(ar|augmented reality)\\b").containsMatchIn(text)
        if (Regex("^(start|begin|do|show|preview|demo|demonstrate|view|display|open)\\b").containsMatchIn(text) || wantsDemo || wantsAr) {
            val normalized = text.replace("push ups", "pushups").replace("push up", "pushup")
                .replace("sit ups", "situps").replace("sit up", "situp").replace("pull ups", "pullups").replace("pull up", "pullup")
            val exercise = Exercise.entries.sortedByDescending { it.name.length }.firstOrNull {
                val name = it.name.lowercase().replace('_', ' ')
                Regex("\\b${Regex.escape(name)}s?\\b").containsMatchIn(normalized)
            } ?: similarExercise(normalized)
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
                val showing = wantsDemo || wantsAr || Regex("^(show|preview|view|display)\\b").containsMatchIn(text)
                return VoiceCommand(if(showing) "demo" else "exercise",exercise,number,expand=wantsDemo && !wantsAr)
            }
            if(text.startsWith("start ") || text.startsWith("begin ")) return VoiceCommand("level",label=text.substringAfter(' ').removeSuffix(" session").removeSuffix(" workout"))
            if(wantsDemo || wantsAr) return VoiceCommand("demo",expand=wantsDemo && !wantsAr)
        }
        // A phrase one or two letters away from a real command is almost always that command.
        similarLabel(text, actions.keys)?.let { match -> return VoiceCommand(actions.getValue(match)) }
        similarLabel(text, exits)?.let { return VoiceCommand("exit") }
        return null
    }

    private fun distance(a: String, b: String): Int {
        var prev=IntArray(b.length+1) { it }
        a.forEachIndexed { i,c ->
            val next=IntArray(b.length+1); next[0]=i+1
            b.forEachIndexed { j,d -> next[j+1]=minOf(next[j]+1,prev[j+1]+1,prev[j]+if(c==d) 0 else 1) }
            prev=next
        }
        return prev.last()
    }

    /** Recovers a mis-heard exercise name ("squads", "lunch") when no name matched exactly. */
    fun similarExercise(text: String): Exercise? {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val phrases = words.indices.flatMap { i -> (1..3).mapNotNull { n -> if (i+n<=words.size) words.subList(i,i+n).joinToString(" ") else null } }
        val matches = Exercise.entries.filter { exercise ->
            val name = exercise.name.lowercase().replace('_', ' ')
            val budget = if (name.length >= 8) 2 else 1
            phrases.any { it.length >= name.length-budget && distance(it, name) <= budget }
        }
        return matches.singleOrNull()
    }

    /** A small spelling error may select one unique existing label; never guess destructive actions. */
    fun similarLabel(raw: String, labels: Set<String>): String? {
        val query=normalize(raw)
        if(query in labels) return query
        if(query.length<4) return null
        val matches=labels.filterNot { Regex("\\b(delete|clear|forget|reset|disable)\\b").containsMatchIn(it) }
            .filter { distance(query,it)<=if(query.length>=8) 2 else 1 }
        return matches.singleOrNull()
    }
}
