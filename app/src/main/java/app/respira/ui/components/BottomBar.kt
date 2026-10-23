package app.respira.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.respira.R
import app.respira.ui.Routes
import app.respira.ui.theme.RespiraColors

data class BottomTab(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val icon: Int,
)

val RespiraTabs = listOf(
    BottomTab(Routes.Home, R.string.home_tab_home, R.drawable.ic_house),
    BottomTab(Routes.Breathe, R.string.home_tab_breathe, R.drawable.ic_wind),
    BottomTab(Routes.Meditate, R.string.home_tab_meditate, R.drawable.ic_moon),
    BottomTab(Routes.Ambience, R.string.home_tab_sounds, R.drawable.ic_cloud_rain),
)

@Composable
fun RespiraBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp)
            .padding(bottom = 10.dp),
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            RespiraTabs.forEach { tab ->
                val label = stringResource(tab.labelRes)
                val selected = currentRoute == tab.route
                val background by animateColorAsState(
                    targetValue = if (selected) RespiraColors.Aqua.copy(alpha = 0.13f) else Color.Transparent,
                    label = "tab-background",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(background)
                        .clickable { onSelect(tab.route) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(tab.icon),
                        contentDescription = label,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) RespiraColors.Aqua else RespiraColors.TertiaryText,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.4.sp),
                        color = if (selected) RespiraColors.Aqua else RespiraColors.TertiaryText,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
