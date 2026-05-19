package com.kotarov.autoclock

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Auto Clock"
            textSize = 28f
        }

        val subtitle = TextView(this).apply {
            text = "Android Auto media clock prototype. Open Android Auto and select Auto Clock from media apps."
            textSize = 16f
        }

        root.addView(title)
        root.addView(subtitle)
        setContentView(root)
    }
}
