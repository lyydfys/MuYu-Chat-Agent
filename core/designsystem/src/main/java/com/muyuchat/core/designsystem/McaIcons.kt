package com.muyuchat.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.NetworkWifi
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private const val MCA_STROKE_DEFAULT = 1.68f

private inline fun mcaStrokeIcon(
    name: String,
    strokeWidth: Float = MCA_STROKE_DEFAULT,
    block: PathBuilder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )
}.build()

/**
 * Semantic entry point for product icons. Directional actions mirror with layout direction.
 * [PinUnselected] deliberately keeps the outline/filled state contrast of the existing pin action.
 * High-frequency micro-action glyphs use custom 1.68dp uniform-stroke vectors for flagship clarity.
 */
public object McaIcons {
    public val Back: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack
    public val Forward: ImageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight
    public val Previous: ImageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft
    public val Send: ImageVector = Icons.AutoMirrored.Rounded.Send
    public val Undo: ImageVector = Icons.AutoMirrored.Rounded.Undo
    public val Redo: ImageVector = Icons.AutoMirrored.Rounded.Redo
    public val FormatListBulleted: ImageVector = Icons.AutoMirrored.Rounded.FormatListBulleted
    public val HelpOutline: ImageVector = Icons.AutoMirrored.Rounded.HelpOutline
    public val OpenInNew: ImageVector = Icons.AutoMirrored.Rounded.OpenInNew
    public val PinUnselected: ImageVector = Icons.Outlined.PushPin
    public val ExpandLess: ImageVector = Icons.Rounded.ExpandLess
    public val ExpandMore: ImageVector = Icons.Rounded.ExpandMore
    public val AutoFixHigh: ImageVector = Icons.Rounded.AutoFixHigh
    public val Science: ImageVector = Icons.Rounded.Science
    public val Bolt: ImageVector = Icons.Rounded.Bolt
    public val Tune: ImageVector = mcaStrokeIcon("McaTune") {
        // Upper slider track + hollow knob
        moveTo(4f, 8f)
        horizontalLineTo(12.2f)
        moveTo(16.8f, 8f)
        horizontalLineTo(20f)
        moveTo(16.8f, 8f)
        arcTo(2.3f, 2.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12.2f, 8f)
        arcTo(2.3f, 2.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.8f, 8f)
        // Lower slider track + hollow knob
        moveTo(4f, 16f)
        horizontalLineTo(7.2f)
        moveTo(11.8f, 16f)
        horizontalLineTo(20f)
        moveTo(11.8f, 16f)
        arcTo(2.3f, 2.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7.2f, 16f)
        arcTo(2.3f, 2.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.8f, 16f)
    }
    public val Menu: ImageVector = Icons.Rounded.Menu
    public val BrokenImage: ImageVector = Icons.Rounded.BrokenImage
    public val PhotoCamera: ImageVector = Icons.Rounded.PhotoCamera
    public val UploadFile: ImageVector = Icons.Rounded.UploadFile
    public val CheckCircle: ImageVector = Icons.Rounded.CheckCircle
    public val OpenInBrowser: ImageVector = Icons.Rounded.OpenInBrowser
    public val FileDownload: ImageVector = Icons.Rounded.FileDownload
    public val InstallMobile: ImageVector = Icons.Rounded.InstallMobile
    public val Save: ImageVector = Icons.Rounded.Save

