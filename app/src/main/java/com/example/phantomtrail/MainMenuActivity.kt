package com.example.phantomtrail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.phantomtrail.ui.theme.PhantomTrailTheme

class MainMenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PhantomTrailTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("PhantomTrail", color = Color.White, fontSize = 36.sp)

                    Spacer(modifier = Modifier.height(64.dp))

                    Button(
                        onClick = { startActivity(Intent(this@MainMenuActivity, MainActivity::class.java)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A7C59)),
                        modifier = Modifier.width(200.dp)
                    ) {
                        Text("Random Trail", fontSize = 18.sp,
                            color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { startActivity(Intent(this@MainMenuActivity, FollowGpxActivity::class.java)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A7C59)),
                        modifier = Modifier.width(200.dp)
                    ) {
                        Text("Follow GPX", fontSize = 18.sp,
                            color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { startActivity(Intent(this@MainMenuActivity, FollowRandomRoad::class.java)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A7C59)),
                        modifier = Modifier.width(200.dp)
                    ) {
                        Text("Random Road", fontSize = 18.sp,
                            color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.strava.com/upload/select")))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFC4C02)),
                        modifier = Modifier.width(200.dp)
                    ) {
                        Text("Upload to Strava", fontSize = 18.sp, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(64.dp))
                    Text("V1.6.6", color = Color.DarkGray, fontSize = 12.sp)
                }
            }
        }
    }
}