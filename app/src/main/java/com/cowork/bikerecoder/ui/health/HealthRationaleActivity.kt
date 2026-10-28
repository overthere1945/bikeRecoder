package com.cowork.bikerecoder.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.R

/**
 * Health Connect가 띄우는 권한 이유 화면
 * (`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`, `VIEW_PERMISSION_USAGE` 별칭).
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                HealthRationale(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun HealthRationale(onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text("심박은 주행 기록에만 쓰고 휴대폰 밖으로 보내지 않습니다", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
        }
    }
}
