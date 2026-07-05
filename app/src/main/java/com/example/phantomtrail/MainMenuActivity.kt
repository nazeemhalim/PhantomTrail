package com.example.phantomtrail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.phantomtrail.ui.theme.PhantomTrailTheme

class MainMenuActivity : ComponentActivity() {

    private data class Mode(
        val title: String,
        val subtitle: String,
        val gradient: Brush,
        val destination: Class<*>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val modes = listOf(
            Mode(
                title = "Random Trail",
                subtitle = "Generate a random walk from your location",
                gradient = Brush.verticalGradient(listOf(Color(0xFF2D6A4F), Color(0xFF1B4332))),
                destination = MainActivity::class.java
            ),
            Mode(
                title = "Follow GPX",
                subtitle = "Import and follow a GPX route",
                gradient = Brush.verticalGradient(listOf(Color(0xFF1A3A5C), Color(0xFF0D1B2A))),
                destination = FollowGpxActivity::class.java
            ),
            Mode(
                title = "Random Road",
                subtitle = "Walk along randomly chosen roads",
                gradient = Brush.verticalGradient(listOf(Color(0xFF6B3A1F), Color(0xFF3D1F0D))),
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

                    val pagerState = rememberPagerState(pageCount = { modes.size })

                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = 40.dp),
                        pageSpacing = 16.dp,
                        modifier = Modifier.height(400.dp)
                    ) { page ->
                        val mode = modes[page]
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(24.dp))
                                .background(mode.gradient)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { startActivity(Intent(this@MainMenuActivity, mode.destination)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(28.dp)
                            ) {
                                Text(
                                    mode.title,
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    mode.subtitle,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(40.dp))
                                Text(
                                    "tap to open",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(modes.size) { i ->
                            Box(
                                modifier = Modifier
                                    .size(if (pagerState.currentPage == i) 10.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (pagerState.currentPage == i) Color.White else Color.DarkGray
                                    )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    Text("V1.7", color = Color.DarkGray, fontSize = 12.sp)
                }
            }
        }
    }
}
