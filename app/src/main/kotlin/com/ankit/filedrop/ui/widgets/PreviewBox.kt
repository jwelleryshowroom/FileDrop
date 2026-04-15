package com.ankit.filedrop.ui.widgets

import android.net.Uri
import android.content.Context
import android.util.Base64
import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ankit.filedrop.FileHelper

@Composable
fun PreviewBox(mimeType: String, uri: Uri) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp) // Increased height for hero feel
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(24.dp)
            )
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.06f)) // Glass background (Refinement 3)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp)), // Glass border
        contentAlignment = Alignment.Center
    ) {
        when {
            mimeType.startsWith("image/") -> {
                ImagePreview(uri)
            }
            mimeType.startsWith("video/") -> {
                VideoPreview(uri)
            }
            mimeType == "application/pdf" -> {
                FallbackIcon(Icons.Default.PictureAsPdf, MaterialTheme.colorScheme.error)
            }
            else -> {
                FallbackIcon(Icons.Default.Description, MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ImagePreview(uri: Uri) {
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        // Cinematic Gradient Overlay (Top -> Down)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )
    }
}

@Composable
private fun VideoPreview(uri: Uri) {
    val context = LocalContext.current
    
    // Fix Phase 4: Performance Optimization using produceState
    val thumbBase64 by produceState<String?>(initialValue = null, uri) {
        value = FileHelper.generateThumbnail(context, uri)
    }
    
    // Fix Phase 1: Stable Bitmap Rendering
    val bitmap = remember(thumbBase64) {
        thumbBase64?.let {
            try {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Fix Phase 5: Missing Fallback (Always show something)
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
        
        // Cinematic Gradient Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )
        // Precision Play Icon
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = Color.White
        )
    }
}

@Composable
fun MultiFilePreviewHero(uris: List<Uri>, context: Context) {
    val firstUri = uris.first()
    val mimeType = context.contentResolver.getType(firstUri) ?: "*/*"

    // Interaction Physics (Phase 2): Scale Feedback
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "heroScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .scale(scale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Step 1: HERO (The primary focus)
        Box {
            PreviewBox(mimeType, firstUri)
        }

        // Step 2: MINI STACK OVERLAY (Corner-locked context)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
        ) {
            MultiPreviewStackMini(uris)
        }
    }
}

@Composable
fun MultiPreviewStackMini(uris: List<Uri>) {
    Box(
        modifier = Modifier.size(90.dp),
        contentAlignment = Alignment.Center
    ) {
        // Layered Glass Cards (Smaller scale)
        uris.take(3).reversed().forEachIndexed { reverseIndex, _ ->
            val index = (uris.take(3).size - 1) - reverseIndex
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .offset(x = (index * 6).dp, y = (index * 6).dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            )
        }

        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.Center).offset(x = 10.dp, y = 10.dp)
        ) {
            Text(
                text = "+${uris.size}",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun MultiPreviewStack(uris: List<Uri>) {
    // Keep internal for legacy if needed, but we now use Mini
    val context = LocalContext.current
    
    // Fix Phase 2: Lock Stack to Fixed Container Size
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        // Deck-of-cards stack (up to 3 items)
        uris.take(3).reversed().forEachIndexed { reverseIndex, uri ->
            val index = (uris.take(3).size - 1) - reverseIndex
            val type = remember(uri) { context.contentResolver.getType(uri) ?: "" }
            
            ElevatedCard(
                modifier = Modifier
                    .size(150.dp)
                    // Fix Phase 3: Centered Offset System
                    .offset(x = ((index - 1) * 12).dp, y = ((index - 1) * 12).dp)
                    // Fix Phase 8: Scale Hierarchy
                    .scale(if (index == 1) 1f else 0.92f),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = (index * 4).dp)
            ) {
                // Fix Phase 7: Video Thumbnail for Hero Only (Index 1)
                if (type.startsWith("video/")) {
                    if (index == 1) {
                        VideoStackItem(uri)
                    } else {
                        // Lightweight placeholder for background cards
                        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(alpha = 0.4f))
                        }
                    }
                } else {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }

        // Fix Phase 4: Bottom-Locked Count Badge
        Surface(
            color = Color.Black.copy(alpha = 0.8f),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 4.dp, y = 4.dp)
                .shadow(4.dp, CircleShape)
        ) {
            Text(
                text = "+${uris.size}",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun VideoStackItem(uri: Uri) {
    val context = LocalContext.current
    val thumbBase64 by produceState<String?>(initialValue = null, uri) {
        value = FileHelper.generateThumbnail(context, uri)
    }
    val bitmap = remember(thumbBase64) {
        thumbBase64?.let {
            val bytes = Base64.decode(it, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun MixedPreview(count: Int) {
    // Keep internal for legacy if needed, but we now use Hero
    // Fix Phase 3: Premium Mixed Preview (Layered Glass Cards)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .offset(x = (index * 10).dp, y = (index * 10).dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.White.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "$count Files",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun FallbackIcon(icon: ImageVector, color: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        tint = color.copy(alpha = 0.7f)
    )
}

@Composable
fun FileGridExplorer(uris: List<Uri>, context: Context) {
    val firstUri = uris.first()
    val heroMimeType = remember(firstUri) { context.contentResolver.getType(firstUri) ?: "*/*" }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Affordance Hint (UX Fix 1)
            Text(
                text = "Tap to collapse",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                // HERO ITEM (UX Fix 2: Full span context)
                item(span = { GridItemSpan(3) }) {
                    Box(modifier = Modifier.height(140.dp)) {
                        PreviewBox(heroMimeType, firstUri)
                    }
                }

                // SUBSEQUENT ITEMS
                items(uris.drop(1)) { uri ->
                    GridItemBox(uri, context)
                }
            }
        }
    }
}

@Composable
private fun GridItemBox(uri: Uri, context: Context) {
    val type = remember(uri) { context.contentResolver.getType(uri) ?: "" }
    
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (type.startsWith("image/")) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (type.startsWith("video/")) {
            VideoStackItem(uri)
            Icon(
                Icons.Default.PlayArrow,
                null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
        } else {
            Icon(
                imageVector = when {
                    type == "application/pdf" -> Icons.Default.PictureAsPdf
                    else -> Icons.Default.Description
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.White.copy(alpha = 0.4f)
            )
        }
    }
}
