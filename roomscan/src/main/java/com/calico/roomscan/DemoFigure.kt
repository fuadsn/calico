package com.calico.roomscan

import android.content.res.AssetManager
import android.util.Log

/** Shared model choice for room scanning and exercise previews. */
object DemoFigure {
    val modelAssets = listOf("models/quaternius-base.glb", "models/human.glb")

    fun read(assets: AssetManager, exercise: String): SkinnedFigure? =
        modelAssets.firstNotNullOfOrNull { path ->
            try {
                SkinnedFigure.read(assets, path, exercise).also {
                    Log.i("DemoFigure", "Loaded $path for $exercise")
                }
            } catch (e: Exception) {
                Log.w("DemoFigure", "Could not load $path: ${e.message}")
                null
            }
        }

    /** A GPU/driver failure must not take down the camera or the demo screen. */
    fun prepare(figure: SkinnedFigure?): SkinnedFigure? = try {
        figure?.also { it.createOnGlThread() }?.takeIf { it.isUsable }
    } catch (e: RuntimeException) {
        Log.e("DemoFigure", "GPU rig initialization failed", e)
        null
    }
}
