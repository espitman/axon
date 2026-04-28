package com.axon.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.axon.bridge.domain.BridgeConnectionState
import com.axon.bridge.domain.BridgeRole
import com.axon.bridge.domain.HomeState
import com.axon.bridge.domain.SmsArchiveMessage
import com.axon.bridge.domain.SmsThread
import com.axon.bridge.presentation.HomeViewModel
import java.text.DateFormat
import java.util.Date

private object AxonColor {
    val Background = Color(0xFF0D1113)
    val Panel = Color(0xFF151B1E)
    val PanelRaised = Color(0xFF1A2226)
    val Border = Color(0xFF283337)
    val Text = Color(0xFFF4F7F5)
    val Muted = Color(0xFFA4B0AD)
    val Cyan = Color(0xFF31D7D5)
    val Amber = Color(0xFFF0B75B)
    val Green = Color(0xFF73E39B)
    val Red = Color(0xFFFF7A7A)
}

private val VazirmatnFontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)

private enum class AxonPage {
    Home,
    Settings,
    Inbox,
    Thread
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStartupPermissionsIfNeeded()
        setContent {
            AxonTheme {
                AxonHomeScreen()
            }
        }
    }

    private fun requestStartupPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            val postNotificationsGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!postNotificationsGranted) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        val receiveSmsGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!receiveSmsGranted) {
            permissions += Manifest.permission.RECEIVE_SMS
        }

        val readContactsGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!readContactsGranted) {
            permissions += Manifest.permission.READ_CONTACTS
        }

        val readPhoneStateGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!readPhoneStateGranted) {
            permissions += Manifest.permission.READ_PHONE_STATE
        }

        val readCallLogGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!readCallLogGranted) {
            permissions += Manifest.permission.READ_CALL_LOG
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                REQUEST_STARTUP_PERMISSIONS
            )
        }
    }

    companion object {
        private const val REQUEST_STARTUP_PERMISSIONS = 42
    }
}

