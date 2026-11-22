package io.github.victorkolesnyk.hamir

import android.os.Bundle

/** Static help: placement, flight mode, threat model, limits of the defence, no network access. */
class GuideActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        enableUpNavigation()
    }
}