    public val AccountTree: ImageVector = Icons.Rounded.AccountTree
    public val Add: ImageVector = Icons.Rounded.Add
    public val AttachFile: ImageVector = Icons.Rounded.AttachFile
    public val AutoAwesome: ImageVector = Icons.Rounded.AutoAwesome
    public val Build: ImageVector = Icons.Rounded.Build
    public val Cached: ImageVector = Icons.Rounded.Cached
    public val ChatBubbleOutline: ImageVector = Icons.Rounded.ChatBubbleOutline
    public val Check: ImageVector = mcaStrokeIcon("McaCheck", strokeWidth = 1.74f) {
        moveTo(5.2f, 12.6f)
        lineTo(9.8f, 17.2f)
        lineTo(19.0f, 7.2f)
    }
    public val ChevronRight: ImageVector = Icons.Rounded.ChevronRight
    public val Clear: ImageVector = Icons.Rounded.Clear
    public val Close: ImageVector = Icons.Rounded.Close
    public val ContentCopy: ImageVector = mcaStrokeIcon("McaContentCopy") {
        // Front sheet
        moveTo(11.2f, 9f)
        horizontalLineTo(17.8f)
        quadTo(20f, 9f, 20f, 11.2f)
        verticalLineTo(17.8f)
        quadTo(20f, 20f, 17.8f, 20f)
        horizontalLineTo(11.2f)
        quadTo(9f, 20f, 9f, 17.8f)
        verticalLineTo(11.2f)
        quadTo(9f, 9f, 11.2f, 9f)
        close()
        // Rear sheet partial outline
        moveTo(5f, 15f)
        verticalLineTo(6.2f)
        quadTo(5f, 4f, 7.2f, 4f)
        horizontalLineTo(15f)
    }
    public val Delete: ImageVector = Icons.Rounded.Delete
    public val Description: ImageVector = Icons.Rounded.Description
    public val DonutLarge: ImageVector = Icons.Rounded.DonutLarge
    public val Download: ImageVector = Icons.Rounded.Download
    public val Edit: ImageVector = mcaStrokeIcon("McaEdit") {
        moveTo(15.8f, 4.4f)
        quadTo(17.1f, 3.1f, 18.4f, 4.4f)
        lineTo(19.6f, 5.6f)
        quadTo(20.9f, 6.9f, 19.6f, 8.2f)
        lineTo(9.2f, 18.6f)
        lineTo(4.5f, 19.5f)
        lineTo(5.4f, 14.8f)
        close()
        moveTo(13.8f, 6.4f)
        lineTo(17.6f, 10.2f)
    }
    public val Error: ImageVector = Icons.Rounded.Error
    public val ErrorOutline: ImageVector = Icons.Rounded.ErrorOutline
    public val Favorite: ImageVector = Icons.Rounded.Favorite
    public val FavoriteBorder: ImageVector = Icons.Rounded.FavoriteBorder
    public val FilterList: ImageVector = Icons.Rounded.FilterList
    public val Folder: ImageVector = Icons.Rounded.Folder
    public val History: ImageVector = Icons.Rounded.History
    public val Image: ImageVector = Icons.Rounded.Image
    public val Info: ImageVector = Icons.Rounded.Info
    public val KeyboardArrowDown: ImageVector = Icons.Rounded.KeyboardArrowDown
    public val KeyboardArrowUp: ImageVector = Icons.Rounded.KeyboardArrowUp
    public val List: ImageVector = Icons.AutoMirrored.Rounded.List
    public val Lock: ImageVector = Icons.Rounded.Lock
    public val MoreVert: ImageVector = ImageVector.Builder(
        name = "McaMoreVert",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            val r = 1.45f
            for (cy in floatArrayOf(5.5f, 12f, 18.5f)) {
                moveTo(12f + r, cy)
                arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f - r, cy)
                arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f + r, cy)
                close()
            }
        }
    }.build()
    public val NetworkWifi: ImageVector = Icons.Rounded.NetworkWifi
    public val PhotoLibrary: ImageVector = Icons.Rounded.PhotoLibrary
    public val PlayArrow: ImageVector = Icons.Rounded.PlayArrow
    public val Psychology: ImageVector = Icons.Rounded.Psychology
    public val Person: ImageVector = Icons.Rounded.Person
    public val Code: ImageVector = Icons.Rounded.Code
    public val Star: ImageVector = Icons.Rounded.Star
    public val Feedback: ImageVector = Icons.Rounded.Feedback
    public val PushPin: ImageVector = Icons.Rounded.PushPin
    public val Refresh: ImageVector = Icons.Rounded.Refresh
    public val Remove: ImageVector = Icons.Rounded.Remove
    public val Replay: ImageVector = mcaStrokeIcon("McaReplay") {
        moveTo(11.8f, 3.3f)
        lineTo(9.0f, 5.7f)
        lineTo(11.8f, 8.1f)
        moveTo(9.3f, 5.7f)
        horizontalLineTo(12f)
        arcTo(6.8f, 6.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.6f, 10.2f)
    }
    public val Schedule: ImageVector = Icons.Rounded.Schedule
    public val Search: ImageVector = mcaStrokeIcon("McaSearch") {
        moveTo(16.8f, 10.8f)
        arcTo(6.0f, 6.0f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4.8f, 10.8f)
        arcTo(6.0f, 6.0f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.8f, 10.8f)
        close()
        moveTo(15.2f, 15.2f)
        lineTo(19.5f, 19.5f)
    }
    public val Settings: ImageVector = Icons.Rounded.Settings
    public val Share: ImageVector = Icons.Rounded.Share
    public val Shield: ImageVector = Icons.Rounded.Shield
    public val Speed: ImageVector = Icons.Rounded.Speed
    public val Stop: ImageVector = Icons.Rounded.Stop
    public val Storage: ImageVector = Icons.Rounded.Storage
    public val Translate: ImageVector = Icons.Rounded.Translate
    public val Visibility: ImageVector = Icons.Rounded.Visibility
    public val Warning: ImageVector = Icons.Rounded.Warning
    public val ZoomIn: ImageVector = Icons.Rounded.ZoomIn
    public val ZoomOut: ImageVector = Icons.Rounded.ZoomOut
}
