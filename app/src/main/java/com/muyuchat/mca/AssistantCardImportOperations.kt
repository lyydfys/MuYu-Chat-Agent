package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun importAssistantCardFor(
    store: UiStateStore,
    scope: CoroutineScope,
    showStatus: (String) -> Unit,
    captureContentImportOwner: () -> ContentImportOwner,
    finishCharacterCardImport: suspend (CharacterCardParseResult, ContentImportOwner) -> Boolean,
    rawJson: String
) {
    if (store.state.value.isGenerating) {
        showStatus("请先停止当前生成，再导入角色卡")
        return
    }
    val owner = captureContentImportOwner()
    scope.launch {
        val result = withContext(Dispatchers.Default) {
            CharacterCardCodec.parseJson(rawJson)
        }
        finishCharacterCardImport(result, owner)
    }
}

internal fun importAssistantCardFileFor(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    showStatus: (String) -> Unit,
    captureContentImportOwner: () -> ContentImportOwner,
    finishCharacterCardImport: suspend (CharacterCardParseResult, ContentImportOwner) -> Boolean,
    uriString: String,
    importOwner: ContentImportOwner? = null
) {
    if (store.state.value.isGenerating) {
        showStatus("请先停止当前生成，再导入角色卡")
        return
    }
    val owner = importOwner ?: captureContentImportOwner()
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                val stream = application.contentResolver.openInputStream(uri)
                    ?: error("无法打开所选文件")
                stream.use(CharacterCardCodec::parse)
            }.getOrElse { error ->
                CharacterCardParseResult.Failure(
                    CharacterCardParseError(
                        code = CharacterCardParseErrorCode.IO_ERROR,
                        message = error.message ?: "无法读取所选文件",
                        cause = error
                    )
                )
            }
        }
        finishCharacterCardImport(result, owner)
    }
}
