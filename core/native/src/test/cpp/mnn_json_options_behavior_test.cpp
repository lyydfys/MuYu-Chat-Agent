// MCA E04 (pure migration, L1) REAL BEHAVIOUR test for mca::mnn JSON option helpers.
//
// Why this file exists:
//   opt_int/opt_bool/opt_double/opt_string/optional_json_*/merge_json_object/
//   advanced_json_from_params were moved verbatim out of the anonymous namespace in
//   mnn_native_engine.cpp into mca/mnn/mnn_json_options.hpp.  The move is only allowed to touch
//   text, never behaviour, so this test compiles the production header as-is and pins the value
//   contract: defaults, null handling, coercions, range/finiteness rejection, the exact
//   MNN-Diffusion error strings, recursive merge, and the non-throwing advanced_json decode.
//
// It calls the real mca::mnn functions (not a re-implementation) and includes degenerate
// negative controls (wrong root type, wrong field type, unsigned overflow, non-finite double,
// malformed advanced_json) that must return fallback/false rather than throw.

#include <cmath>
#include <cstdio>
#include <limits>
#include <string>

#include "mca/mnn/mnn_json_options.hpp"

namespace {

int failures = 0;

void expect(bool condition, const std::string& what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        ++failures;
    }
}

void expect_eq(const std::string& actual, const std::string& expected, const std::string& what) {
    if (actual != expected) {
        std::fprintf(stderr, "FAIL: %s (expected '%s', got '%s')\n",
                     what.c_str(), expected.c_str(), actual.c_str());
        ++failures;
    }
}

void expect_eq_int(int actual, int expected, const std::string& what) {
    if (actual != expected) {
        std::fprintf(stderr, "FAIL: %s (expected %d, got %d)\n", what.c_str(), expected, actual);
        ++failures;
    }
}

void expect_eq_double(double actual, double expected, const std::string& what) {
    if (actual != expected) {
        std::fprintf(stderr, "FAIL: %s (expected %g, got %g)\n", what.c_str(), expected, actual);
        ++failures;
    }
}

}  // namespace

