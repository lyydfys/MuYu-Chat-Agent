import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from check_state_write_boundaries import (
    VM_COMPOSITION_FILES,
    code_only,
    inspect_source,
    verify_authorities,
)


class StateWriteBoundaryTest(unittest.TestCase):
    def assert_rejected(self, source, message, path="BusinessOwner.kt"):
        errors = inspect_source(Path(path), source)
        self.assertTrue(any(message in error for error in errors), errors)

    def test_business_cannot_receive_store(self):
        self.assert_rejected("class Owner(val state: UiStateStore)", "root store")

    def test_composition_can_receive_store(self):
        self.assertEqual([], inspect_source(Path("composition/Factory.kt"), "fun make(s: UiStateStore) = s"))

    def test_mutable_flow_is_not_available_to_business(self):
        self.assert_rejected("val state: MutableStateFlow<MainUiState>", "global mutable flow")

    def test_root_reducer_and_root_writer_are_rejected(self):
        self.assert_rejected("val reducer: (MainUiState) -> MainUiState", "root-returning reducer")
        self.assert_rejected("val writer: UiStateWriter<MainUiState>", "unbounded writer")

    def test_wrong_injection_cannot_return(self):
        self.assert_rejected("injectedEngine: Any? = null", "untyped injected")

    def test_host_and_escape_hatch_cannot_return(self):
        self.assert_rejected("class MainCoordinatorHostAdapter", "retired coordinator")
        self.assert_rejected("val leaked = store.ownerFlow", "escape hatch")

    def test_comment_or_string_is_not_a_boundary(self):
        source = '''/* MainCoordinatorHost /* ownerFlow McaAppContainer */ */
        // reducer: (MainUiState) -> MainUiState
        val diagnostic = "UiStateStore McaAppContainer"
        val fixture = """MutableStateFlow<MainUiState> McaAppContainer"""
        '''
        self.assertEqual([], inspect_source(Path("Diagnostic.kt"), source))
        self.assertEqual(source.count("\n"), code_only(source).count("\n"))

    def test_business_cannot_mint_or_import_writers(self):
        self.assert_rejected("val result = s.writer(project, merge)", "raw writer issuance")
        self.assert_rejected("import com.muyuchat.mca.composition.chatStreamWriter", "writer factory")
        self.assert_rejected("import com.muyuchat.mca.composition.*", "writer factory")
        self.assert_rejected("val result = s.writer(project, merge)", "raw writer issuance", "composition/UnreviewedFactory.kt")

    def test_future_vm_named_file_is_not_exempt(self):
        self.assert_rejected("val store: UiStateStore", "root store", "MainViewModelNewBusiness.kt")

    def test_business_cannot_receive_application_container(self):
        for source in (
            "class Owner(val container: McaAppContainer)",
            "class Owner(val container: com.muyuchat.mca.composition.McaAppContainer)",
            "val container = com.muyuchat.mca.composition.McaAppContainer(application)",
        ):
            with self.subTest(source=source):
                self.assert_rejected(source, "application container")

    def test_business_cannot_import_or_alias_application_container(self):
        for source in (
            "import com.muyuchat.mca.composition.McaAppContainer",
            "import com.muyuchat.mca.composition.McaAppContainer as Dependencies",
        ):
            with self.subTest(source=source):
                self.assert_rejected(source, "application container")

    def test_reviewed_composition_entries_can_reference_application_container(self):
        source = "import com.muyuchat.mca.composition.McaAppContainer\nval container: McaAppContainer"
        for path in (
            "composition/Factory.kt",
            "composition/runtime/NestedFactory.kt",
            "AppStartupViewModel.kt",
            *sorted(VM_COMPOSITION_FILES),
        ):
            with self.subTest(path=path):
                self.assertEqual([], inspect_source(Path(path), source))

    def test_unknown_view_models_and_nested_entry_names_do_not_gain_container_access(self):
        for path in (
            "MainViewModelNewBusiness.kt",
            "FutureViewModel.kt",
            "AppStartupViewModelBusiness.kt",
            "nested/MainViewModel.kt",
            "nested/AppStartupViewModel.kt",
            "feature/composition/Owner.kt",
            "compositionBusiness/Owner.kt",
            "MainUiBootstrapFactory.kt",
        ):
            with self.subTest(path=path):
                self.assert_rejected("val container: McaAppContainer", "application container", path)

    def test_startup_container_entry_does_not_bypass_other_state_boundaries(self):
        self.assert_rejected("val store: UiStateStore", "root store", "AppStartupViewModel.kt")
        self.assert_rejected("val result = s.writer(project, merge)", "raw writer issuance", "AppStartupViewModel.kt")

    def test_container_diagnostic_preserves_source_line_numbers(self):
        source = "/* McaAppContainer\n nested comment */\nval container: McaAppContainer"
        self.assertEqual(
            ["BusinessOwner.kt:3: application container in business code"],
            inspect_source(Path("BusinessOwner.kt"), source),
        )

    def test_whitelist_rejects_foreign_merge_and_restored_snapshot(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "composition").mkdir()
            factory = '''internal fun UiStateStore.exampleWriter(): UiStateWriter<ExampleFields> = writer(
                project = { it.toExampleFields() }, merge = { current, fields -> fields.mergeInto(current) },)
            '''
            (root / "composition/UiStateDomainWriters.kt").write_text(factory)
            header = '''data class ExampleFields(override val snapshot: MainUiState,
                val input: String = snapshot.input,) : UiStateFields
            '''
            source = root / "ExampleFields.kt"
            source.write_text(header + "fun ExampleFields.mergeInto(current: MainUiState): MainUiState = current.copy(input = input,)")
            self.assertEqual([], verify_authorities(root, {"ExampleFields": ["input"]}))
            source.write_text(header + "fun ExampleFields.mergeInto(current: MainUiState): MainUiState = current.copy(input = input, models = snapshot.models,)")
            self.assertTrue(verify_authorities(root, {"ExampleFields": ["input"]}))
            source.write_text(header + "fun ExampleFields.mergeInto(current: MainUiState): MainUiState = snapshot.copy(input = input,)")
            self.assertTrue(verify_authorities(root, {"ExampleFields": ["input"]}))


if __name__ == "__main__":
    unittest.main()
