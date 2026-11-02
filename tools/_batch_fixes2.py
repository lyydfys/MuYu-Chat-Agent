import io

def patch(path, pairs):
    t = io.open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert old in t, f"anchor missing in {path}: {old[:70]!r}"
        t = t.replace(old, new, 1)
    io.open(path, "w", encoding="utf-8", newline="\n").write(t)
    print("patched", path)

chat = r"feature/chat/src/main/java/com/muyuchat/feature/chat"

# 4) cache evidence text: drop the extra alpha so 10sp stays legible
patch(f"{chat}/ChatStatusBarHeader.kt", [
    ("                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)",
     "                                color = MaterialTheme.colorScheme.onSurfaceVariant"),
    ("""            shape = CircleShape,
            shadowElevation = 5.dp
        ) {
            McaIconButton(
                contentDescription = "打开历史",""",
     """            shape = CircleShape,
            // 4dp keeps the capsule lifted without the grey band reading as a second surface.
            shadowElevation = 4.dp
        ) {
            McaIconButton(
                contentDescription = "打开历史","""),
    ("""            shape = CircleShape,
            shadowElevation = 5.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),""",
     """            shape = CircleShape,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),"""),
])

# 5) large image preview: shared three-state loader with in-place retry
patch(f"{chat}/ChatGeneratedImageViews.kt", [
    ("""        // The full-screen preview still uses a bounded bitmap; the original file remains
        // available for save/share and is never decoded into the Compose heap.
        val previewBitmap by rememberChatImageBitmap(
            context = context,
            uriString = previewImage.uriString,
            maxDimensionPx = 2048
        )
        val previewBitmapValue = previewBitmap""",
     """        // The full-screen preview shares the honest loader: no premature failure text while the
        // decode is still running, and a retry that visibly returns to Loading.
        var previewRetry by remember(previewImage.id) { mutableStateOf(0) }
        val previewState = rememberChatImageLoadState(
            context = context,
            uriString = previewImage.uriString,
            maxDimensionPx = 2048,
            revisionKey = previewRetry
        )"""),
    ("""                    if (previewBitmapValue != null) {
                        Image(
                            bitmap = previewBitmapValue.asImageBitmap(),
                            contentDescription = previewImage.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 680.dp)
                        )
                    } else {
                        Text("图片文件暂时无法读取。")
                    }""",
     """                    when (previewState) {
                        is ChatImageLoadState.Ready -> Image(
                            bitmap = previewState.bitmap.asImageBitmap(),
                            contentDescription = previewImage.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 680.dp)
                        )
                        ChatImageLoadState.Loading -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                        }
                        ChatImageLoadState.Failed -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("图片文件暂时无法读取。")
                            TextButton(onClick = { previewRetry += 1 }) { Text("重试") }
                        }
                    }"""),
])

# 6) gallery tile: spinner while loading, retry on failure
patch(f"{chat}/ImageAssetScreens.kt", [
    ("""    val bitmap by rememberChatImageBitmap(context, image.uriString, maxDimensionPx = 384)""",
     """    var retryKey by remember(image.id) { mutableStateOf(0) }
    val loadState = rememberChatImageLoadState(
        context = context,
        uriString = image.uriString,
        maxDimensionPx = 384,
        revisionKey = retryKey
    )"""),
    ("""        bitmap?.let { loadedBitmap ->
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } ?: run {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
            )
        }""",
     """        when (loadState) {
            is ChatImageLoadState.Ready -> Image(
                bitmap = loadState.bitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            ChatImageLoadState.Loading -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            ChatImageLoadState.Failed -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6),
                    modifier = Modifier.size(26.dp)
                )
                TextButton(onClick = { retryKey += 1 }) { Text("重试") }
            }
        }"""),
])
print("done")
