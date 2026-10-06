package io.github.crockalet.haunt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun HauntApp() {
    Box(Modifier.fillMaxSize().background(Color(0xFFF1F1EF)), contentAlignment = Alignment.Center) {
        BasicText("Haunt")
    }
}
