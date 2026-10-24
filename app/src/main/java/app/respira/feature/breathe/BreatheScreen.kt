package app.respira.feature.breathe

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.respira.R
import app.respira.ui.components.GhostPill
import app.respira.ui.components.PillButton
import app.respira.ui.components.SoftIconButton
import app.respira.ui.components.formatDuration
import app.respira.ui.theme.RespiraColors
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil

@Composable
fun BreatheScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val feedback = remember(context) { BreathFeedback(context) }

    var patternId by rememberSaveable { mutableStateOf(BreathPatterns.first().id) }
    var sessionKey by rememberSaveable { mutableStateOf(3) }
    var hapticsOn by rememberSaveable { mutableStateOf(true) }
    var soundOn by rememberSaveable { mutableStateOf(true) }
    var showDurations by remember { mutableStateOf(false) }

    val sessionMinutes = if (sessionKey < 0) null else sessionKey
    val engine = remember {
        BreathEngine(
            pattern = BreathPatterns.firstOrNull { it.id == patternId } ?: BreathPatterns.first(),
            minutes = sessionMinutes,
        )
    }

    LaunchedEffect(engine.runId) {
        if (engine.status != BreathStatus.Running) return@LaunchedEffect
        val runId = engine.runId
        val base = engine.elapsedSeconds
        val startNanos = withFrameNanos { it }
        while (engine.runId == runId && engine.status == BreathStatus.Running) {
            val now = withFrameNanos { it }
            if (engine.runId != runId || engine.status != BreathStatus.Running) break
            val elapsed = base + (now - startNanos) / 1_000_000_000.0
            when (val tick = engine.update(elapsed)) {
                BreathTick.Complete -> {
                    feedback.sessionComplete(hapticsOn, soundOn)
                    break
                }
                is BreathTick.PhaseChanged -> feedback.phaseChanged(hapticsOn, soundOn)
                BreathTick.None -> Unit
            }
        }
    }

    val view = LocalView.current
    val activityWindow = remember(view) { view.context.findActivity()?.window }
    val isRunning = engine.status == BreathStatus.Running

    DisposableEffect(activityWindow, isRunning) {
        if (isRunning) {
            activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }

    val exit: () -> Unit = {
        engine.stop()
        feedback.stopSounds()
        onBack()
    }
    BackHandler(onBack = exit)

    val remainingTime by remember(engine) {
        derivedStateOf {
            engine.sessionMinutes?.let { limit ->
                val remaining = (limit * 60.0 - engine.elapsedSeconds).coerceAtLeast(0.0)
                formatDuration(ceil(remaining).toLong() * 1000L)
            }
        }
    }

    val phaseSeconds by remember(engine) {
        derivedStateOf {
            ceil(engine.phaseRemainingSeconds.toDouble()).toInt().coerceAtLeast(1)
        }
    }

    val begin: () -> Unit = {
        showDurations = false
        engine.begin()
        feedback.startSession(hapticsOn, soundOn)
    }

    val patternName = stringResource(engine.pattern.nameRes)
    val openLabel = stringResource(R.string.breathe_length_open)
    val remainingLabel = remainingTime ?: openLabel

    val isActive = engine.status == BreathStatus.Running || engine.status == BreathStatus.Paused
    val headerPillText = if (isActive) remainingLabel else sessionLabel(engine.sessionMinutes)
    val subtitle = if (engine.status == BreathStatus.Complete) {
        stringResource(R.string.breathe_subtitle_complete)
    } else {
        stringResource(
            R.string.breathe_meta,
            patternName,
            sessionLabel(engine.sessionMinutes),
        )
    }

    val controlLabel = stringResource(
        when (engine.status) {
            BreathStatus.Running -> R.string.breathe_pause
            BreathStatus.Paused -> R.string.breathe_resume
            else -> R.string.breathe_begin
        },
    )
    val controlIcon = if (engine.status == BreathStatus.Running) {
        R.drawable.ic_pause
    } else {
        R.drawable.ic_play
    }
    val controlAction: () -> Unit = when (engine.status) {
        BreathStatus.Running -> engine::pause
        BreathStatus.Paused -> engine::resume
        else -> begin
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 6.dp, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.breathe_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = RespiraColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = RespiraColors.SecondaryText,
                )
            }
            GhostPill(
                text = headerPillText,
                onClick = { showDurations = !showDurations },
                selected = showDurations,
                modifier = Modifier.heightIn(min = 44.dp),
                leading = {
                    Icon(
                        painter = painterResource(R.drawable.ic_timer),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (showDurations) {
                            RespiraColors.Aqua
                        } else {
                            RespiraColors.SecondaryText
                        },
                    )
                },
            )
        }

        AnimatedVisibility(visible = showDurations) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionLengths.forEach { option ->
                    val chosen = engine.sessionMinutes == option.minutes
                    GhostPill(
                        text = sessionLabel(option.minutes),
                        selected = chosen,
                        onClick = {
                            sessionKey = option.minutes ?: -1
                            engine.selectSession(option.minutes)
                            showDurations = false
                        },
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .semantics { selected = chosen },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            BreathOrb(
                engine = engine,
                modifier = Modifier.size(300.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 124.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (engine.status) {
                BreathStatus.Idle -> IdleSummary(engine.pattern, engine.sessionMinutes)
                BreathStatus.Complete -> CompletionSummary(
                    elapsedSeconds = engine.elapsedSeconds,
                    onAgain = begin,
                    onDone = exit,
                )
                else -> ActiveSummary(
                    engine = engine,
                    phaseSeconds = phaseSeconds,
                    remainingLabel = remainingLabel,
                    paused = engine.status == BreathStatus.Paused,
                )
            }
        }

        if (engine.status != BreathStatus.Complete) {
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BreathPatterns.forEach { option ->
                    val chosen = engine.pattern.id == option.id
                    GhostPill(
                        text = stringResource(option.nameRes),
                        selected = chosen,
                        onClick = {
                            patternId = option.id
                            engine.selectPattern(option)
                        },
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .semantics { selected = chosen },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                BreathControl(
                    icon = controlIcon,
                    label = controlLabel,
                    onClick = controlAction,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TogglePill(
                    icon = R.drawable.ic_activity,
                    label = stringResource(R.string.breathe_haptics),
                    enabled = hapticsOn,
                    onClick = { hapticsOn = !hapticsOn },
                )
                Spacer(Modifier.width(10.dp))
                TogglePill(
                    icon = R.drawable.ic_volume_2,
                    label = stringResource(R.string.breathe_sound),
                    enabled = soundOn,
                    onClick = { soundOn = !soundOn },
                )
            }
        }
    }
}

@Composable
private fun IdleSummary(pattern: BreathPattern, minutes: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(pattern.nameRes),
            style = MaterialTheme.typography.displaySmall,
            color = RespiraColors.Mist,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = patternDetail(pattern),
            style = MaterialTheme.typography.bodyMedium,
            color = RespiraColors.SecondaryText,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(
                R.string.breathe_meta,
                stringResource(pattern.hintRes),
                sessionPhrase(minutes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = RespiraColors.TertiaryText,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ActiveSummary(
    engine: BreathEngine,
    phaseSeconds: Int,
    remainingLabel: String,
    paused: Boolean,
) {
    val accent = engine.phase.accentColor()
    val remainingPhrase = if (engine.sessionMinutes == null) {
        stringResource(R.string.breathe_time_open_session)
    } else {
        stringResource(R.string.breathe_time_left, remainingLabel)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(engine.phase.labelRes()),
            style = MaterialTheme.typography.displaySmall,
            color = RespiraColors.Mist,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$phaseSeconds",
                style = MaterialTheme.typography.headlineLarge,
                color = accent,
            )
            Text(
                text = stringResource(R.string.breathe_seconds_suffix),
                style = MaterialTheme.typography.titleMedium,
                color = RespiraColors.TertiaryText,
                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (paused) {
                stringResource(R.string.breathe_paused, remainingPhrase)
            } else {
                stringResource(
                    R.string.breathe_meta,
                    stringResource(engine.pattern.nameRes),
                    remainingPhrase,
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (paused) RespiraColors.Amber else RespiraColors.TertiaryText,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CompletionSummary(
    elapsedSeconds: Double,
    onAgain: () -> Unit,
    onDone: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.breathe_well_done),
            style = MaterialTheme.typography.displaySmall,
            color = RespiraColors.Mist,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.breathe_breathed_for,
                formatDuration((elapsedSeconds * 1000.0).toLong()),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = RespiraColors.SecondaryText,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                text = stringResource(R.string.breathe_again),
                onClick = onAgain,
                modifier = Modifier.heightIn(min = 44.dp),
            )
            GhostPill(
                text = stringResource(R.string.breathe_done),
                onClick = onDone,
                modifier = Modifier.heightIn(min = 44.dp),
            )
        }
    }
}

@Composable
private fun BreathControl(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                RespiraColors.Aqua.copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                        ),
                        CircleShape,
                    ),
            )
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(RespiraColors.Aqua, RespiraColors.Indigo),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = label,
                        onClick = onClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = label,
                    modifier = Modifier.size(30.dp),
                    tint = RespiraColors.Ink,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = RespiraColors.SecondaryText,
        )
    }
}

@Composable
private fun TogglePill(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val state = stringResource(
        if (enabled) R.string.breathe_toggle_on else R.string.breathe_toggle_off,
    )
    GhostPill(
        text = label,
        onClick = onClick,
        selected = enabled,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .semantics { stateDescription = state },
        leading = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (enabled) RespiraColors.Aqua else RespiraColors.SecondaryText,
            )
        },
    )
}

