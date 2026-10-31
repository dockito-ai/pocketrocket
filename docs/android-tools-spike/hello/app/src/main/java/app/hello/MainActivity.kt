package app.hello

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** The spike's app: one label and a button that counts taps, so `input -d` and `uiautomator dump` have something to prove. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("HelloSpike", "onCreate display=${display?.displayId}")
        setContent {
            MaterialTheme {
                var taps by remember { mutableIntStateOf(0) }
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Hello from PocketRocket")
                    Button(onClick = {
                        taps++
                        Log.i("HelloSpike", "tap $taps")
                    }) { Text("Tapped $taps") }
                }
            }
        }
    }
}
