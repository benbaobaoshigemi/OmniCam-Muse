package local.jiege.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Bottom bar icon for 主页: a house. */
val HomeIcon: ImageVector = ImageVector.Builder(
    name = "Home", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(12f, 3f)
        lineTo(21.5f, 11.5f)
        horizontalLineTo(19f)
        verticalLineTo(20f)
        horizontalLineTo(5f)
        verticalLineTo(11.5f)
        horizontalLineTo(2.5f)
        close()
        // Door.
        moveTo(10f, 20f)
        verticalLineTo(14f)
        horizontalLineTo(14f)
        verticalLineTo(20f)
        close()
    }
}.build()

/** Bottom bar icon for 拍摄: a camera body with a lens. */
val CameraIcon: ImageVector = ImageVector.Builder(
    name = "Camera", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(8.5f, 4f)
        horizontalLineTo(15.5f)
        lineTo(17f, 6.5f)
        horizontalLineTo(20f)
        curveTo(21.1f, 6.5f, 22f, 7.4f, 22f, 8.5f)
        verticalLineTo(18.5f)
        curveTo(22f, 19.6f, 21.1f, 20.5f, 20f, 20.5f)
        horizontalLineTo(4f)
        curveTo(2.9f, 20.5f, 2f, 19.6f, 2f, 18.5f)
        verticalLineTo(8.5f)
        curveTo(2f, 7.4f, 2.9f, 6.5f, 4f, 6.5f)
        horizontalLineTo(7f)
        close()
        // Lens (hole).
        moveTo(12f, 9f)
        curveTo(9.8f, 9f, 8f, 10.8f, 8f, 13f)
        curveTo(8f, 15.2f, 9.8f, 17f, 12f, 17f)
        curveTo(14.2f, 17f, 16f, 15.2f, 16f, 13f)
        curveTo(16f, 10.8f, 14.2f, 9f, 12f, 9f)
        close()
    }
}.build()

/** Home info row icon for the module version: a box. */
val InfoModuleIcon: ImageVector = ImageVector.Builder(
    name = "InfoModule", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(4f, 4f)
        horizontalLineTo(20f)
        verticalLineTo(20f)
        horizontalLineTo(4f)
        close()
        moveTo(7f, 7f)
        verticalLineTo(17f)
        horizontalLineTo(17f)
        verticalLineTo(7f)
        close()
    }
}.build()

/** Home info row icon for the device: a phone. */
val PhoneIcon: ImageVector = ImageVector.Builder(
    name = "Phone", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(7f, 2f)
        horizontalLineTo(17f)
        curveTo(18.1f, 2f, 19f, 2.9f, 19f, 4f)
        verticalLineTo(20f)
        curveTo(19f, 21.1f, 18.1f, 22f, 17f, 22f)
        horizontalLineTo(7f)
        curveTo(5.9f, 22f, 5f, 21.1f, 5f, 20f)
        verticalLineTo(4f)
        curveTo(5f, 2.9f, 5.9f, 2f, 7f, 2f)
        close()
        moveTo(7f, 5f)
        verticalLineTo(17f)
        horizontalLineTo(17f)
        verticalLineTo(5f)
        close()
    }
}.build()