@Composable
private fun patternDetail(pattern: BreathPattern): String {
    val locale = LocalConfiguration.current.locales[0]
    val item = stringResource(R.string.breathe_phase_detail_item)
    val separator = stringResource(R.string.breathe_detail_separator)
    val inhale = stringResource(R.string.breathe_phase_inhale_short)
    val holdIn = stringResource(R.string.breathe_phase_hold_in_short)
    val exhale = stringResource(R.string.breathe_phase_exhale_short)
    val holdOut = stringResource(R.string.breathe_phase_hold_out_short)
    return pattern.slices.joinToString(separator) { slice ->
        val phaseLabel = when (slice.phase) {
            BreathPhase.Inhale -> inhale
            BreathPhase.HoldIn -> holdIn
            BreathPhase.Exhale -> exhale
            BreathPhase.HoldOut -> holdOut
        }
        String.format(locale, item, formatSeconds(slice.duration, locale), phaseLabel)
    }
}

private fun formatSeconds(value: Float, locale: Locale): String {
    if (value % 1f == 0f) return value.toInt().toString()
    val format = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return format.format(value)
}

@Composable
private fun sessionLabel(minutes: Int?): String =
    if (minutes == null) {
        stringResource(R.string.breathe_length_open)
    } else {
        stringResource(R.string.breathe_length_minutes, minutes)
    }

@Composable
private fun sessionPhrase(minutes: Int?): String =
    if (minutes == null) {
        stringResource(R.string.breathe_session_open_phrase)
    } else {
        stringResource(R.string.breathe_session_minutes_phrase, minutes)
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
