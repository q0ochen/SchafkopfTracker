package io.github.q0ochen.schafkopf.app.drive

import androidx.annotation.StringRes
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.data.drive.DriveError

/** Ein verständlicher Satz zu jedem Fehler von Google Drive. */
@StringRes
fun DriveError.messageRes(): Int = when (this) {
    DriveError.SIGN_IN_REQUIRED -> R.string.drive_error_sign_in
    DriveError.ACCESS_DENIED -> R.string.drive_error_access
    DriveError.STORAGE_FULL -> R.string.drive_error_storage_full
    DriveError.RATE_LIMITED -> R.string.drive_error_rate
    DriveError.NOT_FOUND -> R.string.drive_error_not_found
    DriveError.SERVER_UNAVAILABLE -> R.string.drive_error_server
    DriveError.NETWORK -> R.string.drive_error_network
    DriveError.TOO_LARGE -> R.string.backup_too_big
    DriveError.UNKNOWN -> R.string.drive_error_unknown
}