int main() {
    using mca::mnn::advanced_json_from_params;
    using mca::mnn::merge_json_object;
    using mca::mnn::opt_bool;
    using mca::mnn::opt_double;
    using mca::mnn::opt_int;
    using mca::mnn::opt_string;
    using mca::mnn::optional_json_bool;
    using mca::mnn::optional_json_double;
    using mca::mnn::optional_json_int;
    using mca::mnn::optional_json_string;

    // ---- opt_int -------------------------------------------------------------------------
    {
        const nlohmann::json root = {{"n", 7}, {"f", 3.9}, {"huge", 1e300}, {"neg", -2},
                                     {"s", "42"}, {"ws", " 12 "}, {"bad", "abc"}, {"b", true}};
        expect_eq_int(opt_int(root, "n", -1), 7, "opt_int integer");
        expect_eq_int(opt_int(root, "f", -1), 3, "opt_int truncates finite float");
        expect_eq_int(opt_int(root, "huge", -1), -1, "opt_int out-of-range float falls back");
        expect_eq_int(opt_int(root, "neg", 0), -2, "opt_int negative");
        expect_eq_int(opt_int(root, "s", -1), 42, "opt_int numeric string");
        expect_eq_int(opt_int(root, "ws", -1), 12, "opt_int whitespace-padded string");
        expect_eq_int(opt_int(root, "bad", -1), -1, "opt_int malformed string falls back");
        expect_eq_int(opt_int(root, "b", -1), -1, "opt_int boolean falls back");
        expect_eq_int(opt_int(root, "missing", -1), -1, "opt_int missing key falls back");
        const nlohmann::json null_value = {{"n", nullptr}};
        expect_eq_int(opt_int(null_value, "n", -1), -1, "opt_int null falls back");
        const nlohmann::json not_object = nlohmann::json::array({1, 2});
        expect_eq_int(opt_int(not_object, "n", -1), -1, "opt_int non-object root falls back");
    }

    // ---- opt_bool ------------------------------------------------------------------------
    {
        const nlohmann::json root = {{"t", true}, {"f", false}, {"one", 1}, {"zero", 0},
                                     {"yes", "YES"}, {"no", "no"}, {"mixed", "True"},
                                     {"maybe", "maybe"}, {"spaced", " true "}};
        expect(opt_bool(root, "t", false), "opt_bool true");
        expect(!opt_bool(root, "f", true), "opt_bool false");
        expect(opt_bool(root, "one", false), "opt_bool integer 1");
        expect(!opt_bool(root, "zero", true), "opt_bool integer 0");
        expect(opt_bool(root, "yes", false), "opt_bool upper-case yes");
        expect(!opt_bool(root, "no", true), "opt_bool lower-case no");
        expect(opt_bool(root, "mixed", false), "opt_bool mixed-case true");
        expect(opt_bool(root, "maybe", true),
               "opt_bool unknown string returns the true fallback");
        expect(!opt_bool(root, "maybe", false),
               "opt_bool unknown string returns the false fallback");
        expect(!opt_bool(root, "spaced", false), "opt_bool untrimmed string falls back");
        expect(!opt_bool(root, "missing", false), "opt_bool missing falls back");
        const nlohmann::json not_object = 5;
        expect(opt_bool(not_object, "t", true), "opt_bool non-object root falls back");
    }

    // ---- opt_double ----------------------------------------------------------------------
    {
        const nlohmann::json root = {{"d", 4.5}, {"i", 2}, {"s", "2.5"}, {"bad", "abc"},
                                     {"b", true}};
        expect_eq_double(opt_double(root, "d", -1.0), 4.5, "opt_double float");
        expect_eq_double(opt_double(root, "i", -1.0), 2.0, "opt_double integer");
        expect_eq_double(opt_double(root, "s", -1.0), 2.5, "opt_double numeric string");
        expect_eq_double(opt_double(root, "bad", -1.0), -1.0, "opt_double malformed string falls back");
        expect_eq_double(opt_double(root, "b", -1.0), -1.0, "opt_double boolean falls back");
        expect_eq_double(opt_double(root, "missing", -1.0), -1.0, "opt_double missing falls back");
        nlohmann::json nan_value = {{"n", 0.0}};
        nan_value["n"] = std::numeric_limits<double>::quiet_NaN();
        expect_eq_double(opt_double(nan_value, "n", -1.0), -1.0, "opt_double NaN falls back");
        const nlohmann::json inf_string = {{"n", "1e999"}};
        expect_eq_double(opt_double(inf_string, "n", -1.0), -1.0, "opt_double out-of-range string falls back");
    }

    // ---- opt_string ----------------------------------------------------------------------
    {
        const nlohmann::json root = {{"s", "text"}, {"i", 42}, {"d", 2.5}, {"t", true},
                                     {"f", false}, {"n", nullptr}};
        expect_eq(opt_string(root, "s", "fb"), "text", "opt_string string");
        expect_eq(opt_string(root, "i", "fb"), "42", "opt_string integer renders");
        expect_eq(opt_string(root, "d", "fb"), std::to_string(2.5), "opt_string float renders like std::to_string");
        expect_eq(opt_string(root, "t", "fb"), "true", "opt_string true renders");
        expect_eq(opt_string(root, "f", "fb"), "false", "opt_string false renders");
        expect_eq(opt_string(root, "n", "fb"), "fb", "opt_string null falls back");
        expect_eq(opt_string(root, "missing"), "", "opt_string default fallback is empty");
    }

    // ---- optional_json_* error contract --------------------------------------------------
    {
        const nlohmann::json root = {{"s", "text"}, {"i", 5}, {"max", 2147483647},
                                     {"over", 2147483648ull}, {"d", 1.5}, {"b", true},
                                     {"n", nullptr}, {"wrong", "x"}};
        std::string value;
        std::string error;

        value.clear(); error.clear();
        expect(optional_json_string(root, "s", "fb", value, error), "optional_json_string ok");
        expect_eq(value, "text", "optional_json_string value");
        expect(error.empty(), "optional_json_string ok has empty error");
        value.clear(); error.clear();
        expect(optional_json_string(root, "n", "fb", value, error), "optional_json_string null uses fallback");
        expect_eq(value, "fb", "optional_json_string null fallback value");
        value.clear(); error.clear();
        expect(!optional_json_string(root, "i", "fb", value, error), "optional_json_string rejects number");
        expect_eq(error, "MNN-Diffusion parameter 'i' must be a string.", "optional_json_string error text");
        const nlohmann::json not_object = nlohmann::json::array();
        error.clear();
        expect(!optional_json_string(not_object, "s", "fb", value, error), "optional_json_string rejects non-object");
        expect_eq(error, "MNN-Diffusion parameters must be a JSON object.", "optional_json_string root error text");

        int int_value = 0;
        error.clear();
        expect(optional_json_int(root, "i", -1, int_value, error), "optional_json_int ok");
        expect_eq_int(int_value, 5, "optional_json_int value");
        error.clear();
        expect(optional_json_int(root, "n", -1, int_value, error), "optional_json_int null uses fallback");
        expect_eq_int(int_value, -1, "optional_json_int null fallback value");
        error.clear();
        expect(optional_json_int(root, "max", -1, int_value, error), "optional_json_int accepts 2^31-1");
        expect_eq_int(int_value, 2147483647, "optional_json_int 2^31-1 value");
        error.clear();
        expect(!optional_json_int(root, "over", -1, int_value, error), "optional_json_int rejects 2^31");
        expect_eq(error, "MNN-Diffusion parameter 'over' is outside the supported integer range.",
                  "optional_json_int range error text");
        error.clear();
        expect(!optional_json_int(root, "d", -1, int_value, error), "optional_json_int rejects float");
        expect_eq(error, "MNN-Diffusion parameter 'd' must be an integer.",
                  "optional_json_int type error text");

        double double_value = 0.0;
        error.clear();
        expect(optional_json_double(root, "d", -1.0, double_value, error), "optional_json_double ok");
        expect_eq_double(double_value, 1.5, "optional_json_double value");
        error.clear();
        expect(optional_json_double(root, "i", -1.0, double_value, error),
               "optional_json_double accepts integer numbers");
        expect_eq_double(double_value, 5.0, "optional_json_double integer value");
        error.clear();
        expect(!optional_json_double(root, "wrong", -1.0, double_value, error),
               "optional_json_double rejects strings");
        expect_eq(error, "MNN-Diffusion parameter 'wrong' must be a number.",
                  "optional_json_double type error text");
        nlohmann::json nan_root = {{"d", 1.0}};
        nan_root["d"] = std::numeric_limits<double>::quiet_NaN();
        error.clear();
        expect(!optional_json_double(nan_root, "d", -1.0, double_value, error),
               "optional_json_double rejects NaN");
        expect_eq(error, "MNN-Diffusion parameter 'd' must be finite.",
                  "optional_json_double finite error text");

        bool bool_value = false;
        error.clear();
        expect(optional_json_bool(root, "b", false, bool_value, error), "optional_json_bool ok");
        expect(bool_value, "optional_json_bool value");
        error.clear();
        expect(!optional_json_bool(root, "i", false, bool_value, error), "optional_json_bool rejects number");
        expect_eq(error, "MNN-Diffusion parameter 'i' must be a boolean.",
                  "optional_json_bool error text");
        error.clear();
        expect(optional_json_bool(root, "n", true, bool_value, error), "optional_json_bool null uses fallback");
        expect(bool_value, "optional_json_bool null fallback value");
    }

    // ---- merge_json_object ---------------------------------------------------------------
    {
        nlohmann::json target = {{"a", {{"x", 1}}}, {"b", 1}};
        const nlohmann::json override_value = {{"a", {{"y", 2}}}, {"c", 3}};
        merge_json_object(target, override_value);
        expect(target["a"]["x"] == 1 && target["a"]["y"] == 2, "merge_json_object recurses into objects");
        expect(target["b"] == 1, "merge_json_object keeps untouched keys");
        expect(target["c"] == 3, "merge_json_object adds new keys");
        const nlohmann::json array_override = nlohmann::json::array({1});
        merge_json_object(target, array_override);
        expect(target.contains("a"), "merge_json_object ignores non-object override");
        nlohmann::json replaced = {{"a", {{"x", 1}}}};
        merge_json_object(replaced, nlohmann::json({{"a", 7}}));
        expect(replaced["a"] == 7, "merge_json_object replaces when override value is not an object");
        nlohmann::json replaced_by_object = {{"a", 7}};
        merge_json_object(replaced_by_object, nlohmann::json({{"a", {{"x", 1}}}}));
        expect(replaced_by_object["a"].is_object() && replaced_by_object["a"]["x"] == 1,
               "merge_json_object replaces non-object target with object override");
    }

    // ---- advanced_json_from_params -------------------------------------------------------
    {
        expect(advanced_json_from_params(nlohmann::json::array()).empty(),
               "advanced_json_from_params non-object root yields empty object");
        expect(advanced_json_from_params(nlohmann::json({{"x", 1}})).empty(),
               "advanced_json_from_params missing key yields empty object");
        expect(advanced_json_from_params(nlohmann::json({{"advanced_json", nullptr}})).empty(),
               "advanced_json_from_params null yields empty object");
        const nlohmann::json object_value = {{"advanced_json", {{"seed", 7}}}};
        expect(advanced_json_from_params(object_value)["seed"] == 7,
               "advanced_json_from_params passes through object value");
        const nlohmann::json string_value = {{"advanced_json", "{\"steps\": 8}"}};
        expect(advanced_json_from_params(string_value)["steps"] == 8,
               "advanced_json_from_params parses JSON string");
        const nlohmann::json malformed_value = {{"advanced_json", "{not json"}};
        expect(advanced_json_from_params(malformed_value).empty(),
               "advanced_json_from_params malformed string yields empty object");
        const nlohmann::json array_string_value = {{"advanced_json", "[1,2]"}};
        expect(advanced_json_from_params(array_string_value).empty(),
               "advanced_json_from_params non-object JSON string yields empty object");
        const nlohmann::json number_value = {{"advanced_json", 5}};
        expect(advanced_json_from_params(number_value).empty(),
               "advanced_json_from_params number yields empty object");
    }

    if (failures != 0) {
        std::fprintf(stderr, "mnn_json_options_behavior_test: %d failure(s)\n", failures);
        return 1;
    }
    std::printf("mnn_json_options_behavior_test: PASS\n");
    return 0;
}