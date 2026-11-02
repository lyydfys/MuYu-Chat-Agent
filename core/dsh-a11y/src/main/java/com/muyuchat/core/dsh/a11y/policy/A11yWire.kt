package com.muyuchat.core.dsh.a11y.policy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes typed outcomes into the JSON documents the official `dsh-tool-accessibility` package
 * expects.
 *
 * Field names are the reference ones (`ok`, `error`, `running`, `nodes`, `held`, ...). Two MCA
 * extensions are additive and ignored by the official schema-driven package:
 * - every failure also carries `code` (a stable [com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode]);
 * - `/dump` carries `treeGeneration` and a per-node `ref`, which enables the stale-reference
 *   rejecting `/tap?ref=` path without changing the official fields.
 *
 * Honesty rules encoded here: a failed input or scroll is `ok:false` (the reference answered
 * `ok:true` with an `error` string, which its render step then displayed as success); a failed
 * global action is `ok:false`; a tap that found nothing stays `ok:true, found:false` so the
 * official package can still attach its own candidate list.
 */
object A11yWire {

    fun encode(outcome: A11yOutcome): String = when (outcome) {
        is A11yOutcome.Status -> status(outcome.status)
        is A11yOutcome.Dump -> dump(outcome.snapshot)
        is A11yOutcome.Tap -> tap(outcome.result)
        is A11yOutcome.Input -> input(outcome.result)
        is A11yOutcome.Scroll -> scroll(outcome.result)
        is A11yOutcome.GlobalAction -> globalAction(outcome.result)
        is A11yOutcome.Screenshot -> screenshot(outcome.result)
        is A11yOutcome.Touch -> touch(outcome.result)
        is A11yOutcome.TouchStatus -> touchStatus(outcome.report)
        is A11yOutcome.Rejected -> failure(outcome.rejection)
    }

    fun failure(rejection: A11yRejection): String = JSONObject()
        .put("ok", false)
        .put("error", rejection.message)
        .put("code", rejection.code.name)
        .toString()

    private fun status(status: A11yServiceStatus): String = JSONObject()
        .put("ok", true)
        .put("running", status.running)
        .put("package", status.packageName)
        .put("nodeCount", status.nodeCount)
        .put("apiLevel", status.apiLevel)
        .put("canScreenshot", status.canScreenshot)
        .put("windowId", status.windowId)
        .toString()

    private fun dump(snapshot: A11yTreeSnapshot): String {
        val nodes = JSONArray()
        snapshot.nodes.forEach { node ->
            val json = JSONObject()
                .put("text", node.text)
                .put("desc", node.desc)
                .put("cls", node.cls)
                .put("x", node.rect.x)
                .put("y", node.rect.y)
                .put("w", node.rect.w)
                .put("h", node.rect.h)
                .put("clickable", node.clickable)
                .put("input", node.input)
                .put("checked", node.checked)
                .put("selected", node.selected)
                .put("scrollable", node.scrollable)
                .put("depth", node.depth)
                .put("ref", node.ref.wire)
            if (node.redacted) json.put("redacted", true)
            nodes.put(json)
        }
        val document = JSONObject()
            .put("ok", true)
            .put("package", snapshot.packageName)
            .put("count", snapshot.nodes.size)
            .put("truncated", snapshot.truncated)
            .put("textTruncated", snapshot.textTruncated)
            .put("redactedCount", snapshot.redactedCount)
            .put("treeGeneration", snapshot.generation)
            .put("windowId", snapshot.windowId)
            .put("nodes", nodes)
        if (snapshot.nodes.isEmpty()) {
            document.put("hint", A11yTreePolicy.EMPTY_SCREEN_HINT)
        }
        return document.toString()
    }

    private fun tap(result: A11yTapResult): String {
        val document = JSONObject()
            .put("ok", true)
            .put("found", result.found)
            .put("method", result.method)
        result.error?.let { document.put("error", it) }
        return document.toString()
    }

    private fun input(result: A11yInputResult): String {
        val failed = result.error != null
        val document = JSONObject()
            .put("ok", !failed)
            .put("focused", result.focused)
            .put("method", result.method)
        result.error?.let { document.put("error", it) }
        return document.toString()
    }

    private fun scroll(result: A11yScrollResult): String {
        val failed = result.error != null
        val document = JSONObject()
            .put("ok", !failed)
            .put("direction", result.direction.name.lowercase())
            .put("method", result.method)
        result.error?.let { document.put("error", it) }
        return document.toString()
    }

    private fun globalAction(result: A11yGlobalActionResult): String {
        val document = JSONObject().put("ok", result.ok)
        if (!result.ok) document.put("error", "the system rejected the global action")
        return document.toString()
    }

    private fun screenshot(result: A11yScreenshot): String {
        val document = JSONObject()
            .put("ok", true)
            .put("path", result.path)
            .put("width", result.width)
            .put("height", result.height)
            .put("screenW", result.screenW)
            .put("screenH", result.screenH)
            .put("imageW", result.imageW)
            .put("imageH", result.imageH)
            .put("scaleX", result.scaleX)
            .put("scaleY", result.scaleY)
            .put("grid", result.grid)
            .put("bytes", result.bytes)
            .put("redactedRegions", result.redactedRegions)
        result.hint?.let { document.put("hint", it) }
        return document.toString()
    }

    private fun touch(result: A11yTouchDispatchResult): String = JSONObject()
        .put("ok", true)
        .put("durationMs", result.durationMs)
        .put("held", heldArray(result.held))
        .toString()

    private fun touchStatus(report: A11yTouchReport): String = JSONObject()
        .put("ok", true)
        .put("screenW", report.screenW)
        .put("screenH", report.screenH)
        .put("maxFingers", report.maxFingers)
        .put("holdTimeoutMs", report.holdTimeoutMs)
        .put("held", heldArray(report.held))
        .toString()

    private fun heldArray(held: List<A11yHeldFinger>): JSONArray {
        val array = JSONArray()
        held.forEach { finger ->
            array.put(
                JSONObject()
                    .put("finger", finger.finger)
                    .put("x", finger.x)
                    .put("y", finger.y)
                    .put("fx", finger.fx)
                    .put("fy", finger.fy)
                    .put("elapsedMs", finger.elapsedMs)
            )
        }
        return array
    }
}
