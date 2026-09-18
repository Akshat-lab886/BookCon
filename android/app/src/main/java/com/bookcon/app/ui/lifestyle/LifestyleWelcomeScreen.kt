package com.bookcon.app.ui.lifestyle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First-launch welcome screen (Oripio card #1).
 *
 * Full-bleed teal hero with two decorative book covers floating in the
 * background, the app tagline front-and-centre, and a black "Get Started"
 * pill that calls [onGetStarted] (typically navigates to the library or
 * dismisses the welcome).
 */
@Composable
fun LifestyleWelcomeScreen(
    onGetStarted: () -> Unit,
) {
    val teal = MaterialTheme.colorScheme.primary
    val pink = MaterialTheme.colorScheme.secondary
    val yellow = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(teal, teal.copy(alpha = 0.92f)),
                )
            )
    ) {
        // Decorative floating circles (matches Oripio's little floating dots)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = size
            drawCircle(Color.White.copy(alpha = 0.18f), radius = 10f, center = Offset(s.width * 0.15f, s.height * 0.10f))
            drawCircle(Color.White.copy(alpha = 0.14f), radius = 14f, center = Offset(s.width * 0.90f, s.height * 0.18f))
            drawCircle(Color.White.copy(alpha = 0.12f), radius = 6f, center = Offset(s.width * 0.30f, s.height * 0.30f))
            drawCircle(Color.White.copy(alpha = 0.18f), radius = 8f, center = Offset(s.width * 0.78f, s.height * 0.50f))
        }

        // Two mock book covers in the upper half (Oripio's fanned covers)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 96.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            MockCover(
                color = pink,
                modifier = Modifier
                    .size(140.dp, 200.dp)
                    .padding(top = 24.dp, end = 80.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            MockCover(
                color = yellow,
                modifier = Modifier
                    .size(140.dp, 200.dp)
                    .padding(start = 80.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
        }

        // Tagline + Get Started button anchored at the bottom half
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Your Book Library\nMake Your Own Space",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 38.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Buy a best trending book here and manage your ebooks in your space.",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onGetStarted,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    "Get Started",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MockCover(color: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(16.dp))
    ) {
        Box(
            modifier = Modifier
                .padding(12.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.4f))
        )
    }
}
