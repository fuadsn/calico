package com.hackathon.calico

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.activity.ComponentActivity

/** Test launcher: pick an exercise, start the live workout with it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val names = Exercise.values().map { it.name.replace('_', ' ') }
        setContentView(ListView(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, names)
            setOnItemClickListener { _, _, i, _ ->
                startActivity(
                    Intent(context, WorkoutActivity::class.java)
                        .putExtra("exercise", Exercise.values()[i].name)
                )
            }
        })
    }
}