@Composable
private fun AxonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = AxonColor.Background,
            surface = AxonColor.Panel,
            primary = AxonColor.Cyan,
            secondary = AxonColor.Amber,
            onBackground = AxonColor.Text,
            onSurface = AxonColor.Text,
            onPrimary = Color(0xFF041315),
            onSecondary = Color(0xFF1C1304)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AxonHomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val ipSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isIpSheetOpen by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(AxonPage.Home) }
    var selectedThreadId by remember { mutableStateOf<String?>(null) }
    var draftReceiverIp by remember { mutableStateOf("") }

    BackHandler(enabled = currentPage != AxonPage.Home) {
        currentPage = if (currentPage == AxonPage.Thread) AxonPage.Inbox else AxonPage.Home
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AxonColor.Background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF12191B),
                            AxonColor.Background,
                            Color(0xFF0A0D0F)
                        )
                    )
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        (slideInHorizontally(animationSpec = tween(260)) { width -> width } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(260)) { width -> -width / 4 } + fadeOut(tween(160)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(260)) { width -> -width / 4 } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(260)) { width -> width } + fadeOut(tween(160)))
                    }.using(SizeTransform(clip = false))
                },
                label = "Axon page transition"
            ) { page ->
                when (page) {
                    AxonPage.Settings -> {
                    SettingsScreen(
                        diagnostics = state.diagnostics,
                        onBack = { currentPage = AxonPage.Home },
                        onPing = viewModel::pingPeer,
                        onClearDiagnostics = viewModel::clearDiagnostics,
                        onOpenNotificationAccess = viewModel::openPermissionSettings,
                        onRestartNotificationListener = viewModel::restartNotificationListener,
                        onOpenNotificationSettings = viewModel::openAppNotificationSettings,
                        onRequestBattery = viewModel::requestBatteryExemption,
                        onOpenAppDetails = viewModel::openAppDetailsSettings
                    )
                    }
                    AxonPage.Inbox -> {
                        InboxScreen(
                            threads = state.smsThreads,
                            onBack = { currentPage = AxonPage.Home },
                            onThreadClick = { threadId ->
                                selectedThreadId = threadId
                                viewModel.markThreadRead(threadId)
                                currentPage = AxonPage.Thread
                            }
                        )
                    }
                    AxonPage.Thread -> {
                        val threadId = selectedThreadId.orEmpty()
                        val thread = state.smsThreads.firstOrNull { it.id == threadId }
                        ThreadScreen(
                            thread = thread,
                            messages = viewModel.messagesForThread(threadId),
                            onBack = { currentPage = AxonPage.Inbox }
                        )
                    }
                    AxonPage.Home -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        TopBar(
                            onMessagesClick = {
                                isIpSheetOpen = false
                                currentPage = AxonPage.Inbox
                            },
                            onSettingsClick = {
                                isIpSheetOpen = false
                                currentPage = AxonPage.Settings
                            }
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 18.dp, bottom = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            ConnectionPanel(state = state)
                            RoleSwitch(
                                selectedRole = state.role,
                                onRoleSelected = viewModel::selectRole
                            )
                            StatusPanel(
                                state = state,
                                onEditReceiverIp = {
                                    draftReceiverIp = state.serverIp
                                    isIpSheetOpen = true
                                }
                            )
                        }
                        ActionRow(
                            isRunning = state.isBridgeRunning,
                            onToggleBridge = viewModel::toggleBridge
                        )
                    }
                    }
                }
            }

            if (currentPage == AxonPage.Home && isIpSheetOpen) {
                ModalBottomSheet(
                    onDismissRequest = { isIpSheetOpen = false },
                    sheetState = ipSheetState,
                    containerColor = AxonColor.Panel,
                    contentColor = AxonColor.Text
                ) {
                    ReceiverIpSheet(
                        receiverIp = draftReceiverIp,
                        onReceiverIpChanged = { draftReceiverIp = it },
                        onCancel = { isIpSheetOpen = false },
                        onSave = {
                            viewModel.updateServerIp(draftReceiverIp)
                            isIpSheetOpen = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    onMessagesClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AxonMark()
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Axon",
                    color = AxonColor.Text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "Local bridge",
                    color = AxonColor.Muted,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(
                onClick = onMessagesClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AxonColor.Panel)
                    .border(1.dp, AxonColor.Border, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChatBubbleOutline,
                    contentDescription = "Messages",
                    tint = AxonColor.Cyan
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AxonColor.Panel)
                    .border(1.dp, AxonColor.Border, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = AxonColor.Muted
                )
            }
        }
    }
}

