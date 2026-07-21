package com.example.phantomtrail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.phantomtrail.ui.theme.PhantomTrailTheme

class MainMenuActivity : ComponentActivity() {

    private data class Mode(
        val title: String,
        val subtitle: String,
        val color: Color,
        val destination: Class<*>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val modes = listOf(
            Mode(
                title = "Random Trail",
                subtitle = "Generate a random walk from your location",
                color = Color(0xFF2D6A4F),
                destination = MainActivity::class.java
            ),
            Mode(
                title = "Follow GPX",
                subtitle = "Import and follow a GPX route",
                color = Color(0xFF1A3A5C),
                destination = FollowGpxActivity::class.java
            ),
            Mode(
                title = "Random Road",
                subtitle = "Walk along randomly chosen roads",
                color = Color(0xFF6B3A1F),
                destination = FollowRandomRoad::class.java
            )
        )

        setContent {
            PhantomTrailTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(72.dp))

                    Text("PhantomTrail", color = Color.White, fontSize = 36.sp)

                    Spacer(modifier = Modifier.height(48.dp))

                    modes.forEach { mode ->
                        Button(
                            onClick = {
                                startActivity(Intent(this@MainMenuActivity, mode.destination))
                                @Suppress("DEPRECATION")
                                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = mode.color),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(84.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    mode.title,
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    mode.subtitle,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    Text("V1.7.3", color = Color.DarkGray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
