# third_party

Place external native dependencies here.

Expected future layout:

```text
third_party/
  llama.cpp/
```

The current `:core:native` module is a buildable JNI stub. After adding
`llama.cpp`, update `core/native/src/main/cpp/CMakeLists.txt` and
`native_engine.cpp` to call the real llama.cpp Android APIs.
