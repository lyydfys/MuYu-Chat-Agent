package com.muyuchat.mca.agent.toolpack

/**
 * Reads third-party resources, WASM declarations and workflow/workspace templates.
 *
 * Every item is classified, never silently dropped: resources are preview-only, WASM is unsupported
 * because MCA has no WAMR runtime, and templates require a host import port that is not wired.
 */
internal class ThirdPartyManifestResourceReader(private val session: ThirdPartyManifestSession) {

    fun readResources(): Map<String, ThirdPartyResourceDescriptor> {
        val array = session.optionalArray("resources", "manifest") ?: return emptyMap()
        session.requireBoundedCount(array.items, THIRD_PARTY_MAX_RESOURCES, "manifest.resources")
        val resources = LinkedHashMap<String, ThirdPartyResourceDescriptor>()
        array.items.forEachIndexed { index, item ->
            val field = "manifest.resources[$index]"
            val obj = session.asObject(item, field)
            session.rejectUnknownFields(obj.fields, THIRD_PARTY_RESOURCE_FIELDS, field)
            val key = session.requireString(obj, "key", field)
            if (!THIRD_PARTY_ENTITY_ID.matches(key)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_ID, "$field.key", "resource key is invalid")
            }
            if (resources.containsKey(key)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "$field.key", "resource key is repeated")
            }
            val rawPath = session.requireString(obj, "path", field)
            val mime = session.optionalString(obj, "mime", field)
            val declaredDirectory = rawPath.endsWith('/') ||
                mime?.lowercase()?.contains("directory") == true
            val path = normalizeThirdPartyResourcePath(rawPath, declaredDirectory, "$field.path")
            val listed = session.listing.files[path] ?: session.listing.directories[path]
            if (listed != null && listed.isDirectory != declaredDirectory) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.INVALID_FIELD,
                    "$field.path",
                    "resource kind (file/directory) does not match the package listing"
                )
            }
            val exists = if (declaredDirectory) {
                session.listing.hasDirectory(path)
            } else {
                session.listing.hasFile(path)
            }
            if (!exists) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.RESOURCE_MISSING,
                    "$field.path",
                    "the declared resource is not present in the package listing"
                )
            }
            val descriptor = ThirdPartyResourceDescriptor(key, path, mime, declaredDirectory)
            resources[key] = descriptor
            val executable = !declaredDirectory && isExecutableThirdPartyResource(path)
            session.add(
                ThirdPartyCompatibilityCategory.RESOURCE,
                key,
                "$field.path",
                if (executable) ThirdPartyCompatibilityStatus.UNSUPPORTED
                else ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
                if (executable) {
                    "Executable/binary resource formats are not loaded from a third-party package."
                } else {
                    "MCA does not expose ToolPkg.readResource; resources only feed the preview fingerprint."
                },
                if (executable) "Remove the executable artifact or ship it as a separately reviewed package."
                else "Provide a read-only resource handle before resources can be consumed at run time."
            )
        }
        return resources
    }

    fun readLogo(resources: Map<String, ThirdPartyResourceDescriptor>) {
        val logo = session.optionalString("logo", "manifest") ?: return
        val resource = resources[logo]
            ?: thirdpartyFailure(
                ThirdPartyImportErrorCode.RESOURCE_MISSING,
                "manifest.logo",
                "logo must reference a declared resource key"
            )
        if (resource.isDirectory) {
            thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "manifest.logo", "logo must reference a file resource")
        }
    }

    fun readWasmModules(): List<ThirdPartyWasmModuleDescriptor> {
        val array = session.optionalArray("wasm_modules", "manifest") ?: return emptyList()
        session.requireBoundedCount(array.items, THIRD_PARTY_MAX_WASM_MODULES, "manifest.wasm_modules")
        val seen = HashSet<String>()
        return array.items.mapIndexed { index, item ->
            val field = "manifest.wasm_modules[$index]"
            val obj = session.asObject(item, field)
            session.rejectUnknownFields(obj.fields, THIRD_PARTY_WASM_FIELDS, field)
            val id = session.requireString(obj, "id", field)
            if (!THIRD_PARTY_ENTITY_ID.matches(id)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_ID, "$field.id", "wasm module id is invalid")
            }
            if (!seen.add(id)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "$field.id", "wasm module id is repeated")
            }
            val rawPath = session.requireString(obj, "path", field)
            val path = normalizeThirdPartyResourcePath(rawPath, isDirectory = false, field = "$field.path")
            if (!path.endsWith(".wasm")) {
                thirdpartyFailure(ThirdPartyImportErrorCode.UNSUPPORTED_ENTRY, "$field.path", "wasm path must end with .wasm")
            }
            if (!session.listing.hasFile(path)) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.ENTRY_MISSING,
                    "$field.path",
                    "the declared wasm module is not present in the package listing"
                )
            }
            val exports = session.optionalStringArray(obj, "exports", field)
            val abi = session.optionalString(obj, "abi", field)
            val sourceLanguage = session.optionalString(obj, "source_language", field)
            session.add(
                ThirdPartyCompatibilityCategory.WASM,
                id,
                field,
                ThirdPartyCompatibilityStatus.UNSUPPORTED,
                "MCA has no WAMR/WASM execution runtime; the module is declared but will not be loaded.",
                "Remove wasm_modules or implement a bounded WASM host capability with real execution tests."
            )
            ThirdPartyWasmModuleDescriptor(id, path, exports, abi, sourceLanguage)
        }
    }

    fun readTemplates(
        field: String,
        requireDirectory: Boolean,
        resources: Map<String, ThirdPartyResourceDescriptor>
    ): List<ThirdPartyTemplateDescriptor> {
        val array = session.optionalArray(field, "manifest") ?: return emptyList()
        session.requireBoundedCount(array.items, THIRD_PARTY_MAX_TEMPLATES, "manifest.$field")
        val seen = HashSet<String>()
        return array.items.mapIndexed { index, item ->
            val context = "manifest.$field[$index]"
            val obj = session.asObject(item, context)
            session.rejectUnknownFields(
                obj.fields,
                if (requireDirectory) THIRD_PARTY_WORKSPACE_TEMPLATE_FIELDS else THIRD_PARTY_WORKFLOW_TEMPLATE_FIELDS,
                context
            )
            val id = session.requireString(obj, "id", context)
            if (!THIRD_PARTY_ENTITY_ID.matches(id)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_ID, "$context.id", "template id is invalid")
            }
            if (!seen.add(id)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.INVALID_FIELD, "$context.id", "template id is repeated")
            }
            val resourceKey = session.requireString(obj, "resource_key", context)
            val resource = resources[resourceKey]
                ?: thirdpartyFailure(
                    ThirdPartyImportErrorCode.RESOURCE_MISSING,
                    "$context.resource_key",
                    "template must reference a declared resource"
                )
            if (resource.isDirectory != requireDirectory) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.INVALID_FIELD,
                    "$context.resource_key",
                    if (requireDirectory) "workspace template must reference a directory resource"
                    else "workflow template must reference a file resource"
                )
            }
            val projectType = if (requireDirectory) session.optionalString(obj, "project_type", context) else null
            obj.fields["display_name"]?.let { session.validateLocalized(it, "$context.display_name") }
            obj.fields["description"]?.let { session.validateLocalized(it, "$context.description") }
            session.add(
                ThirdPartyCompatibilityCategory.TEMPLATE,
                id,
                context,
                ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
                if (requireDirectory) {
                    "MCA does not wire a third-party workspace-template import port; nothing is copied or registered."
                } else {
                    "MCA does not wire a third-party workflow-template import port; nothing is created or registered."
                },
                "Add a host port that creates an auditable draft bound to the package content hash."
            )
            ThirdPartyTemplateDescriptor(id, resourceKey, projectType)
        }
    }
}
