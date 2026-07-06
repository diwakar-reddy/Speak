package com.apps.dsimpletools.speak.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.apps.dsimpletools.speak.R
import kotlin.math.roundToInt

enum class BubbleState { IDLE, LISTENING, PROCESSING }

/** Purely visual: a colored circle with a mic glyph. All interaction logic lives in [BubbleController]. */
class BubbleView(context: Context) : FrameLayout(context) {

    private val circleBackground = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val icon = ImageView(context)
    private var pulseAnimator: ValueAnimator? = null
    private val fixedSizePx = (SIZE_DP * resources.displayMetrics.density).roundToInt()

    init {
        background = circleBackground
        val iconPadding = (10 * resources.displayMetrics.density).toInt()
        icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        icon.setImageResource(R.drawable.ic_mic_glyph)
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setState(BubbleState.IDLE)
    }

    // wrap_content + a MATCH_PARENT child is an ambiguous measure spec (it was
    // collapsing to the icon's intrinsic size, ~44dp, instead of the intended
    // 48dp). Force the exact size so it always matches BubbleController's
    // bubbleSizePx used for placement/clamping math.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = View.MeasureSpec.makeMeasureSpec(fixedSizePx, View.MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }

    fun setState(state: BubbleState) {
        val color = when (state) {
            BubbleState.IDLE -> ContextCompat.getColor(context, R.color.bubble_idle)
            BubbleState.LISTENING -> ContextCompat.getColor(context, R.color.bubble_listening)
            BubbleState.PROCESSING -> ContextCompat.getColor(context, R.color.bubble_processing)
        }
        circleBackground.setColor(color)
        if (state == BubbleState.LISTENING) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.18f).apply {
            duration = 450
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                scaleX = v
                scaleY = v
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        scaleX = 1f
        scaleY = 1f
    }

    companion object {
        const val SIZE_DP = 48
    }
}
