package com.bookcon.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bookcon.app.ui.theme.BrandColors

/**
 * Rounded search field with muted background — replaces the heavy outlined
 * text field on the Library home screen.
 *
 * The reference shows a solid near-black pill with a magnifier and bold
 * placeholder text, so the default colours are the dark ones; pass overrides
 * for the mint hero.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "Search",
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = BrandColors.TextSecondary,
        )
    },
    containerColor: Color = Color(0xFF0F1014),
    textColor: Color = BrandColors.TextSecondary,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
        },
        leadingIcon = leadingIcon,
        singleLine = true,
        shape = RoundedCornerShape(50),
        textStyle = MaterialTheme.typography.titleMedium.copy(color = textColor),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = containerColor,
            focusedContainerColor = containerColor,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = BrandColors.Green,
            cursorColor = BrandColors.Green,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    )
}
