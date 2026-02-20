package com.djfriend.app.model

import android.net.Uri

data class SuggestionResult(
    val artist: String,
    val track: String,
    val localUri: Uri?,
    val isLocal: Boolean
)
