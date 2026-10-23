package app.respira.feature.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.respira.R
import app.respira.ui.components.GlassCard
import app.respira.ui.components.IconBadge
import app.respira.ui.components.LanguageButton
import app.respira.ui.components.PillButton
import app.respira.ui.components.SectionLabel
import app.respira.ui.theme.RespiraColors
import app.respira.ui.theme.RespiraColors.Aqua
import app.respira.ui.theme.RespiraColors.Mist
import app.respira.ui.theme.RespiraColors.SecondaryText
import app.respira.ui.theme.RespiraColors.TertiaryText
import java.time.LocalTime

@Composable
fun HomeScreen(
    onBreathe: () -> Unit,
    onMeditate: () -> Unit,
    onAmbience: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val greetingRes = remember {
        when (LocalTime.now().hour) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..17 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        }
    }
    val greeting = stringResource(greetingRes)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_wordmark),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 6.sp),
                color = Aqua.copy(alpha = 0.9f),
            )
            Spacer(Modifier.weight(1f))
            LanguageButton()
        }

        Spacer(Modifier.height(36.dp))

        Text(
            text = greeting.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.4.sp),
            color = TertiaryText,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.home_headline),
            style = MaterialTheme.typography.displayMedium,
            color = Mist,
        )

        Spacer(Modifier.height(26.dp))

        BreatheHero(onClick = onBreathe)

        Spacer(Modifier.height(22.dp))

        SectionLabel(stringResource(R.string.home_explore_label))
        Spacer(Modifier.height(12.dp))

        ExploreCard(
            title = stringResource(R.string.home_card_meditate_title),
            subtitle = stringResource(R.string.home_card_meditate_subtitle),
            icon = R.drawable.ic_moon,
            accent = RespiraColors.Violet,
            onClick = onMeditate,
        )
        Spacer(Modifier.height(12.dp))
        ExploreCard(
            title = stringResource(R.string.home_card_sounds_title),
            subtitle = stringResource(R.string.home_card_sounds_subtitle),
            icon = R.drawable.ic_cloud_rain,
            accent = Aqua,
            onClick = onAmbience,
        )

        Spacer(Modifier.height(26.dp))
        Text(
            text = stringResource(R.string.home_footer),
            style = MaterialTheme.typography.bodySmall,
            color = TertiaryText,
        )
    }
}

@Composable
private fun BreatheHero(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF14303F),
                        Color(0xFF161A38),
                        Color(0xFF1B1830),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Aqua.copy(alpha = 0.26f), Color.White.copy(alpha = 0.04f)),
                ),
                RoundedCornerShape(32.dp),
            )
            .clickable(onClick = onClick)
            .padding(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_hero_title),
                style = MaterialTheme.typography.displaySmall,
                color = Mist,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.home_hero_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = SecondaryText,
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(text = stringResource(R.string.home_hero_action), onClick = onClick)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_hero_pattern),
                    style = MaterialTheme.typography.labelSmall,
                    color = TertiaryText,
                )
            }
        }
        Box(Modifier.size(118.dp)) {
            BreathOrb(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun BreathOrb(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hero-orb")
    val swell by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hero-orb-swell",
    )

    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val orb = radius * swell
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Aqua.copy(alpha = 0.34f), Color.Transparent),
                center = center,
                radius = orb,
            ),
            radius = orb,
            center = center,
        )
        drawCircle(
            color = Aqua.copy(alpha = 0.22f),
            radius = radius * 0.94f,
            center = center,
            style = Stroke(width = 1.2f),
        )
        drawCircle(
            color = Aqua.copy(alpha = 0.5f),
            radius = orb * 0.64f,
            center = center,
            style = Stroke(width = 1.2f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.8f),
                    Aqua.copy(alpha = 0.55f),
                    Color.Transparent,
                ),
                center = center,
                radius = orb * 0.42f,
            ),
            radius = orb * 0.42f,
            center = center,
        )
    }
}

@Composable
private fun ExploreCard(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    accent: Color,
    onClick: () -> Unit,
) {
    GlassCard(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = icon, tint = accent, size = 50.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Mist,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = TertiaryText,
            )
        }
    }
}
