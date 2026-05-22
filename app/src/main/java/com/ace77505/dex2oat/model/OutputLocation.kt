package com.ace77505.dex2oat.model

import android.net.Uri

sealed class OutputLocation {
    data object AppPrivate : OutputLocation()
    data class Saf(val uri: Uri) : OutputLocation()

    companion object {
        const val LOCATION_APP = "app"
        const val LOCATION_SAF = "saf"
    }
}
