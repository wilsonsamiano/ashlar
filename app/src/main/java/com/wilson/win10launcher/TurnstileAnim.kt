package com.wilson.win10launcher

import android.content.Context
import android.graphics.Camera
import android.util.AttributeSet
import android.view.animation.Animation
import android.view.animation.Transformation

/**
 * Windows Phone 7 turnstile: the screen hinges on the left edge and swings
 * like a book page. fromDegrees / toDegrees are android:fromDegrees and
 * android:toDegrees on the anim XML.
 */
class TurnstileAnim(context: Context, attrs: AttributeSet) : Animation() {

    private val fromDeg: Float
    private val toDeg: Float
    private val camera = Camera()
    private var h = 0

    init {
        val ns = "http://schemas.android.com/apk/res/android"
        fromDeg = attrs.getAttributeFloatValue(ns, "fromDegrees", 0f)
        toDeg = attrs.getAttributeFloatValue(ns, "toDegrees", 0f)
    }

    override fun willChangeTransformationMatrix() = true

    override fun initialize(width: Int, height: Int, parentWidth: Int, parentHeight: Int) {
        super.initialize(width, height, parentWidth, parentHeight)
        h = if (height > 0) height else parentHeight
    }

    override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
        val degrees = fromDeg + (toDeg - fromDeg) * interpolatedTime
        val matrix = t.matrix
        camera.save()
        // Positive Z pushes the page away while it is edge-on.
        camera.translate(0f, 0f, kotlin.math.abs(degrees) * 4f)
        camera.rotateY(degrees)
        camera.getMatrix(matrix)
        camera.restore()
        val py = h / 2f
        matrix.preTranslate(0f, -py)
        matrix.postTranslate(0f, py)
        val edge = (kotlin.math.abs(degrees) / 80f).coerceIn(0f, 1f)
        t.alpha = 1f - 0.3f * edge
    }
}
