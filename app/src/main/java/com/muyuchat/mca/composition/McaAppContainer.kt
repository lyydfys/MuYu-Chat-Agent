package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.mca.AssistantStore
import com.muyuchat.mca.ChatImageToolAuthorizationStore
import com.muyuchat.mca.ChatSessionStore
import com.muyuchat.mca.KnowledgeBaseStore
import com.muyuchat.mca.ModelGenerationProfileStore
import com.muyuchat.mca.ModelRuntimeProfileStore
import com.muyuchat.mca.WorldBookStore

/**
 * R2/S12：组合根骨架（方案 §9 / §14.4）。
 *
 * 职责边界：只装配“仅依赖 Application（或零依赖）”的稳定叶子对象；不承载状态机、数据库逻辑或 UI 分支。
 * ViewModel/UI/Worker/Local API 通过接口或本容器读取装配结果，而不是各自 new 出平行实例。
 *
 * 逐域扩张（本片承接 7 个 store，构造参数与顺序 1:1 保留，零行为变化）：
 * - S12：ModelRuntimeProfileStore / ModelGenerationProfileStore / ChatSessionStore /
 *   ChatImageToolAuthorizationStore / WorldBookStore / KnowledgeBaseStore / AssistantStore；
 * - 后续切片：其余 application-only store 与 client 迁入；带 port/scope 的 owner（如 AppUpdateOwner）
 *   待其 port 依赖先行解耦后再迁入。
 */
internal class McaAppContainer(application: Application) {

    val runtimeProfileStore = ModelRuntimeProfileStore(application)

    val modelGenerationProfileStore = ModelGenerationProfileStore(application)

    val chatSessionStore = ChatSessionStore(application)

    val chatImageToolAuthorizationStore = ChatImageToolAuthorizationStore(application)

    val worldBookStore = WorldBookStore(application)

    val knowledgeBaseStore = KnowledgeBaseStore(application)

    val assistantStore = AssistantStore(application)
}
