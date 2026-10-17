#ifndef MCA_MNN_CHAT_COMPLETION_HPP
#define MCA_MNN_CHAT_COMPLETION_HPP

#include <jni.h>

namespace mca {
namespace mnn {
namespace chat_session {

// Owns the beginCompletion transaction body. The JNI entry remains a stable
// NativeMnnBridge symbol and delegates here so business logic no longer lives
// in the JNI translation unit.
jint begin_mnn_completion(JNIEnv* env, jstring messagesJson, jstring paramsJson);

}  // namespace chat_session
}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_CHAT_COMPLETION_HPP