@Composable
private fun AxonMark() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AxonColor.PanelRaised)
            .border(1.dp, AxonColor.Border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(30.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = AxonColor.Cyan, radius = size.minDimension * 0.45f)
            drawCircle(color = AxonColor.PanelRaised, radius = size.minDimension * 0.31f)
            drawCircle(color = AxonColor.Amber, radius = size.minDimension * 0.17f)
            drawLine(
                color = AxonColor.Text,
                start = Offset(size.width * 0.2f, center.y),
                end = Offset(size.width * 0.8f, center.y),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun ConnectionPanel(state: HomeState) {
    val stateColor = when (state.connectionState) {
        BridgeConnectionState.Connected -> AxonColor.Green
        BridgeConnectionState.Connecting -> AxonColor.Amber
        BridgeConnectionState.Disconnected -> AxonColor.Muted
        BridgeConnectionState.Error -> AxonColor.Red
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = AxonColor.Panel),
        border = BorderStroke(1.dp, AxonColor.Border)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Bridge status",
                        color = AxonColor.Muted,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    Text(
                        text = state.connectionState.name,
                        color = AxonColor.Text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                StatusPill(text = if (state.isBridgeRunning) "ACTIVE" else "IDLE", color = stateColor)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeviceNode(
                    label = if (state.role == BridgeRole.Source) "This device" else "Sender",
                    model = if (state.role == BridgeRole.Source) state.deviceInfo.displayName else "Remote device",
                    accent = AxonColor.Amber,
                    modifier = Modifier.weight(1f)
                )
                LinkRail(modifier = Modifier.weight(0.82f))
                DeviceNode(
                    label = if (state.role == BridgeRole.Sink) "This device" else "Receiver",
                    model = if (state.role == BridgeRole.Sink) state.deviceInfo.displayName else "Remote device",
                    accent = AxonColor.Cyan,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DeviceNode(
    label: String,
    model: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AxonColor.PanelRaised)
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Smartphone,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(34.dp)
            )
        }
        Text(
            text = label,
            color = AxonColor.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = model,
            color = AxonColor.Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LinkRail(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        ) {
            val centerY = size.height / 2f
            drawLine(
                color = AxonColor.Cyan.copy(alpha = 0.42f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(color = AxonColor.Green, radius = 5.dp.toPx(), center = Offset(size.width / 2f, centerY))
            drawCircle(color = AxonColor.Green.copy(alpha = 0.2f), radius = 12.dp.toPx(), center = Offset(size.width / 2f, centerY))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = null,
                tint = AxonColor.Cyan,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "WebSocket",
                color = AxonColor.Muted,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RoleSwitch(
    selectedRole: BridgeRole,
    onRoleSelected: (BridgeRole) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AxonColor.Panel)
            .border(1.dp, AxonColor.Border, RoundedCornerShape(8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RoleTab(
            text = "Sender",
            selected = selectedRole == BridgeRole.Source,
            accent = AxonColor.Amber,
            onClick = { onRoleSelected(BridgeRole.Source) },
            modifier = Modifier.weight(1f)
        )
        RoleTab(
            text = "Receiver",
            selected = selectedRole == BridgeRole.Sink,
            accent = AxonColor.Cyan,
            onClick = { onRoleSelected(BridgeRole.Sink) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RoleTab(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (selected) accent else AxonColor.Muted
        ),
        elevation = null
    ) {
        Text(
            text = text,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusPanel(
    state: HomeState,
    onEditReceiverIp: () -> Unit
) {
    val endpointValue = when (state.role) {
        BridgeRole.Sink -> state.localIp.ifBlank { "No LAN IP" }
        BridgeRole.Source -> state.serverIp.ifBlank { "Not set" }
    }
    val rows = buildList {
        add(
            StatusRowData(
                Icons.Rounded.Wifi,
                if (state.role == BridgeRole.Sink) "Local IP" else "Receiver IP",
                endpointValue,
                if (endpointValue == "Not set" || endpointValue == "No LAN IP") AxonColor.Red else AxonColor.Cyan,
                actionLabel = if (state.role == BridgeRole.Source) "Edit" else null,
                onAction = if (state.role == BridgeRole.Source) onEditReceiverIp else null
            )
        )
        add(
            StatusRowData(
                Icons.Rounded.Smartphone,
                "This device",
                state.deviceInfo.displayName,
                AxonColor.Amber
            )
        )
        state.peerDeviceName.takeIf { it.isNotBlank() }?.let { peerName ->
            add(
                StatusRowData(
                    Icons.Rounded.Smartphone,
                    "Peer device",
                    peerName,
                    AxonColor.Cyan
                )
            )
        }
        state.lastEventTimeMillis.takeIf { it > 0L }?.let { lastEventTime ->
            add(
                StatusRowData(
                    Icons.Rounded.Notifications,
                    "Last event",
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastEventTime)),
                    AxonColor.Amber
                )
            )
        }
        add(
            StatusRowData(
                Icons.Rounded.CheckCircle,
                "Bridge service",
                if (state.isBridgeRunning) "Running" else "Stopped",
                if (state.isBridgeRunning) AxonColor.Green else AxonColor.Muted
            )
        )
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            add(
                StatusRowData(
                    Icons.Rounded.ErrorOutline,
                    "Bridge message",
                    error,
                    if (state.connectionState == BridgeConnectionState.Error) AxonColor.Red else AxonColor.Amber
                )
            )
        }
        state.permissions.forEach { permission ->
            add(
                StatusRowData(
                    if (permission.granted) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    permission.label,
                    permission.value,
                    if (permission.granted) AxonColor.Green else AxonColor.Red
                )
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "System checks",
            color = AxonColor.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        rows.forEach { row ->
            StatusRow(row)
        }
    }
}

@Composable
private fun DiagnosticsPanel(
    entries: List<String>,
    maxEntries: Int = 10,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Diagnostics",
                color = AxonColor.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Button(
                onClick = onClear,
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AxonColor.PanelRaised,
                    contentColor = AxonColor.Text
                )
            ) {
                Text(
                    text = "Clear",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(172.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AxonColor.Panel)
                .border(1.dp, AxonColor.Border, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = "No events yet",
                    color = AxonColor.Muted,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            } else {
                entries.take(maxEntries).forEach { entry ->
                    Text(
                        text = entry,
                        color = AxonColor.Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxScreen(
    threads: List<SmsThread>,
    onBack: () -> Unit,
    onThreadClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        PageHeader(
            title = "Messages",
            subtitle = "${threads.size} conversations",
            onBack = onBack
        )

        if (threads.isEmpty()) {
            EmptyInbox()
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 18.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(threads, key = { it.id }) { thread ->
                    ThreadRow(thread = thread, onClick = { onThreadClick(thread.id) })
                }
            }
        }
    }
}

@Composable
private fun ThreadScreen(
    thread: SmsThread?,
    messages: List<SmsArchiveMessage>,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        PageHeader(
            title = thread?.sender ?: "Messages",
            subtitle = "${messages.size} received",
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(top = 18.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }
    }
}

@Composable
private fun PageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AxonMark()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = AxonColor.Text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = VazirmatnFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = AxonColor.Muted,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AxonColor.PanelRaised)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = AxonColor.Text
            )
        }
    }
}

@Composable
private fun ThreadRow(thread: SmsThread, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (thread.unreadCount > 0) AxonColor.PanelRaised else AxonColor.Panel)
            .border(
                1.dp,
                if (thread.unreadCount > 0) AxonColor.Cyan.copy(alpha = 0.45f) else AxonColor.Border,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AxonColor.Cyan.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = thread.sender.take(1).ifBlank { "?" },
                color = AxonColor.Cyan,
                fontWeight = FontWeight.Bold,
                fontFamily = VazirmatnFontFamily,
                fontSize = 18.sp,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.sender,
                    color = AxonColor.Text,
                    fontSize = 16.sp,
                    fontWeight = if (thread.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                    fontFamily = VazirmatnFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatArchiveTime(thread.lastReceivedAt),
                    color = AxonColor.Muted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = thread.lastMessage,
                color = if (thread.unreadCount > 0) AxonColor.Text else AxonColor.Muted,
                fontSize = 14.sp,
                fontFamily = VazirmatnFontFamily,
                style = TextStyle(textDirection = TextDirection.ContentOrRtl),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (thread.unreadCount > 0) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(AxonColor.Cyan),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = thread.unreadCount.coerceAtMost(99).toString(),
                    color = Color(0xFF031516),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: SmsArchiveMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(8.dp))
                .background(AxonColor.Panel)
                .border(1.dp, AxonColor.Border, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message.body,
                color = AxonColor.Text,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontFamily = VazirmatnFontFamily,
                style = TextStyle(textDirection = TextDirection.ContentOrRtl),
                textAlign = TextAlign.Start
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.originDevice,
                    color = AxonColor.Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatArchiveTime(message.receivedAt),
                    color = AxonColor.Muted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun EmptyInbox() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 42.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AxonColor.Panel)
                    .border(1.dp, AxonColor.Border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChatBubbleOutline,
                    contentDescription = null,
                    tint = AxonColor.Cyan,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = "No messages yet",
                color = AxonColor.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = "Received SMS will appear here.",
                color = AxonColor.Muted,
                fontSize = 13.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    diagnostics: List<String>,
    onBack: () -> Unit,
    onPing: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRestartNotificationListener: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenAppDetails: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AxonMark()
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Settings",
                        color = AxonColor.Text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "Diagnostics and setup",
                        color = AxonColor.Muted,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AxonColor.PanelRaised)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = AxonColor.Text
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = AxonColor.Panel),
                border = BorderStroke(1.dp, AxonColor.Border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Bridge ping",
                            color = AxonColor.Text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = "Send a WebSocket ping to the peer",
                            color = AxonColor.Muted,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onPing,
                        modifier = Modifier.height(42.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AxonColor.Cyan,
                            contentColor = Color(0xFF031516)
                        )
                    ) {
                        Text(
                            text = "Ping",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
            DiagnosticsPanel(
                entries = diagnostics,
                maxEntries = 10,
                onClear = onClearDiagnostics
            )
            PermissionActions(
                onOpenNotificationAccess = onOpenNotificationAccess,
                onRestartNotificationListener = onRestartNotificationListener,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onRequestBattery = onRequestBattery,
                onOpenAppDetails = onOpenAppDetails
            )
        }
    }
}

@Composable
private fun PermissionActions(
    onOpenNotificationAccess: () -> Unit,
    onRestartNotificationListener: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenAppDetails: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Setup",
            color = AxonColor.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        CompactActionButton(
            text = "Permissions",
            onClick = onOpenNotificationAccess,
            modifier = Modifier.fillMaxWidth()
        )
        CompactActionButton(
            text = "Restart notification listener",
            onClick = onRestartNotificationListener,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompactActionButton(
                text = "Alerts",
                onClick = onOpenNotificationSettings,
                modifier = Modifier.weight(1f)
            )
            CompactActionButton(
                text = "Battery",
                onClick = onRequestBattery,
                modifier = Modifier.weight(1f)
            )
        }
        CompactActionButton(
            text = "System app settings",
            onClick = onOpenAppDetails,
            modifier = Modifier.fillMaxWidth()
        )
        XiaomiGuidance()
    }
}

@Composable
private fun CompactActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AxonColor.PanelRaised,
            contentColor = AxonColor.Text
        )
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun XiaomiGuidance() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AxonColor.Panel)
            .border(1.dp, AxonColor.Border, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = "Xiaomi setup",
            color = AxonColor.Amber,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        listOf("Auto-start enabled", "Battery set to No restrictions", "Keep Axon locked in recents").forEach { item ->
            Text(
                text = item,
                color = AxonColor.Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class StatusRowData(
    val icon: ImageVector,
    val title: String,
    val value: String,
    val accent: Color,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

@Composable
private fun StatusRow(row: StatusRowData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AxonColor.Panel)
            .border(1.dp, AxonColor.Border, RoundedCornerShape(8.dp))
            .then(if (row.onAction != null) Modifier.clickable { row.onAction.invoke() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(row.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = row.icon,
                contentDescription = null,
                tint = row.accent,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = row.title,
            color = AxonColor.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = row.value,
            color = row.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.weight(0.72f),
            overflow = TextOverflow.Ellipsis
        )
        if (row.actionLabel != null && row.onAction != null) {
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = row.onAction,
                modifier = Modifier.height(34.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AxonColor.PanelRaised,
                    contentColor = AxonColor.Text
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = row.actionLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ReceiverIpSheet(
    receiverIp: String,
    onReceiverIpChanged: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Receiver IP",
            color = AxonColor.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        OutlinedTextField(
            value = receiverIp,
            onValueChange = onReceiverIpChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Receiver server IP") },
            placeholder = { Text("192.168.1.24") },
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AxonColor.PanelRaised,
                unfocusedContainerColor = AxonColor.PanelRaised,
                focusedTextColor = AxonColor.Text,
                unfocusedTextColor = AxonColor.Text,
                focusedLabelColor = AxonColor.Cyan,
                unfocusedLabelColor = AxonColor.Muted,
                focusedIndicatorColor = AxonColor.Cyan,
                unfocusedIndicatorColor = AxonColor.Border,
                cursorColor = AxonColor.Cyan
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AxonColor.PanelRaised,
                    contentColor = AxonColor.Text
                )
            ) {
                Text("Cancel", fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onSave,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AxonColor.Cyan,
                    contentColor = Color(0xFF031516)
                )
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ActionRow(
    isRunning: Boolean,
    onToggleBridge: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onToggleBridge,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AxonColor.Cyan,
                contentColor = Color(0xFF031516)
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (isRunning) "Stop bridge" else "Start bridge",
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatArchiveTime(timeMillis: Long): String {
    if (timeMillis <= 0L) return ""
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timeMillis))
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun AxonHomeScreenPreview() {
    AxonTheme {
        ConnectionPanel(state = HomeState(connectionState = BridgeConnectionState.Connected, isBridgeRunning = true))
    }
}
