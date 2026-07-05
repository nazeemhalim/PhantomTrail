package com.example.phantomtrail.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.phantomtrail.R

val TurretRoadFontFamily = FontFamily(
    Font(R.font.turret_road_regular, FontWeight.Normal),
    Font(R.font.turret_road_bold, FontWeight.Bold)
)

val HandjetFontFamily = FontFamily(
    Font(R.font.handjet_regular, FontWeight.Bold)
)

val Typography = Typography(
    displayLarge  = TextStyle(fontFamily = TurretRoadFontFamily),
    displayMedium = TextStyle(fontFamily = TurretRoadFontFamily),
    displaySmall  = TextStyle(fontFamily = TurretRoadFontFamily),
    headlineLarge  = TextStyle(fontFamily = TurretRoadFontFamily),
    headlineMedium = TextStyle(fontFamily = TurretRoadFontFamily),
    headlineSmall  = TextStyle(fontFamily = TurretRoadFontFamily),
    titleLarge  = TextStyle(fontFamily = TurretRoadFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = TurretRoadFontFamily),
    titleSmall  = TextStyle(fontFamily = TurretRoadFontFamily),
    bodyLarge  = TextStyle(fontFamily = TurretRoadFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = TurretRoadFontFamily),
    bodySmall  = TextStyle(fontFamily = TurretRoadFontFamily),
    labelLarge  = TextStyle(fontFamily = TurretRoadFontFamily),
    labelMedium = TextStyle(fontFamily = TurretRoadFontFamily),
    labelSmall  = TextStyle(fontFamily = TurretRoadFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)
