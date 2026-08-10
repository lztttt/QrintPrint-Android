package com.qring.print.ui.customprint

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qring.print.model.CODE_TYPES
import com.qring.print.model.CodeCategory
import com.qring.print.ui.theme.QringPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateSaveDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = QringPalette.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "保存模板",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("模板名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QringPalette.surfaceSunken,
                        contentColor = QringPalette.textPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = onSaveAs,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QringPalette.surfaceSunken,
                        contentColor = QringPalette.textPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("另存为")
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeTypePickerSheet(
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = QringPalette.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "选择码制",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 二维码
            Text("二维码", fontSize = 13.sp, color = QringPalette.textSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(CODE_TYPES.filter { it.category == CodeCategory.TWO_D }) { _, codeType ->
                    val idx = CODE_TYPES.indexOf(codeType)
                    Card(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(idx) },
                        colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken)
                    ) {
                        Text(
                            text = codeType.label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            fontSize = 13.sp,
                            color = QringPalette.textPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 一维码
            Text("一维码", fontSize = 13.sp, color = QringPalette.textSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(CODE_TYPES.filter { it.category == CodeCategory.ONE_D }) { _, codeType ->
                    val idx = CODE_TYPES.indexOf(codeType)
                    Card(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(idx) },
                        colors = CardDefaults.cardColors(containerColor = QringPalette.surfaceSunken)
                    ) {
                        Text(
                            text = codeType.label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            fontSize = 13.sp,
                            color = QringPalette.textPrimary
                        )
                    }
                }
            }
        }
    }
}
