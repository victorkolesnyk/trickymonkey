package io.github.victorkolesnyk.hamir

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import io.github.victorkolesnyk.hamir.core.SAMPLE_RATE

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = getString(R.string.app_name) + " " + SAMPLE_RATE
        setContentView(tv)
    }
}
